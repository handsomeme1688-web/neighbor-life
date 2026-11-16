package com.hmdp.mq;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.entity.CacheInvalidationRecord;
import com.hmdp.mapper.CacheInvalidationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * Performs local cache invalidation immediately and repeats it through Kafka.
 * @author NeighborLife
 */
@Slf4j
@Component
public class ShopCacheInvalidationHandler {
    private static final String VOUCHER_MESSAGE_PREFIX = "VOUCHER:";


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private Cache<Long, Shop> shopLocalCache;
    @Resource
    private Cache<Long, com.hmdp.dto.VoucherDetailDTO> voucherLocalCache;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;


    @Resource
    private CacheInvalidationMapper cacheInvalidationMapper;

    @Value("${app.kafka.shop-cache-invalidation-topic}")
    private String invalidationTopic;

    @Value("${app.kafka.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    @Value("${app.cache.invalidation-delay-ms:500}")
    private long invalidationDelayMs;

    public void invalidateAfterCommit(Long shopId) {
        try {
            deleteCache(shopId);
        } catch (Exception e) {
            log.warn("直接删除商铺缓存失败，将由Kafka消息重试, shopId={}", shopId, e);
        }

    }

    @Scheduled(fixedDelayString = "${app.cache.outbox-relay-delay-ms:1000}")
    public void relayPendingInvalidations() {
        List<CacheInvalidationRecord> records = cacheInvalidationMapper.findPending();
        for (CacheInvalidationRecord record : records) {
            try {
                String shopId = record.getShopId().toString();
                String payload = "VOUCHER".equals(record.getResourceType()) ? VOUCHER_MESSAGE_PREFIX + shopId : shopId;
                kafkaTemplate.send(invalidationTopic, shopId, payload)
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                cacheInvalidationMapper.delete(record.getId());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                log.error("缓存失效消息投递失败，保留待投递记录, eventId={}", record.getId(), exception);
                return;
            }
        }
    }

    @KafkaListener(
            topics = "${app.kafka.shop-cache-invalidation-topic}",
            groupId = "hmdp-shop-cache-invalidator-${app.instance-id}",
            containerFactory = "shopCacheKafkaListenerContainerFactory"
    )
    public void consumeInvalidation(String shopIdText) {
        delaySecondInvalidation();
        if (shopIdText.startsWith(VOUCHER_MESSAGE_PREFIX)) {
            invalidateVoucher(Long.valueOf(shopIdText.substring(VOUCHER_MESSAGE_PREFIX.length())));
            return;
        }
        Long shopId = Long.valueOf(shopIdText);
        deleteCache(shopId);
        log.info("商铺缓存失效完成, shopId={}", shopId);
    }

    @KafkaListener(
            topics = "${app.kafka.shop-cache-invalidation-dlt-topic}",
            groupId = "hmdp-shop-cache-dlt-${app.instance-id}",
            containerFactory = "compensationKafkaListenerContainerFactory"
    )
    public void retryDeadLetterInvalidation(String shopIdText) {
        consumeInvalidation(shopIdText);
    }

    public void invalidateVoucherAfterCommit(Long id) {
        try { invalidateVoucher(id); }
        catch (RuntimeException exception) { log.warn("券缓存失效等待持久化事件重试, voucherId={}", id, exception); }
    }

    private void invalidateVoucher(Long id) {
        voucherLocalCache.invalidate(id);
        stringRedisTemplate.delete("cache:voucher:" + id);
        java.util.List<Integer> states = jdbc.queryForList("SELECT status FROM tb_voucher WHERE id=?", Integer.class, id);
        if (!states.isEmpty()) {
            stringRedisTemplate.opsForValue().set("seckill:enabled:" + id, states.get(0) == 1 ? "1" : "0");
        }
    }

    private void deleteCache(Long shopId) {
        shopLocalCache.invalidate(shopId);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
    }

    private void delaySecondInvalidation() {
        try {
            Thread.sleep(invalidationDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("缓存延迟失效任务被中断", e);
        }
    }
}
