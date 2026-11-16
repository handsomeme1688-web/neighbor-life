package com.hmdp.service.impl;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

/**
 * Audited role and merchant-scope administration.
 * @author NeighborLife
 */
@org.springframework.stereotype.Service
public class AdministrationServiceImpl implements com.hmdp.service.AdministrationService {
    private static final String SHOP_EXISTS_SQL = "SELECT COUNT(*) FROM tb_shop WHERE id=?";
    private static final String USER_EXISTS_SQL = "SELECT COUNT(*) FROM tb_user WHERE id=?";

    @Resource
    private AccessService access;
    @Resource
    private AuditService audit;
    @Resource
    private JdbcTemplate jdbc;

    @Transactional(rollbackFor = Exception.class)
    public Result grantRole(Long id,
            String role) {
        access.requireAdmin();
        requireUser(id);
        jdbc.update("INSERT IGNORE INTO tb_user_role(user_id,role) VALUES (?,?)", id, role);
        audit.record("ROLE_GRANT", id, null, role);
        return Result.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public Result revokeRole(Long id,
            String role) {
        access.requireAdmin();
        jdbc.update("DELETE FROM tb_user_role WHERE user_id=? AND role=?", id, role);
        audit.record("ROLE_REVOKE", id, role, null);
        return Result.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public Result assignShop(Long shopId,
            Long userId) {
        access.requireAdmin();
        requireUser(userId);
        if (jdbc.queryForObject(SHOP_EXISTS_SQL, Long.class, shopId) == 0) {
            throw new IllegalArgumentException("商铺不存在");
        }
        jdbc.update("INSERT IGNORE INTO tb_user_role(user_id,role) VALUES (?,'MERCHANT')", userId);
        jdbc.update("INSERT IGNORE INTO tb_shop_member(shop_id,user_id) VALUES (?,?)", shopId, userId);
        audit.record("SHOP_MEMBER_GRANT", shopId, null, userId);
        return Result.ok();
    }

    @Transactional(rollbackFor = Exception.class)
    public Result revokeShop(Long shopId,
            Long userId) {
        access.requireAdmin();
        jdbc.update("DELETE FROM tb_shop_member WHERE shop_id=? AND user_id=?", shopId, userId);
        audit.record("SHOP_MEMBER_REVOKE", shopId, userId, null);
        return Result.ok();
    }

    public Result auditLog(Long beforeId) {
        access.requireAdmin();
        return Result.ok(jdbc.queryForList("SELECT * FROM tb_audit_log WHERE id<? ORDER BY id DESC LIMIT 100", beforeId));
    }

    private void requireUser(Long id) {
        if (jdbc.queryForObject(USER_EXISTS_SQL, Long.class, id) == 0) {
            throw new IllegalArgumentException("用户不存在");
        }
    }
}
