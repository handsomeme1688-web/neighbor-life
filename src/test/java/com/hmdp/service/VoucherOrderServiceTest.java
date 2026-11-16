package com.hmdp.service;

import com.hmdp.entity.OrderEventRecord;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderEventMapper;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises failed-reservation fences and compensation acknowledgements.
 * @author NeighborLife
 */
class VoucherOrderServiceTest {
    private VoucherOrderServiceImpl service;
    private StringRedisTemplate redis;
    private OrderEventMapper events;
    private VoucherOrder order;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        redis = mock(StringRedisTemplate.class);
        events = mock(OrderEventMapper.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "orderEventMapper", events);
        order = new VoucherOrder().setId(101L).setUserId(1L).setVoucherId(2L);
    }

    @Test
    void leavesCanceledCompensationPendingWhenStockKeyIsMissing() {
        when(redis.execute(any(), eq(Collections.emptyList()), eq("2"), eq("101"))).thenReturn(2L);
        assertThrows(IllegalStateException.class, () -> service.compensateCanceledOrder(order));
    }

    @Test
    void leavesRejectedCompensationPendingWhenRedisReturnsNull() {
        assertThrows(IllegalStateException.class, () -> service.compensateFailedOrder(order));
        verify(events, never()).markCompensated(anyLong());
    }

    @Test
    void leavesLegacyReservationForReconciliation() {
        when(redis.execute(any(), eq(Collections.emptyList()), eq("2"), eq("1"), eq("101")))
                .thenReturn(2L);
        assertThrows(IllegalStateException.class, () -> service.compensateFailedOrder(order));
        verify(events, never()).markCompensated(anyLong());
    }

    @Test
    void acknowledgesAlreadyCompensatedReservation() {
        when(redis.execute(any(), eq(Collections.emptyList()), eq("2"), eq("1"), eq("101")))
                .thenReturn(0L);
        service.compensateFailedOrder(order);
        verify(events).markCompensated(101L);
    }

    @Test
    void ignoresLateCreationAfterDurableRejection() {
        OrderEventRecord event = new OrderEventRecord();
        event.setUserId(1L);
        event.setVoucherId(2L);
        event.setOutcome("REJECTED");
        when(events.lock(101L)).thenReturn(event);
        service.createVoucherOrder(order);
        verify(events, never()).finish(anyLong(), anyString());
    }

    @Test
    void rejectsReusedOrderIdWithDifferentIdentity() {
        OrderEventRecord event = new OrderEventRecord();
        event.setUserId(9L);
        event.setVoucherId(2L);
        when(events.lock(101L)).thenReturn(event);
        assertThrows(IllegalArgumentException.class, () -> service.createVoucherOrder(order));
    }
}
