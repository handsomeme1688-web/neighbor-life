package com.hmdp.mq;

import com.hmdp.entity.SeckillInitializationRecord;
import com.hmdp.mapper.SeckillInitializationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies durable activity publication when Redis is unavailable or incomplete.
 * @author NeighborLife
 */
class SeckillInitializationHandlerTest {
    private SeckillInitializationHandler handler;
    private SeckillInitializationMapper mapper;
    private StringRedisTemplate redis;
    private SeckillInitializationRecord record;

    @BeforeEach
    void setUp() {
        handler = new SeckillInitializationHandler();
        mapper = mock(SeckillInitializationMapper.class);
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(handler, "initializationMapper", mapper);
        ReflectionTestUtils.setField(handler, "stringRedisTemplate", redis);
        record = new SeckillInitializationRecord();
        record.setVoucherId(1L);
        record.setStock(3);
        record.setBeginSecond(0L);
        record.setEndSecond(1L);
    }

    @Test
    void retainsOutboxWhenRedisIsUnavailable() {
        handler.initializeAfterCommit(record);
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    void retainsPartialStateForReconciliation() {
        when(redis.execute(any(), anyList(), anyString(), anyString(), anyString())).thenReturn(2L);
        handler.initializeAfterCommit(record);
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    void acknowledgesAlreadyInitializedActivity() {
        when(redis.execute(any(), eq(Arrays.asList("seckill:stock:1", "seckill:begin:1", "seckill:end:1")),
                eq("3"), eq("0"), eq("1"))).thenReturn(0L);
        handler.initializeAfterCommit(record);
        verify(mapper).delete(1L);
    }
}
