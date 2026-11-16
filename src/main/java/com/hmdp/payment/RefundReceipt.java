package com.hmdp.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider full-refund status used for idempotent reconciliation.
 * @author NeighborLife
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundReceipt {
    private String refundNo;
    private String providerRefundId;
    private Long orderId;
    private Long amount;
    private String state;
}
