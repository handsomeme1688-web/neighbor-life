package com.hmdp.entity;

import lombok.Data;

/**
 * Durable processing fence shared by order creation and dead-letter recovery.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
@Data
public class OrderEventRecord {

    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String outcome;
    private Integer compensated;
}
