package com.hmdp.entity;

import lombok.Data;

/**
 * Cache invalidation written with the corresponding business transaction.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
@Data
public class CacheInvalidationRecord {
    private Long id;
    private Long shopId;
    private String resourceType;
}
