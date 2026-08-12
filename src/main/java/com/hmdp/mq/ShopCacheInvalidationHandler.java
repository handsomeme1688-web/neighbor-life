package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * Performs local cache invalidation immediately and repeats it through Kafka.
 */
@Slf4j
@Component
public class ShopCacheInvalidationHandler {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.shop-cache-invalidation-topic}")
    private String invalidationTopic;

    @Value("${app.kafka.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    public void invalidateAfterCommit(Long shopId) {
        try {
            deleteCache(shopId);
        } catch (Exception e) {
            log.warn("直接删除商铺缓存失败，将由Kafka消息重试, shopId={}", shopId, e);
        }

        try {
            kafkaTemplate.send(invalidationTopic, shopId.toString(), shopId.toString())
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Cache entries have a physical TTL, which remains the last safety net.
            log.error("发送商铺缓存失效消息失败, shopId={}", shopId, e);
        }
    }

    @KafkaListener(
            topics = "${app.kafka.shop-cache-invalidation-topic}",
            groupId = "hmdp-shop-cache-invalidator",
            containerFactory = "shopCacheKafkaListenerContainerFactory"
    )
    public void consumeInvalidation(String shopIdText) {
        Long shopId = Long.valueOf(shopIdText);
        deleteCache(shopId);
        log.info("商铺缓存失效完成, shopId={}", shopId);
    }

    private void deleteCache(Long shopId) {
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
    }
}
