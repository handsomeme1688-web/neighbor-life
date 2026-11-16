package com.hmdp.sms;

import com.hmdp.exception.RateLimitExceededException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Issues rate-limited one-time codes and tracks delivery receipts.
 * @author NeighborLife
 */
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {
    private static final long CODE_COOLDOWN_SECONDS = 60L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_BOUND = 1_000_000;
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();
    static {
        ISSUE_SCRIPT.setLocation(new ClassPathResource("issue_login_code.lua"));
        ISSUE_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setLocation(new ClassPathResource("consume_login_code.lua"));
        CONSUME_SCRIPT.setResultType(Long.class);
        RELEASE_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private SmsSender sender;
    @Resource
    private JdbcTemplate jdbc;

    public void issue(String phone) {
        String cooldownKey = "login:cooldown:" + phone;
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, token, CODE_COOLDOWN_SECONDS, TimeUnit.SECONDS))) {
            throw new RateLimitExceededException("请在60秒后重新获取验证码");
        }
        String codeKey = "login:code:" + phone;
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(CODE_BOUND));
        try {
            Long stored = redis.execute(ISSUE_SCRIPT, Arrays.asList(codeKey, codeKey + ":attempts"), code, "120");
            if (!Long.valueOf(1L).equals(stored)) {
                throw new IllegalStateException("验证码写入暂未完成");
            }
            String bizId = sender.send(phone, code);
            jdbc.update("INSERT INTO tb_sms_delivery(id,phone,biz_id,send_date) VALUES (?,?,?,?)",
                    token, phone, bizId, LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE));
        } catch (RuntimeException exception) {
            redis.execute(RELEASE_SCRIPT, Collections.singletonList(codeKey), code);
            redis.execute(RELEASE_SCRIPT, Collections.singletonList(cooldownKey), token);
            throw exception;
        }
    }

    public boolean consume(String phone, String code) {
        String key = "login:code:" + phone;
        Long result = redis.execute(CONSUME_SCRIPT, Arrays.asList(key, key + ":attempts"), code);
        return Long.valueOf(1L).equals(result);
    }

    @Scheduled(fixedDelayString = "${app.sms.receipt-scan-ms:30000}")
    public void pollReceipts() {
        List<Map<String, Object>> pending = jdbc.queryForList("SELECT id,phone,biz_id,send_date FROM tb_sms_delivery "
                + "WHERE state='PENDING' AND create_time > DATE_SUB(NOW(), INTERVAL 1 DAY) LIMIT 100");
        for (Map<String, Object> row : pending) {
            try {
                String state = sender.receipt(row.get("phone").toString(), row.get("biz_id").toString(),
                        row.get("send_date").toString());
                jdbc.update("UPDATE tb_sms_delivery SET state=? WHERE id=?", state, row.get("id"));
            } catch (RuntimeException exception) {
                jdbc.update("UPDATE tb_sms_delivery SET last_error='RECEIPT_RETRY' WHERE id=?", row.get("id"));
            }
        }
        jdbc.update("UPDATE tb_sms_delivery SET state='UNKNOWN' WHERE state='PENDING' "
                + "AND create_time <= DATE_SUB(NOW(), INTERVAL 1 DAY)");
        jdbc.update("DELETE FROM tb_sms_delivery WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY)");
    }
}
