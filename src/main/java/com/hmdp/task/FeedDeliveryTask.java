package com.hmdp.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Resumes bounded follower fanout after crashes using a durable user cursor.
 * @author NeighborLife
 */
@Component
public class FeedDeliveryTask {
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private StringRedisTemplate redis;

    @Scheduled(fixedDelayString = "${app.feed.delivery-scan-ms:1000}")
    public void deliver() {
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT * FROM tb_feed_delivery ORDER BY blog_id LIMIT 10");
        for (Map<String, Object> job : jobs) {
            Long blogId = ((Number) job.get("blog_id")).longValue();
            Long cursor = ((Number) job.get("last_user_id")).longValue();
            List<Long> recipients = jdbc.queryForList("SELECT user_id FROM tb_user_follow "
                    + "WHERE follow_user_id=? AND user_id>? ORDER BY user_id LIMIT 100", Long.class,
                    job.get("author_id"), cursor);
            if (recipients.isEmpty()) {
                jdbc.update("DELETE FROM tb_feed_delivery WHERE blog_id=?", blogId);
                continue;
            }
            for (Long recipient : recipients) {
                redis.opsForZSet().add("feed:" + recipient, blogId.toString(),
                        ((Number) job.get("published_ms")).doubleValue());
            }
            jdbc.update("UPDATE tb_feed_delivery SET last_user_id=GREATEST(last_user_id,?) WHERE blog_id=?",
                    recipients.get(recipients.size() - 1), blogId);
        }
    }
}
