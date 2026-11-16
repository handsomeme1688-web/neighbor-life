package com.hmdp.ops;

import cn.hutool.json.JSONUtil;
import com.hmdp.mq.VoucherOrderEvent;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Persists poison messages before acknowledgement and validates administrator replays.
 * @author NeighborLife
 */
@Service
public class MessageQuarantineServiceImpl implements MessageQuarantineService {
    private static final String PENDING_MESSAGE_SQL = "SELECT COUNT(*) FROM tb_message_quarantine WHERE id=? AND state='PENDING'";
    private static final String HEX_BYTE_FORMAT = "%02x";
    private static final int HEX_BYTE_MASK = 0xff;

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private KafkaTemplate<String, String> kafka;
    @Resource
    private AccessService access;
    @Resource
    private AuditService audit;
    @Value("${app.kafka.voucher-order-topic}")
    private String topic;

    public void quarantine(String payload) {
        jdbc.update("INSERT INTO tb_message_quarantine(payload_hash,payload,reason) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE payload_hash=payload_hash", digest(payload), payload, "INVALID_ORDER_MESSAGE");
    }

    public void replay(Long id, VoucherOrderEvent event) {
        access.requireAdmin();
        event.toOrder();
        String reservation = (String) redis.opsForHash().get("seckill:reservation:" + event.getVoucherId(),
                event.getUserId().toString());
        if (!event.getOrderId().toString().equals(reservation)) {
            throw new IllegalArgumentException("重放消息需要匹配当前预扣订单归属");
        }
        if (jdbc.queryForObject(PENDING_MESSAGE_SQL,
                Long.class, id) == 0) {
            throw new IllegalArgumentException("待重放消息不存在");
        }
        try {
            kafka.send(topic, event.getOrderId().toString(), JSONUtil.toJsonStr(event)).get(5L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("消息重放被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("消息重放等待重试", exception);
        }
        jdbc.update("UPDATE tb_message_quarantine SET state='REPLAYED' WHERE id=?", id);
        audit.record("MESSAGE_REPLAY", id, null, event);
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte element : hash) { result.append(String.format(java.util.Locale.ROOT, HEX_BYTE_FORMAT, element & HEX_BYTE_MASK)); }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
