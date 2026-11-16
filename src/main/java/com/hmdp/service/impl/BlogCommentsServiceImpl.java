package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.CommentCreateDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import com.hmdp.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * Transactional comments, reply validation, idempotent likes and moderation.
 * @author NeighborLife
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {
    private static final String LOCK_BLOG_SQL = "SELECT id FROM tb_blog WHERE id=? FOR UPDATE";
    private static final String THREAD_REMOVED_MESSAGE = "评论主题已移除";
    private static final String COMMENT_UNAVAILABLE_MESSAGE = "评论不存在或已隐藏";
    private static final String COMMENT_REMOVED_MESSAGE = "评论已移除";

    private static final int NORMAL = 0;
    private static final int REPORTED = 1;
    private static final int HIDDEN = 2;
    private static final int DELETED = 3;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private AccessService access;
    @Resource
    private AuditService audit;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result publish(CommentCreateDTO request) {
        if (jdbc.queryForList(LOCK_BLOG_SQL, request.getBlogId()).isEmpty()) {
            throw new IllegalArgumentException("笔记不存在");
        }
        long root = 0L;
        long answer = request.getAnswerId() == null ? 0L : request.getAnswerId();
        if (answer > 0) {
            BlogComments parent = getById(answer);
            if (parent == null || !parent.getBlogId().equals(request.getBlogId()) || parent.getStatus() >= HIDDEN) {
                throw new IllegalArgumentException("回复目标已移除或属于其他笔记");
            }
            root = parent.getParentId() == 0L ? parent.getId() : parent.getParentId();
            BlogComments thread = getById(root);
            if (thread == null || thread.getStatus() >= HIDDEN) { throw new IllegalArgumentException(THREAD_REMOVED_MESSAGE); }
        }
        BlogComments comment = new BlogComments().setUserId(access.userId()).setBlogId(request.getBlogId())
                .setParentId(root).setAnswerId(answer).setContent(request.getContent()).setLiked(0).setStatus(NORMAL);
        save(comment);
        refreshCount(request.getBlogId());
        return Result.ok(comment.getId());
    }

    @Override
    public Result listComments(Long blogId, Long beforeId) {
        List<BlogComments> comments = query().eq("blog_id", blogId).lt("id", beforeId)
                .orderByDesc("id").last("LIMIT 20").list();
        for (BlogComments comment : comments) {
            if (comment.getStatus() >= HIDDEN) { comment.setContent(null); }
        }
        return Result.ok(comments);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result removeComment(Long id, boolean moderation) {
        BlogComments comment = getById(id);
        if (comment == null) { return Result.ok(); }
        long actor = access.userId();
        if (moderation) { access.requireAdmin(); }
        else if (!comment.getUserId().equals(actor) && !access.isAdmin(actor)) {
            throw new BusinessException("FORBIDDEN", "缺少该评论的管理权限", HttpStatus.FORBIDDEN);
        }
        jdbc.queryForList(LOCK_BLOG_SQL, comment.getBlogId());
        jdbc.update("UPDATE tb_blog_comments SET status=? WHERE id=? AND status IN (0,1)", moderation ? HIDDEN : DELETED, id);
        refreshCount(comment.getBlogId());
        audit.record(moderation ? "COMMENT_HIDE" : "COMMENT_DELETE", id, null, Collections.singletonMap("blogId", comment.getBlogId()));
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result like(Long id, Boolean liked) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM tb_blog_comments WHERE id=? AND status IN (0,1) FOR UPDATE", Long.class, id);
        if (existing.isEmpty()) { throw new IllegalArgumentException(COMMENT_UNAVAILABLE_MESSAGE); }
        if (Boolean.TRUE.equals(liked)) {
            jdbc.update("INSERT IGNORE INTO tb_comment_like(comment_id,user_id) VALUES (?,?)", id, access.userId());
        } else {
            jdbc.update("DELETE FROM tb_comment_like WHERE comment_id=? AND user_id=?", id, access.userId());
        }
        jdbc.update("UPDATE tb_blog_comments SET liked=(SELECT COUNT(*) FROM tb_comment_like WHERE comment_id=?) WHERE id=?", id, id);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result report(Long id, String reason) {
        BlogComments comment = getById(id);
        if (comment == null || comment.getStatus() >= HIDDEN) { throw new IllegalArgumentException(COMMENT_REMOVED_MESSAGE); }
        jdbc.update("INSERT IGNORE INTO tb_comment_report(comment_id,user_id,reason) VALUES (?,?,?)", id, access.userId(), reason);
        jdbc.update("UPDATE tb_blog_comments SET status=? WHERE id=? AND status=?", REPORTED, id, NORMAL);
        return Result.ok();
    }

    private void refreshCount(Long blogId) {
        jdbc.update("UPDATE tb_blog SET comments=(SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=? AND status IN (0,1)) WHERE id=?", blogId, blogId);
    }
}
