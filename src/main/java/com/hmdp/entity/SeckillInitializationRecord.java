package com.hmdp.entity;

import lombok.Data;

/**
 * Immutable activity initialization data retained until Redis acknowledges it.
 * @author NeighborLife
 */
@Data
public class SeckillInitializationRecord {
    private Long voucherId;
    private Integer stock;
    private Long beginSecond;
    private Long endSecond;
}
