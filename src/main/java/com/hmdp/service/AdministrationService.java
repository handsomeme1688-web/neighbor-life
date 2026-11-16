package com.hmdp.service;

/**
 * Audited role and merchant access administration.
 * @author NeighborLife
 */
public interface AdministrationService {
    /**
     * Grants an application role to an existing user.
     * @param id operation input
     * @param role operation input
     * @return operation result
     */
    com.hmdp.dto.Result grantRole(Long id, String role);
    /**
     * Revokes an application role.
     * @param id operation input
     * @param role operation input
     * @return operation result
     */
    com.hmdp.dto.Result revokeRole(Long id, String role);
    /**
     * Grants merchant access to an existing shop.
     * @param shopId operation input
     * @param userId operation input
     * @return operation result
     */
    com.hmdp.dto.Result assignShop(Long shopId, Long userId);
    /**
     * Revokes merchant access to a shop.
     * @param shopId operation input
     * @param userId operation input
     * @return operation result
     */
    com.hmdp.dto.Result revokeShop(Long shopId, Long userId);
    /**
     * Reads a bounded audit page.
     * @param beforeId operation input
     * @return operation result
     */
    com.hmdp.dto.Result auditLog(Long beforeId);
}
