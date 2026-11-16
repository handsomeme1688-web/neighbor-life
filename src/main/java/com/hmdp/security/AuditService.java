package com.hmdp.security;



/**
 * Application boundary for AuditService.
 * @author NeighborLife
 */
public interface AuditService {
    /**
     * Writes an audit record in the caller business transaction.
     * @param action audited business operation
     * @param resourceId business resource identity
     * @param before state before the operation
     * @param after state after the operation
     */
    void record(String action, Object resourceId, Object before, Object after);

}
