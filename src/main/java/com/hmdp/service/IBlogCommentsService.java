package com.hmdp.service;

import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {
    /**
     * Publishes a comment or validated reply.
     * @param request comment content and target
     * @return new comment identity
     */
    com.hmdp.dto.Result publish(com.hmdp.dto.CommentCreateDTO request);
    /**
     * Lists a bounded page with removed content redacted.
     * @param blogId blog identity
     * @param beforeId exclusive comment cursor
     * @return comments
     */
    com.hmdp.dto.Result listComments(Long blogId, Long beforeId);
    /**
     * Removes or moderates a comment idempotently.
     * @param id comment identity
     * @param moderation whether administrator moderation is requested
     * @return operation result
     */
    com.hmdp.dto.Result removeComment(Long id, boolean moderation);
    /**
     * Sets the desired like state.
     * @param id comment identity
     * @param liked desired state
     * @return operation result
     */
    com.hmdp.dto.Result like(Long id, Boolean liked);
    /**
     * Reports a comment once per actor.
     * @param id comment identity
     * @param reason moderation reason
     * @return operation result
     */
    com.hmdp.dto.Result report(Long id, String reason);


}
