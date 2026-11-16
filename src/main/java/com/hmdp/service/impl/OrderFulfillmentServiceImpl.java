package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.exception.BusinessException;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import com.hmdp.service.IOrderFulfillmentService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Atomic merchant redemption using an expiring hashed buyer credential.
 * @author NeighborLife
 */
@Service
public class OrderFulfillmentServiceImpl implements IOrderFulfillmentService {
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_STATUS = "status";
    private static final String PAID_ORDER_REQUIRED = "请使用已支付的未核销订单";
    private static final String ORDER_NOT_REDEEMABLE = "订单当前状态不支持核销";
    private static final String INVALID_REDEMPTION_CODE = "INVALID_REDEMPTION_CODE";
    private static final String INVALID_PROOF_MESSAGE = "核销凭据无效或已过期";
    private static final String ORDER_MISSING_MESSAGE = "订单不存在";

    private static final int PAID = 2;
    private static final int USED = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private AccessService access;
    @Resource
    private AuditService audit;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result issue(Long orderId) {
        Map<String, Object> order = order(orderId);
        if (((Number) order.get(FIELD_USER_ID)).longValue() != access.userId()) {
            throw new BusinessException("ORDER_NOT_FOUND", ORDER_MISSING_MESSAGE, HttpStatus.NOT_FOUND);
        }
        if (((Number) order.get(FIELD_STATUS)).intValue() != PAID) { throw new IllegalArgumentException(PAID_ORDER_REQUIRED); }
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        jdbc.update("INSERT INTO tb_order_redemption(order_id,code_hash,expire_time) VALUES (?,?,DATE_ADD(NOW(),INTERVAL 5 MINUTE)) "
                + "ON DUPLICATE KEY UPDATE code_hash=VALUES(code_hash),expire_time=VALUES(expire_time)", orderId, digest(token));
        Map<String, Object> result = new LinkedHashMap<>(3);
        result.put("orderId", orderId);
        result.put("code", token);
        result.put("expiresInSeconds", 300);
        return Result.ok(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result redeem(Long orderId, String code) {
        Map<String, Object> order = order(orderId);
        access.requireShop(((Number) order.get("shop_id")).longValue());
        int state = ((Number) order.get(FIELD_STATUS)).intValue();
        if (state == USED) { return Result.ok(); }
        if (state != PAID) { throw new IllegalArgumentException(ORDER_NOT_REDEEMABLE); }
        List<String> valid = jdbc.queryForList("SELECT code_hash FROM tb_order_redemption WHERE order_id=? "
                + "AND expire_time>NOW() FOR UPDATE", String.class, orderId);
        boolean proof = !valid.isEmpty() && MessageDigest.isEqual(digest(code).getBytes(StandardCharsets.UTF_8),
                valid.get(0).getBytes(StandardCharsets.UTF_8));
        if (!proof) { throw new BusinessException(INVALID_REDEMPTION_CODE, INVALID_PROOF_MESSAGE, HttpStatus.FORBIDDEN); }
        jdbc.update("UPDATE tb_voucher_order SET status=?,use_time=NOW() WHERE id=? AND status=?", USED, orderId, PAID);
        jdbc.update("DELETE FROM tb_order_redemption WHERE order_id=?", orderId);
        audit.record("ORDER_REDEEM", orderId, PAID, USED);
        return Result.ok();
    }

    private Map<String, Object> order(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT o.user_id,CAST(o.status AS UNSIGNED) AS status,v.shop_id "
                + "FROM tb_voucher_order o JOIN tb_voucher v ON v.id=o.voucher_id WHERE o.id=? FOR UPDATE", id);
        if (rows.isEmpty()) { throw new IllegalArgumentException(ORDER_MISSING_MESSAGE); }
        return rows.get(0);
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256不可用", exception); }
    }
}
