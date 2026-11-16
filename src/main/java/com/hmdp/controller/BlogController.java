package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.annotation.SlidingWindowRateLimit;
import com.hmdp.dto.Result;
import com.hmdp.dto.BlogCreateDTO;
import javax.validation.Valid;
import javax.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@Validated
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@Valid @RequestBody BlogCreateDTO request) {
        Blog blog = cn.hutool.core.bean.BeanUtil.copyProperties(request, Blog.class);
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") @Positive Long id) {

        return blogService.likeBlog(id);
    }

    @PutMapping("/{id}/like")
    public Result setLike(@PathVariable("id") @Positive Long id, @RequestParam("liked") Boolean liked) {
        return blogService.setLike(id, liked);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") @Min(1) @Max(10000) Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    @SlidingWindowRateLimit(
            key = "blog:hot", windowSeconds = 1,
            globalLimit = 300, ipLimit = 30)
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") @Min(1) @Max(10000) Integer current) {
        return blogService.queryHotBlog(current);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") @Positive Long id) {
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") @Positive Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") @Min(1) @Max(10000) Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") @PositiveOrZero Long max,
            @RequestParam(value = "offset", defaultValue = "0") @Min(0) @Max(100000) Integer offset)
        {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
