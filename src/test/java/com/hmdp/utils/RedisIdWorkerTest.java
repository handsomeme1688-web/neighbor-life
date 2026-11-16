package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies generated timestamps use UTC across differently configured instances.
 * @author NeighborLife
 */
class RedisIdWorkerTest {
    private static final long BEGIN_TIMESTAMP = 1767225600L;

    @Test
    void usesEpochTimeIndependentlyOfHostTimeZone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.increment(anyString())).thenReturn(1L);
            RedisIdWorker worker = new RedisIdWorker();
            ReflectionTestUtils.setField(worker, "stringRedisTemplate", redis);
            long before = Instant.now().getEpochSecond();
            long id = worker.nextId("order");
            long second = (id >>> 32) + BEGIN_TIMESTAMP;
            assertTrue(second >= before && second <= Instant.now().getEpochSecond());
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
