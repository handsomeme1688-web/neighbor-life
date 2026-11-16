package com.hmdp.service.impl;

import com.hmdp.dto.ProfileUpdateDTO;
import com.hmdp.dto.Result;
import com.hmdp.security.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Own-profile updates with read-only loyalty values and live relationship counts.
 * @author NeighborLife
 */
@org.springframework.stereotype.Service
public class UserProfileServiceImpl implements com.hmdp.service.UserProfileService {
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private AccessService access;

    public Result profile() {
        long userId = access.userId();
        Map<String, Object> profile = jdbc.queryForMap("SELECT id,nick_name,icon FROM tb_user WHERE id=?", userId);
        List<Map<String, Object>> details = jdbc.queryForList("SELECT city,introduce,birthday,CAST(gender AS UNSIGNED) AS gender,"
                + "credits,CAST(level AS UNSIGNED) AS level FROM tb_user_info WHERE user_id=?", userId);
        if (!details.isEmpty()) { profile.putAll(details.get(0)); }
        profile.put("fans", jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_follow WHERE follow_user_id=?", Long.class, userId));
        profile.put("followee", jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_follow WHERE user_id=?", Long.class, userId));
        return Result.ok(profile);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result update(ProfileUpdateDTO request) {
        long userId = access.userId();
        jdbc.update("UPDATE tb_user SET nick_name=COALESCE(?,nick_name),icon=COALESCE(?,icon) WHERE id=?",
                request.getNickName(), request.getIcon(), userId);
        jdbc.update("INSERT IGNORE INTO tb_user_info(user_id) VALUES (?)", userId);
        jdbc.update("UPDATE tb_user_info SET city=COALESCE(?,city),introduce=COALESCE(?,introduce),"
                + "gender=COALESCE(?,gender),birthday=COALESCE(?,birthday) WHERE user_id=?",
                request.getCity(), request.getIntroduce(), request.getGender(), request.getBirthday(), userId);
        return profile();
    }

    public Result permissions() {
        long userId = access.userId();
        Map<String, Object> result = new java.util.LinkedHashMap<>(3);
        result.put("admin", access.isAdmin(userId));
        result.put("roles", jdbc.queryForList("SELECT role FROM tb_user_role WHERE user_id=? ORDER BY role", String.class, userId));
        result.put("shopIds", jdbc.queryForList("SELECT shop_id FROM tb_shop_member WHERE user_id=? ORDER BY shop_id LIMIT 1000", Long.class, userId));
        return Result.ok(result);
    }
}
