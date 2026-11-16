package com.hmdp.controller;

import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Guards the simulation-only payment operation behind an explicit opt-in.
 * @author NeighborLife
 */
class VoucherOrderControllerTest {
    @Test
    void disablesSimulatedPaymentByDefault() {
        VoucherOrderController controller = new VoucherOrderController();
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        ReflectionTestUtils.setField(controller, "voucherOrderService", service);
        assertEquals("DEMO_PAYMENT_DISABLED", controller.payOrder(1L).getCode());
        verifyNoInteractions(service);
    }
}
