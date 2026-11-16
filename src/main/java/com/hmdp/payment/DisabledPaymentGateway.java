package com.hmdp.payment;

import com.hmdp.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;

/**
 * Fails closed while payment credentials are absent.
 * @author NeighborLife
 */
@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "disabled", matchIfMissing = true)
public class DisabledPaymentGateway implements PaymentGateway {
    protected BusinessException unavailable() {
        return new BusinessException("PAYMENT_DISABLED", "支付通道尚未启用", HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Override
    public String prepay(Long orderId, Long amount, Instant expiresAt) { throw unavailable(); }
    @Override
    public PaymentReceipt query(Long orderId) { throw unavailable(); }
    @Override
    public void close(Long orderId) { throw unavailable(); }
    @Override
    public PaymentReceipt notification(Map<String, String> headers, String body) { throw unavailable(); }
    @Override
    public RefundReceipt refund(Long orderId, String refundNo, Long amount, String reason) { throw unavailable(); }
    @Override
    public RefundReceipt queryRefund(String refundNo) { throw unavailable(); }
}
