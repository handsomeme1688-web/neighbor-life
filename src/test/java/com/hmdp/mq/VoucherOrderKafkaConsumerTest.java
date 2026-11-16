package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies dead-letter recovery sequencing and retry propagation.
 * @author NeighborLife
 */
class VoucherOrderKafkaConsumerTest {
    private static final String PAYLOAD = "{\"orderId\":101,\"userId\":1,\"voucherId\":2}";
    private IVoucherOrderService service;
    private VoucherOrderKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        service = mock(IVoucherOrderService.class);
        consumer = new VoucherOrderKafkaConsumer();
        ReflectionTestUtils.setField(consumer, "voucherOrderService", service);
    }

    @Test
    void preservesReservationWhenOrderAlreadySucceeded() {
        when(service.rejectFailedOrder(any())).thenReturn(false);
        consumer.compensateDeadLetter(PAYLOAD);
        verify(service, never()).compensateFailedOrder(any());
    }

    @Test
    void persistsRejectionBeforeReleasingReservation() {
        when(service.rejectFailedOrder(any())).thenReturn(true);
        consumer.compensateDeadLetter(PAYLOAD);
        org.mockito.InOrder sequence = inOrder(service);
        sequence.verify(service).rejectFailedOrder(any());
        sequence.verify(service).compensateFailedOrder(any());
    }

    @Test
    void propagatesCompensationFailureForKafkaRetry() {
        when(service.rejectFailedOrder(any())).thenReturn(true);
        doThrow(new IllegalStateException("Redis unavailable")).when(service).compensateFailedOrder(any());
        assertThrows(IllegalStateException.class, () -> consumer.compensateDeadLetter(PAYLOAD));
    }
}
