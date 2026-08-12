package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.security.Key;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;


@Slf4j
@Component // 这个bean将来由spring去维护
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
                                                // 把value封装到RedisData中
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis    // 在存数据的时候，给数据里加上一个逻辑过期的字段
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) { // 封装缓存穿透的代码
        String key = keyPrefix + id;                                           // 有参有返回值的函数
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 缓存命中，直接返回          // R的类型 Class<R> type
            return JSONUtil.toBean(json, type); // 反序列化成java对象返回
        }
        // TODO 判断命中的是否是空值
        if (json != null) { // 是空字符串
            return null;
        }
        // 4. 缓存未命中，根据id查询数据库
        // 这一段逻辑我们不知道,交给调用者,要求他把这段逻辑返回给我们
        R r = dbFallback.apply(id);
        // 5. 不存在，返回404
        if (r == null) {
            // TODO 将空值写入redis
            stringRedisTemplate.opsForValue().
                    set(key, "",
                            RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 然后返回错误信息
            return null;
        }
        // 6. 存在，写入redis
        this.set(key, r, time, unit);
        // 7. 返回
        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) { // 封装缓存穿透的代码
        String key = keyPrefix + id;
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. 缓存未命中，直接返回
            return null;
        }
        // 4. 命中,需要判断过期时间
        // 需要先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期,直接返回店铺信息
            return r;
        }
        // 5.2 已过期,需要缓存重建
        // 6. 重建缓存
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if (isLock) {
            // TODO 6.3 成功,开启独立线程,开始缓存重建
            // 用一个线程池,不要自己去写一个线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 先查数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                    // this.方法代表调用本对象的方法也就是下面那个方法
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
        }
        // 6.4 无论是否成功,返回过期的商铺信息
        // 7. 返回
        return r;
    }


    private boolean tryLock(String key) { // 锁就是redis里存储的一个key
        // 尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().
                setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 就是setnx
        // 转成基本类型返回
        return BooleanUtil.isTrue(flag);

    }
    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
