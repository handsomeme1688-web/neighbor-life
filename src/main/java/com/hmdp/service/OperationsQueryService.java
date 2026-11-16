package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * Bounded administrative query operations.
 * @author NeighborLife
 */
public interface OperationsQueryService {
    /**
     * Reads the recent poison-message queue.
     * @return pending and processed quarantined messages
     */
    Result quarantine();
    /**
     * Reads the recent payment ledger.
     * @return recent payment ledger entries
     */
    Result payments();
    /**
     * Reads the recent refund queue.
     * @return recent refund processing entries
     */
    Result refunds();
    /**
     * Reads comment reports for administrator moderation.
     * @return recent comment reports for moderation
     */
    Result commentReports();
}
