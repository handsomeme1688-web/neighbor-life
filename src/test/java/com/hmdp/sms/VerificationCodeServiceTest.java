package com.hmdp.sms;

import com.hmdp.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies SMS cooldown, provider failures and one-time code storage ordering.
 * @author NeighborLife
 */
class VerificationCodeServiceTest {
    private VerificationCodeServiceImpl codes;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SmsSender sender;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        codes = new VerificationCodeServiceImpl();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sender = mock(SmsSender.class);
        jdbc = mock(JdbcTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(redis.execute(any(), anyList(), anyString(), anyString())).thenReturn(1L);
        ReflectionTestUtils.setField(codes, "redis", redis);
        ReflectionTestUtils.setField(codes, "sender", sender);
        ReflectionTestUtils.setField(codes, "jdbc", jdbc);
    }

    @Test
    void enforcesPhoneCooldownBeforeSending() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        assertThrows(RateLimitExceededException.class, () -> codes.issue("19900000001"));
        verifyNoInteractions(sender);
    }

    @Test
    void avoidsSendingUnstoredCode() {
        when(redis.execute(any(), anyList(), anyString(), anyString())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> codes.issue("19900000001"));
        verifyNoInteractions(sender);
    }

    @Test
    void releasesFailedSubmissionSoCallerCanRetry() {
        when(sender.send(anyString(), anyString())).thenThrow(new IllegalStateException("provider offline"));
        assertThrows(IllegalStateException.class, () -> codes.issue("19900000001"));
        verify(redis, times(2)).execute(any(), anyList(), anyString());
        verifyNoInteractions(jdbc);
    }

    @Test
    void recordsAcceptedProviderMessage() {
        when(sender.send(anyString(), anyString())).thenReturn("message-1");
        codes.issue("19900000001");
        verify(jdbc).update(anyString(), anyString(), eq("19900000001"), eq("message-1"), anyString());
    }
}
