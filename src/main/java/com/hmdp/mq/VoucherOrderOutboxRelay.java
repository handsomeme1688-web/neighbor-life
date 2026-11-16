package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.domain.Range;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Transactional-outbox style relay: Redis Stream -> Kafka.
 *
 * At-least-once delivery is intentional. Kafka acknowledgement happens before
 * XACK; duplicates are handled by the consumer and the database unique index.
 * @author NeighborLife
 */
@Slf4j
@Component
public class VoucherOrderOutboxRelay {

    private static final String STREAM = "stream.orders";
    private static final String GROUP = "kafka-relay";
    private static final String GROUP_EXISTS_ERROR = "BUSYGROUP";
    private static final int BATCH_SIZE = 10;
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>();

    static {
        ACK_SCRIPT.setLocation(new ClassPathResource("outbox_ack.lua"));
        ACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.voucher-order-topic}")
    private String topic;

    @Value("${app.kafka.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    @Value("relay-${app.instance-id:1}")
    private String consumerName;
    @Value("${app.kafka.relay-claim-idle-ms:60000}")
    private long claimIdleMs;

    @Resource
    private ExecutorService orderRelayExecutor;
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        createConsumerGroupIfNecessary();
        orderRelayExecutor.execute(this::relayLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        orderRelayExecutor.shutdownNow();
    }

    private void createConsumerGroupIfNecessary() {
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.xGroupCreate(
                    STREAM.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0-0"), true));
        } catch (DataAccessException e) {
            if (e.getMessage() == null || !e.getMessage().contains(GROUP_EXISTS_ERROR)) {
                throw e;
            }
        }
    }

    private void relayLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                recoverOwnPendingMessages();
                recoverAbandonedMessages();
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(BATCH_SIZE).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                );
                publishAndAcknowledge(records);
            } catch (Exception e) {
                log.error("Redis Stream 转发 Kafka 失败，将继续重试", e);
                sleepQuietly(1000);
            }
        }
    }

    private void recoverOwnPendingMessages() {
        while (running && !Thread.currentThread().isInterrupted()) {
            List<MapRecord<String, Object, Object>> records;
            try {
                records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(BATCH_SIZE),
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

    private void recoverAbandonedMessages() {
        PendingMessages pending = stringRedisTemplate.opsForStream()
                .pending(STREAM, GROUP, Range.unbounded(), 100);
        if (pending == null) {
            return;
        }
        List<RecordId> abandoned = new ArrayList<>(BATCH_SIZE);
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().toMillis() >= claimIdleMs) {
                abandoned.add(message.getId());
            }
        }
        if (abandoned.isEmpty()) {
            return;
        }
        List<ByteRecord> claimed = stringRedisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.xClaim(STREAM.getBytes(StandardCharsets.UTF_8), GROUP, consumerName,
                        Duration.ofMillis(claimIdleMs), abandoned.toArray(new RecordId[abandoned.size()])));
        if (claimed == null) {
            return;
        }
        List<MapRecord<String, Object, Object>> records = new ArrayList<>(claimed.size());
        for (ByteRecord record : claimed) {
            Map<Object, Object> values = new LinkedHashMap<>(record.getValue().size());
            record.getValue().forEach((key, value) -> values.put(
                    new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8)));
            records.add(StreamRecords.newRecord().in(STREAM).ofMap(values).withId(record.getId()));
        }
        publishAndAcknowledge(records);
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
                stringRedisTemplate.execute(ACK_SCRIPT, Collections.singletonList(STREAM),
                        GROUP, record.getId().getValue());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("订单转发线程被中断", exception);
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

}
