package com.hmdp.mq;

import com.hmdp.entity.SeckillInitializationRecord;
import com.hmdp.mapper.SeckillInitializationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Retries atomic activity publication while preserving live stock reservations.
 * @author NeighborLife
 */
@Slf4j
@Component
public class SeckillInitializationHandler {
    private static final long PARTIAL_INITIALIZATION = 2L;
    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>();

    static {
        INITIALIZE_SCRIPT.setLocation(new ClassPathResource("seckill_initialize.lua"));
        INITIALIZE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillInitializationMapper initializationMapper;

    public void initializeAfterCommit(SeckillInitializationRecord record) {
        try {
            initialize(record);
        } catch (RuntimeException exception) {
            log.error("秒杀活动初始化待重试, voucherId={}", record.getVoucherId(), exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.order.initialization-retry-ms:5000}")
    public void retryPendingInitializations() {
        for (SeckillInitializationRecord record : initializationMapper.findPending()) {
            initializeAfterCommit(record);
        }
    }

    private void initialize(SeckillInitializationRecord record) {
        String id = record.getVoucherId().toString();
        Long result = stringRedisTemplate.execute(INITIALIZE_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + id, SECKILL_BEGIN_KEY + id, SECKILL_END_KEY + id),
                record.getStock().toString(), record.getBeginSecond().toString(), record.getEndSecond().toString());
        if (result == null || result == PARTIAL_INITIALIZATION) {
            throw new IllegalStateException("秒杀活动缓存待初始化或需要对账");
        }
        initializationMapper.delete(record.getVoucherId());
    }
}
