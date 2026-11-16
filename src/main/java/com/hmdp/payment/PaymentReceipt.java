package com.hmdp.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Verified provider payment state without payer personal data.
 * @author NeighborLife
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReceipt {
    private String notificationId;
    private Long orderId;
    private Long amount;
    private String currency;
    private String transactionId;
    private String state;
}
