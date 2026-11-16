package com.hmdp.payment;

import java.time.Instant;
import java.util.Map;

/**
 * Payment-provider boundary; values entering the ledger are verified server-side.
 * @author NeighborLife
 */
public interface PaymentGateway {
    /**
     * Creates an expiring Native payment.
     * @param orderId stable merchant order number
     * @param amount price in cents
     * @param expiresAt fixed payment deadline
     * @return payment code URL
     */
    String prepay(Long orderId, Long amount, Instant expiresAt);

    /**
     * Queries authoritative provider state.
     * @param orderId merchant order number
     * @return provider receipt
     */
    PaymentReceipt query(Long orderId);

    /**
     * Closes an unpaid provider trade.
     * @param orderId merchant order number
     */
    void close(Long orderId);

    /**
     * Verifies and decrypts a provider callback.
     * @param headers signature headers
     * @param body unmodified callback body
     * @return verified receipt
     */
    PaymentReceipt notification(Map<String, String> headers, String body);

    /**
     * Starts an idempotent full refund.
     * @param orderId merchant order number
     * @param refundNo stable merchant refund number
     * @param amount refund amount in cents
     * @param reason refund reason
     * @return provider refund receipt
     */
    RefundReceipt refund(Long orderId, String refundNo, Long amount, String reason);

    /**
     * Queries an existing refund.
     * @param refundNo stable merchant refund number
     * @return provider refund receipt
     */
    RefundReceipt queryRefund(String refundNo);
}
