package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generates IDs using a UTC timestamp and a shared daily Redis sequence.
 *
 * @author NeighborLife
 */
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1767225600L;
    private static final int COUNT_BITS = 32;
    private static final long MAX_SEQUENCE = 0xFFFFFFFFL;
    private static final DateTimeFormatter COUNTER_DATE =
            DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        Instant now = Instant.now();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        String key = "icr:" + keyPrefix + ":" + COUNTER_DATE.format(now);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null || count < 1 || count > MAX_SEQUENCE) {
            throw new IllegalStateException("订单编号序列暂不可用");
        }
        return timestamp << COUNT_BITS | count;
    }
}
