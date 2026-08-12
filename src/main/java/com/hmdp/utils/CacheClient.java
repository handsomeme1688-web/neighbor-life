package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * Shared Redis cache helper with cache-penetration and hot-key protection.
 */
@Slf4j
@Component
public class CacheClient {

    private static final int MISS_RETRY_COUNT = 20;
    private static final long MISS_RETRY_DELAY_MS = 50L;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // Physical expiry is the final safety net if invalidation/rebuild fails.
        long physicalTtlSeconds = Math.max(unit.toSeconds(time) * 2, 60L);
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(redisData), physicalTtlSeconds, TimeUnit.SECONDS);
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R value = dbFallback.apply(id);
        if (value == null) {
            stringRedisTemplate.opsForValue().set(
                    key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        set(key, value, time, unit);
        return value;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            return loadOnMiss(key, id, type, dbFallback, time, unit);
        }
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData cached = JSONUtil.toBean(json, RedisData.class);
        if (cached.getExpireTime() == null) {
            // Old versions stored plain JSON. Remove it once and rebuild safely.
            stringRedisTemplate.delete(key);
            return loadOnMiss(key, id, type, dbFallback, time, unit);
        }
        R value = convert(cached.getData(), type);
        if (cached.getExpireTime().isAfter(LocalDateTime.now())) {
            return value;
        }

        rebuildExpiredValue(key, id, dbFallback, time, unit);
        return value;
    }

    private <R, ID> R loadOnMiss(
            String key, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String lockKey = LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < MISS_RETRY_COUNT; attempt++) {
            String lockToken = tryLock(lockKey);
            if (lockToken != null) {
                try {
                    String secondCheck = stringRedisTemplate.opsForValue().get(key);
                    if (secondCheck != null) {
                        if (StrUtil.isBlank(secondCheck)) {
                            return null;
                        }
                        RedisData cached = JSONUtil.toBean(secondCheck, RedisData.class);
                        if (cached.getExpireTime() != null) {
                            return convert(cached.getData(), type);
                        }
                    }

                    R value = dbFallback.apply(id);
                    if (value == null) {
                        stringRedisTemplate.opsForValue().set(
                                key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    setWithLogicalExpire(key, value, time, unit);
                    return value;
                } finally {
                    unlock(lockKey, lockToken);
                }
            }
            sleepBeforeRetry();

            String cachedJson = stringRedisTemplate.opsForValue().get(key);
            if (cachedJson != null) {
                if (StrUtil.isBlank(cachedJson)) {
                    return null;
                }
                RedisData cached = JSONUtil.toBean(cachedJson, RedisData.class);
                if (cached.getExpireTime() != null) {
                    return convert(cached.getData(), type);
                }
            }
        }
        throw new IllegalStateException("缓存重建繁忙，请稍后重试");
    }

    private <R, ID> void rebuildExpiredValue(
            String key, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String lockKey = LOCK_SHOP_KEY + id;
        String lockToken = tryLock(lockKey);
        if (lockToken == null) {
            return;
        }

        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                R latest = dbFallback.apply(id);
                if (latest == null) {
                    stringRedisTemplate.opsForValue().set(
                            key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                } else {
                    setWithLogicalExpire(key, latest, time, unit);
                }
            } catch (Exception e) {
                log.error("异步重建缓存失败, key={}", key, e);
            } finally {
                unlock(lockKey, lockToken);
            }
        });
    }

    private <R> R convert(Object data, Class<R> type) {
        if (data instanceof JSONObject) {
            return JSONUtil.toBean((JSONObject) data, type);
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(acquired) ? token : null;
    }

    private void unlock(String key, String token) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(MISS_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("缓存查询被中断", e);
        }
    }
}
