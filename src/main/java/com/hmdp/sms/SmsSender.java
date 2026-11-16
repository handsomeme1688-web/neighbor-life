package com.hmdp.sms;

/**
 * Sends login codes and retrieves provider delivery receipts.
 * @author NeighborLife
 */
public interface SmsSender {
    /**
     * Submits a login code.
     * @param phone recipient phone number
     * @param code one-time code
     * @return provider message identity
     */
    String send(String phone, String code);

    /**
     * Queries a previous submission.
     * @param phone recipient phone number
     * @param bizId provider message identity
     * @param sendDate submission date in yyyyMMdd format
     * @return PENDING, DELIVERED or FAILED
     */
    String receipt(String phone, String bizId, String sendDate);
}
