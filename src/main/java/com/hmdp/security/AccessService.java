package com.hmdp.security;

import com.hmdp.exception.BusinessException;
import com.hmdp.utils.UserHolder;
import java.util.Arrays;

/**
 * Application boundary for AccessService.
 * @author NeighborLife
 */
public interface AccessService {
    /**
     * Returns the current authenticated identity.
     * @return operation result
     */
    long userId();

    /**
     * Evaluates bootstrap and database administrator roles.
     * @param userId user identity
     * @return whether the operation completed
     */
    boolean isAdmin(long userId);
    /**
     * Evaluates the restricted monitoring permission.
     * @param userId user identity
     * @return whether metrics may be read
     */
    boolean canReadMetrics(long userId);

    /**
     * Requires the current actor to administer the application.
     */
    void requireAdmin();

    /**
     * Requires management access to the selected shop.
     * @param shopId managed shop identity
     */
    void requireShop(long shopId);

}
