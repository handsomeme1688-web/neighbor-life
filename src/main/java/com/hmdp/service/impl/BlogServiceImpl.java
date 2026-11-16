package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    private static final String LOCK_BLOG_SQL = "SELECT id FROM tb_blog WHERE id=? FOR UPDATE";
    private static final String SHOP_EXISTS_SQL = "SELECT COUNT(*) FROM tb_shop WHERE id=?";


    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户 ctrl alt m 封装函数
        queryBlogUser(blog);
        // 3.查询blog的点赞数量
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2. 判断当前登录用户是否已经点赞
        blog.setIsLike(jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=? AND user_id=?",
                Long.class, blog.getId(), userId) > 0);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result likeBlog(Long id) {
        jdbc.queryForList(LOCK_BLOG_SQL, id);
        boolean liked = jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=? AND user_id=?",
                Long.class, id, UserHolder.getUser().getId()) > 0;
        return setLike(id, !liked);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result setLike(Long id, Boolean liked) {
        if (jdbc.queryForList(LOCK_BLOG_SQL, id).isEmpty()) {
            return Result.fail("笔记不存在");
        }
        Long userId = UserHolder.getUser().getId();
        if (Boolean.TRUE.equals(liked)) {
            jdbc.update("INSERT IGNORE INTO tb_blog_like(blog_id,user_id) VALUES (?,?)", id, userId);
        } else {
            jdbc.update("DELETE FROM tb_blog_like WHERE blog_id=? AND user_id=?", id, userId);
        }
        jdbc.update("UPDATE tb_blog SET liked=(SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=?) WHERE id=?", id, id);
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM tb_blog_like WHERE blog_id=? "
                + "ORDER BY create_time,user_id LIMIT 5", Long.class, id);
        if (ids.isEmpty()) { return Result.ok(Collections.emptyList()); }
        String idStr = StrUtil.join(",", ids);
        // 3. 根据用户id查询用户
        List<UserDTO> userDtos = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id,"+ idStr +")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDtos);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (jdbc.queryForObject(SHOP_EXISTS_SQL, Long.class, blog.getShopId()) == 0) {
            return Result.fail("商铺不存在");
        }
        blog.setId(null).setLiked(0).setComments(0).setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        jdbc.update("INSERT INTO tb_feed_delivery(blog_id,author_id,published_ms) VALUES (?,?,?)",
                blog.getId(), user.getId(), System.currentTimeMillis());
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 查询收件箱里所有的笔记，然后滚动分页
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱 ZREVRANGEBYSCORE key max min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4. 解析数据：blogId，minTime（时间戳），offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogId = tuple.getValue();
            ids.add(Long.valueOf(blogId));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            }else {
                minTime = time;
                os = 1;
            }

        }
        // 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                // 基于mysql的in 来查, 不能保证顺序
                .in("id", ids).last("ORDER BY FIELD(id,"+ idStr +")").list();
        for (Blog blog : blogs) {
            // 5. 查询blog有关的用户
            queryBlogUser(blog);
            // 6. 查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(minTime == max ? offset + os : os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
}
