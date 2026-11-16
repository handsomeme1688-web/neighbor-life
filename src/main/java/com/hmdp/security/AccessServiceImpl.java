package com.hmdp.security;

import com.hmdp.exception.BusinessException;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;

/**
 * Evaluates current database roles and merchant ownership on every write.
 * @author NeighborLife
 */
@Service
public class AccessServiceImpl implements AccessService {
    @Resource
    private JdbcTemplate jdbc;
    @Value("${app.security.admin-user-ids:}")
    private String bootstrapAdminIds;

    public long userId() {
        if (UserHolder.getUser() == null) {
            throw new BusinessException("UNAUTHENTICATED", "请先登录", HttpStatus.UNAUTHORIZED);
        }
        return UserHolder.getUser().getId();
    }

    public boolean isAdmin(long userId) {
        boolean bootstrap = Arrays.stream(bootstrapAdminIds.split(",")).map(String::trim)
                .anyMatch(value -> value.equals(Long.toString(userId)));
        return bootstrap || jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role WHERE user_id=? AND role='ADMIN'",
                Long.class, userId) > 0;
    }

    @Override
    public boolean canReadMetrics(long userId) {
        return isAdmin(userId) || jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role WHERE user_id=? AND role='OBSERVER'",
                Long.class, userId) > 0;
    }

    public void requireAdmin() {
        if (!isAdmin(userId())) {
            throw new BusinessException("FORBIDDEN", "需要管理员权限", HttpStatus.FORBIDDEN);
        }
    }

    public void requireShop(long shopId) {
        long actor = userId();
        boolean merchant = jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role WHERE user_id=? AND role='MERCHANT'",
                Long.class, actor) > 0;
        boolean assigned = jdbc.queryForObject("SELECT COUNT(*) FROM tb_shop_member WHERE shop_id=? AND user_id=?",
                Long.class, shopId, actor) > 0;
        boolean allowed = isAdmin(actor) || merchant && assigned;
        if (!allowed) {
            throw new BusinessException("FORBIDDEN", "缺少该商铺的管理权限", HttpStatus.FORBIDDEN);
        }
    }
}
