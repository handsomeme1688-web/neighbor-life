package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Transactional-outbox style relay: Redis Stream -> Kafka.
 *
 * At-least-once delivery is intentional. Kafka acknowledgement happens before
 * XACK; duplicates are handled by the consumer and the database unique index.
 */
@Slf4j
@Component
public class VoucherOrderOutboxRelay {

    private static final String STREAM = "stream.orders";
    private static final String GROUP = "kafka-relay";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.voucher-order-topic}")
    private String topic;

    @Value("${app.kafka.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    private final String consumerName = "relay-" + runtimeId() + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "voucher-order-kafka-relay");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        createConsumerGroupIfNecessary();
        relayExecutor.submit(this::relayLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        relayExecutor.shutdownNow();
    }

    private void createConsumerGroupIfNecessary() {
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (DataAccessException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void relayLoop() {
        recoverOwnPendingMessages();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );
                publishAndAcknowledge(records);
            } catch (Exception e) {
                log.error("Redis Stream 转发 Kafka 失败，将继续重试", e);
                sleepQuietly(1000);
                recoverOwnPendingMessages();
            }
        }
    }

    private void recoverOwnPendingMessages() {
        while (running && !Thread.currentThread().isInterrupted()) {
            List<MapRecord<String, Object, Object>> records;
            try {
                records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(STREAM, ReadOffset.from("0"))
                );
            } catch (Exception e) {
                log.warn("读取 Redis Stream pending-list 失败", e);
                return;
            }
            if (records == null || records.isEmpty()) {
                return;
            }
            publishAndAcknowledge(records);
        }
    }

    private void publishAndAcknowledge(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            VoucherOrderEvent event = toEvent(record.getValue());
            try {
                kafkaTemplate.send(topic, event.getOrderId().toString(), JSONUtil.toJsonStr(event))
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                stringRedisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            } catch (Exception e) {
                throw new IllegalStateException("Kafka 未确认订单消息, orderId=" + event.getOrderId(), e);
            }
        }
    }

    private VoucherOrderEvent toEvent(Map<Object, Object> values) {
        return new VoucherOrderEvent(
                Long.valueOf(String.valueOf(values.get("id"))),
                Long.valueOf(String.valueOf(values.get("userId"))),
                Long.valueOf(String.valueOf(values.get("voucherId")))
        );
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String runtimeId() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        return runtimeName.replaceAll("[^a-zA-Z0-9_-]", "-");
    }
}
