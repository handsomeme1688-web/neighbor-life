package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Database-authoritative, idempotent follow relationships.
 * @author NeighborLife
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    private static final String SELF_FOLLOW_MESSAGE = "请选择其他用户关注";
    private static final String USER_NOT_FOUND_MESSAGE = "用户不存在";

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private IUserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result follow(Long followUserId, Boolean follow) {
        Long userId = UserHolder.getUser().getId();
        if (userId.equals(followUserId)) { return Result.fail(SELF_FOLLOW_MESSAGE); }
        if (userService.getById(followUserId) == null) { return Result.fail(USER_NOT_FOUND_MESSAGE); }
        if (Boolean.TRUE.equals(follow)) {
            jdbc.update("INSERT IGNORE INTO tb_user_follow(user_id,follow_user_id) VALUES (?,?)", userId, followUserId);
        } else {
            jdbc.update("DELETE FROM tb_user_follow WHERE user_id=? AND follow_user_id=?", userId, followUserId);
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        return Result.ok(jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_follow WHERE user_id=? AND follow_user_id=?",
                Long.class, UserHolder.getUser().getId(), followUserId) > 0);
    }

    @Override
    public Result followCommons(Long id) {
        List<Long> ids = jdbc.queryForList("SELECT a.follow_user_id FROM tb_user_follow a JOIN tb_user_follow b "
                + "ON a.follow_user_id=b.follow_user_id WHERE a.user_id=? AND b.user_id=? "
                + "ORDER BY a.follow_user_id LIMIT 100", Long.class, UserHolder.getUser().getId(), id);
        if (ids.isEmpty()) { return Result.ok(Collections.emptyList()); }
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
