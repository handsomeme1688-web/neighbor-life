package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * Sets a desired like state idempotently.
     * @param id blog identity
     * @param liked desired state
     * @return operation result
     */
    Result setLike(Long id, Boolean liked);


    /**
     * 查询笔记详情及当前用户点赞状态。
     * @param id 业务主键
     * @return 处理结果
     */
    Result queryBlogById(Long id);

    /**
     * 分页查询热门笔记。
     * @param current 从1开始的页码
     * @return 处理结果
     */
    Result queryHotBlog(Integer current);

    /**
     * 切换当前用户对笔记的点赞状态。
     * @param id 业务主键
     * @return 处理结果
     */
    Result likeBlog(Long id);

    /**
     * 查询笔记最早点赞的用户。
     * @param id 业务主键
     * @return 处理结果
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存笔记并推送到粉丝收件箱。
     * @param blog 笔记内容
     * @return 处理结果
     */
    Result saveBlog(Blog blog);

    /**
     * 按时间游标查询关注用户发布的笔记。
     * @param max 查询时间上界
     * @param offset 同时间游标偏移量
     * @return 处理结果
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
