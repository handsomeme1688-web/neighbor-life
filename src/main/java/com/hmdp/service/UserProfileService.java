package com.hmdp.service;

/**
 * Authenticated profile operations.
 * @author NeighborLife
 */
public interface UserProfileService {
    /**
     * Returns the current user profile and live relationship counts.
     * @return operation result
     */
    com.hmdp.dto.Result profile();
    /**
     * Updates whitelisted fields of the current user profile.
     * @param request operation input
     * @return operation result
     */
    com.hmdp.dto.Result update(com.hmdp.dto.ProfileUpdateDTO request);
    /**
     * Returns the current role and shop permissions.
     * @return operation result
     */
    com.hmdp.dto.Result permissions();
}
