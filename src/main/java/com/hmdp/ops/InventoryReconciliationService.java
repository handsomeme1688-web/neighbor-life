package com.hmdp.ops;

import java.util.Map;

/**
 * Offline inventory inspection and bounded repair operations.
 * @author NeighborLife
 */
public interface InventoryReconciliationService {
    /**
     * Inspects database and Redis inventory state.
     * @param id activity identity
     * @return inventory and reservation diagnostics
     */
    Map<String, Object> inspect(Long id);

    /**
     * Rebuilds a drained offline activity under an inventory row lock.
     * @param id activity identity
     */
    void repair(Long id);
}
