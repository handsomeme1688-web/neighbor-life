package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.service.AdministrationService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

/**
 * Validated role and merchant access administration endpoints.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/ops")
@Validated
public class AdministrationController {
    @Resource
    private AdministrationService administration;
    @AdminOnly
    @PutMapping("/users/{id}/roles/{role}")
    public Result grantRole(@PathVariable("id") @Positive Long id,
            @PathVariable("role") @Pattern(regexp = "ADMIN|MERCHANT|OBSERVER") String role) { return administration.grantRole(id, role); }
    @AdminOnly
    @DeleteMapping("/users/{id}/roles/{role}")
    public Result revokeRole(@PathVariable("id") @Positive Long id,
            @PathVariable("role") @Pattern(regexp = "ADMIN|MERCHANT|OBSERVER") String role) { return administration.revokeRole(id, role); }
    @AdminOnly
    @PutMapping("/shops/{shopId}/members/{userId}")
    public Result assignShop(@PathVariable("shopId") @Positive Long shopId,
            @PathVariable("userId") @Positive Long userId) { return administration.assignShop(shopId, userId); }
    @AdminOnly
    @DeleteMapping("/shops/{shopId}/members/{userId}")
    public Result revokeShop(@PathVariable("shopId") @Positive Long shopId,
            @PathVariable("userId") @Positive Long userId) { return administration.revokeShop(shopId, userId); }
    @AdminOnly
    @GetMapping("/audit")
    public Result auditLog(@RequestParam(value = "beforeId", defaultValue = "9223372036854775807") @Positive Long beforeId) {
        return administration.auditLog(beforeId);
    }
}
