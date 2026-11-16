package com.hmdp.payment;

import com.hmdp.exception.BusinessException;
import com.hmdp.security.AccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Application boundary for PaymentService.
 * @author NeighborLife
 */
public interface PaymentService {
    /**
     * Creates or reuses a payment for the current buyer.
     * @param orderId merchant order identity
     * @return operation result
     */
    Map<String, Object> initiate(Long orderId);

    /**
     * Returns the current buyer payment ledger.
     * @param orderId merchant order identity
     * @return operation result
     */
    Map<String, Object> queryOwned(Long orderId);

    /**
     * Reconciles a payment using authoritative provider state.
     * @param orderId merchant order identity
     */
    void refresh(Long orderId);

    /**
     * Verifies and applies a provider callback.
     * @param headers provider signature headers
     * @param body unmodified provider callback
     */
    void notification(Map<String, String> headers, String body);

    /**
     * Atomically applies a verified payment receipt.
     * @param receipt verified provider receipt
     */
    void applyPaid(PaymentReceipt receipt);

    /**
     * Closes an expired trade and restores stock once.
     * @param orderId merchant order identity
     * @return whether the operation completed
     */
    boolean closeExpired(Long orderId);

    /**
     * Queues an idempotent full refund for the current buyer.
     * @param orderId merchant order identity
     * @return operation result
     */
    String requestRefund(Long orderId);

    /**
     * Applies a reconciled provider refund result.
     * @param receipt verified provider receipt
     */
    void applyRefund(RefundReceipt receipt);

}
