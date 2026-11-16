package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * Buyer-owned redemption credentials and merchant coupon fulfillment.
 * @author NeighborLife
 */
public interface IOrderFulfillmentService {
    /**
     * Issues a short-lived credential for an owned paid order.
     * @param orderId owned order identity
     * @return redemption credential and validity
     */
    Result issue(Long orderId);
    /**
     * Redeems a paid order for an authorized merchant.
     * @param orderId order identity
     * @param code buyer-presented credential
     * @return idempotent fulfillment result
     */
    Result redeem(Long orderId, String code);
}
