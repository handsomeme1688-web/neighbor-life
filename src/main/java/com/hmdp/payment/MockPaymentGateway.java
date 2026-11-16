package com.hmdp.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit local channel simulation used by integration tests and administrator tooling.
 * @author NeighborLife
 */
@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "mock")
public class MockPaymentGateway extends DisabledPaymentGateway {
    private static final String PAYMENT_SUCCESS = "SUCCESS";
    private static final String PAYMENT_CLOSED = "CLOSED";

    private final Map<Long, PaymentReceipt> trades = new ConcurrentHashMap<>();
    private final Map<String, RefundReceipt> refunds = new ConcurrentHashMap<>();

    @Override
    public String prepay(Long orderId, Long amount, Instant expiresAt) {
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("订单支付已到期");
        }
        trades.putIfAbsent(orderId, new PaymentReceipt(null, orderId, amount, "CNY", null, "NOTPAY"));
        return "mock://payment/" + orderId;
    }

    @Override
    public PaymentReceipt query(Long orderId) {
        PaymentReceipt receipt = trades.get(orderId);
        if (receipt == null) {
            return new PaymentReceipt(null, orderId, 0L, "CNY", null, "NOT_CREATED");
        }
        return receipt;
    }

    @Override
    public void close(Long orderId) {
        PaymentReceipt receipt = query(orderId);
        synchronized (receipt) {
            if (!PAYMENT_SUCCESS.equals(receipt.getState())) {
                receipt.setState(PAYMENT_CLOSED);
            }
        }
    }

    public void settle(Long orderId) {
        PaymentReceipt receipt = query(orderId);
        synchronized (receipt) {
            if (PAYMENT_CLOSED.equals(receipt.getState())) {
                throw new IllegalArgumentException("渠道订单已关闭");
            }
            receipt.setTransactionId("mock-" + orderId);
            receipt.setState(PAYMENT_SUCCESS);
        }
    }

    @Override
    public RefundReceipt refund(Long orderId, String refundNo, Long amount, String reason) {
        refunds.putIfAbsent(refundNo, new RefundReceipt(refundNo, "mock-" + refundNo, orderId, amount, PAYMENT_SUCCESS));
        return refunds.get(refundNo);
    }

    @Override
    public RefundReceipt queryRefund(String refundNo) {
        return refunds.get(refundNo);
    }
}
