package com.hmdp.payment;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.BusinessException;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.OrderReliabilityIntegrationTest;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies payment, closing and refunds against real MySQL transactions.
 * @author NeighborLife
 */
@SpringBootTest(classes = OrderReliabilityIntegrationTest.TestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_INTEGRATION", matches = "true")
class PaymentFlowIntegrationTest {
    private static final long LOWER_BOUND = 7_000_000_000_000_000L;
    private static final long UPPER_BOUND = 8_000_000_000_000_000L;
    @Resource
    private PaymentService payments;
    @Resource
    private PaymentGateway gateway;
    @Resource
    private IVoucherOrderService orders;
    @Resource
    private JdbcTemplate jdbc;
    private long coupon;
    private long order;
    private long buyer;

    @BeforeEach
    void setUp() {
        coupon = ThreadLocalRandom.current().nextLong(LOWER_BOUND, UPPER_BOUND);
        order = coupon + 1L;
        buyer = coupon + 2L;
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,status) VALUES (?,1,'payment test',100,200,1)", coupon);
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) "
                + "VALUES (?,3,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))", coupon);
        UserDTO user = new UserDTO();
        user.setId(buyer);
        UserHolder.saveUser(user);
        orders.createVoucherOrder(new VoucherOrder().setId(order).setUserId(buyer).setVoucherId(coupon));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tb_inventory_log WHERE voucher_id=?", coupon);
        ReflectionTestUtils.setField(payments, "gateway", gateway);
        jdbc.update("DELETE FROM tb_order_close_task WHERE order_id=?", order);
        jdbc.update("DELETE FROM tb_payment_notification WHERE order_id=?", order);
        jdbc.update("DELETE FROM tb_refund WHERE order_id=?", order);
        jdbc.update("DELETE FROM tb_payment WHERE order_id=?", order);
        jdbc.update("DELETE FROM tb_order_event WHERE voucher_id=?", coupon);
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", coupon);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", coupon);
        jdbc.update("DELETE FROM tb_voucher WHERE id=?", coupon);
        UserHolder.removeUser();
    }

    @Test
    void preservesOriginalPriceAndReusesPrepayment() {
        jdbc.update("UPDATE tb_voucher SET pay_value=500 WHERE id=?", coupon);
        assertEquals(100L, ((Number) payments.initiate(order).get("amount")).longValue());
        assertEquals(payments.initiate(order).get("code_url"), payments.initiate(order).get("code_url"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment WHERE order_id=?", Integer.class, order));
    }

    @Test
    void rejectsWrongAmountWithoutChangingOrder() {
        payments.initiate(order);
        PaymentReceipt receipt = receipt();
        receipt.setAmount(1L);
        assertThrows(BusinessException.class, () -> payments.applyPaid(receipt));
        assertEquals(1, status());
        assertEquals(2, stock());
    }

    @Test
    void rejectsWrongCurrency() {
        payments.initiate(order);
        PaymentReceipt receipt = receipt();
        receipt.setCurrency("USD");
        assertThrows(BusinessException.class, () -> payments.applyPaid(receipt));
        assertEquals(1, status());
    }

    @Test
    void duplicateNotificationProducesOnePayment() {
        payments.initiate(order);
        payments.applyPaid(receipt());
        payments.applyPaid(receipt());
        assertEquals(2, status());
        assertEquals(2, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_payment_notification WHERE order_id=?", Integer.class, order));
    }

    @Test
    void conflictingTransactionCannotOverwriteSettlement() {
        payments.initiate(order);
        payments.applyPaid(receipt());
        PaymentReceipt conflict = receipt();
        conflict.setTransactionId("different");
        assertThrows(BusinessException.class, () -> payments.applyPaid(conflict));
        assertEquals("trade-" + order, jdbc.queryForObject("SELECT transaction_id FROM tb_payment WHERE order_id=?", String.class, order));
    }

    @Test
    void reconciliationFindsPaymentBeforeClosing() {
        payments.initiate(order);
        ((MockPaymentGateway) gateway).settle(order);
        expire();
        assertFalse(orders.closeUnpaidOrder(order));
        assertEquals(2, status());
        assertEquals(2, stock());
    }

    @Test
    void gatewayFailurePreservesStockAndClosingTaskCanRetry() {
        payments.initiate(order);
        expire();
        PaymentGateway unavailable = mock(PaymentGateway.class);
        when(unavailable.query(order)).thenThrow(new IllegalStateException("offline"));
        ReflectionTestUtils.setField(payments, "gateway", unavailable);
        assertThrows(IllegalStateException.class, () -> orders.closeUnpaidOrder(order));
        assertEquals(7, status());
        assertEquals(2, stock());
        ReflectionTestUtils.setField(payments, "gateway", gateway);
        assertTrue(orders.closeUnpaidOrder(order));
        assertEquals(3, stock());
    }

    @Test
    void latePaymentQueuesRefundAndPreservesCanceledOrder() {
        payments.initiate(order);
        expire();
        assertTrue(orders.closeUnpaidOrder(order));
        payments.applyPaid(receipt());
        assertEquals(4, status());
        assertEquals(3, stock());
        RefundReceipt refund = gateway.refund(order, "refund-" + order, 100L, "LATE_PAYMENT");
        payments.applyRefund(refund);
        payments.applyRefund(refund);
        assertEquals(4, status());
        assertEquals(3, stock());
        assertEquals("REFUNDED", payments.queryOwned(order).get("state"));
    }

    @Test
    void repeatedBuyerRefundRestoresStockOnce() {
        payments.initiate(order);
        payments.applyPaid(receipt());
        String refundNo = payments.requestRefund(order);
        assertEquals(refundNo, payments.requestRefund(order));
        RefundReceipt refund = gateway.refund(order, refundNo, 100L, "BUYER_REQUEST");
        payments.applyRefund(refund);
        payments.applyRefund(refund);
        assertEquals(6, status());
        assertEquals(3, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_refund WHERE order_id=?", Integer.class, order));
    }

    @Test
    void closedRefundBecomesVisibleRequiredAction() {
        payments.initiate(order);
        payments.applyPaid(receipt());
        String refundNo = payments.requestRefund(order);
        payments.applyRefund(new RefundReceipt(refundNo, "provider", order, 100L, "CLOSED"));
        assertEquals("REQUIRES_ACTION", jdbc.queryForObject("SELECT state FROM tb_refund WHERE refund_no=?", String.class, refundNo));
        assertEquals(5, status());
        assertEquals(2, stock());
    }

    @Test
    void rejectsUnpaidRefund() {
        assertThrows(BusinessException.class, () -> payments.requestRefund(order));
        assertEquals(1, status());
    }

    @Test
    void hidesPaymentFromOtherUser() {
        payments.initiate(order);
        UserDTO stranger = new UserDTO();
        stranger.setId(buyer + 1L);
        UserHolder.saveUser(stranger);
        assertThrows(BusinessException.class, () -> payments.queryOwned(order));
    }

    private PaymentReceipt receipt() {
        return new PaymentReceipt("notice-" + order, order, 100L, "CNY", "trade-" + order, "SUCCESS");
    }
    private int status() { return jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, order); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, coupon); }
    private void expire() { jdbc.update("UPDATE tb_voucher_order SET expire_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?", order); }
}
