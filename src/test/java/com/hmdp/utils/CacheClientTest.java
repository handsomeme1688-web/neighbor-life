package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises cache degradation when the bounded rebuild queue is full.
 * @author NeighborLife
 */
class CacheClientTest {
    @Test
    void returnsStaleValueAndReleasesLockWhenRebuildIsRejected() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisData cached = new RedisData();
        cached.setData(new Shop().setId(1L).setName("cached"));
        cached.setExpireTime(LocalDateTime.now().minusSeconds(1L));
        when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(cached));
        when(values.setIfAbsent(eq("lock:cache:cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        CacheClient cache = new CacheClient();
        ReflectionTestUtils.setField(cache, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(cache, "cacheRebuildExecutor", executor);
        Shop result = cache.queryWithLogicalExpire("cache:shop:", 1L, Shop.class,
                id -> { throw new AssertionError("request thread queried database"); }, 30L, TimeUnit.MINUTES);
        assertEquals("cached", result.getName());
        verify(redis).execute(any(), eq(java.util.Collections.singletonList("lock:cache:cache:shop:1")), anyString());
    }
}
