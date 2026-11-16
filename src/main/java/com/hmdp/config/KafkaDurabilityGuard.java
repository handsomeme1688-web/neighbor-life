package com.hmdp.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Verifies existing production topics actually satisfy configured durability.
 * @author NeighborLife
 */
@Component
@Profile("prod")
public class KafkaDurabilityGuard implements ApplicationRunner {
    private static final int REQUIRED_REPLICAS = 3;
    private static final int REQUIRED_ISR = 2;
    @Resource
    private KafkaProperties properties;
    @Value("${app.kafka.voucher-order-topic}")
    private String orders;
    @Value("${app.kafka.voucher-order-dlt-topic}")
    private String deadLetters;
    @Value("${app.kafka.shop-cache-invalidation-topic}")
    private String invalidations;
    @Value("${app.kafka.shop-cache-invalidation-dlt-topic}")
    private String invalidationDeadLetters;

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        AdminClient client = AdminClient.create(properties.buildAdminProperties());
        try {
            Map<String, TopicDescription> topics = client.describeTopics(
                    Arrays.asList(orders, deadLetters, invalidations, invalidationDeadLetters))
                    .all().get(10L, TimeUnit.SECONDS);
            for (TopicDescription topic : topics.values()) {
                boolean ready = topic.partitions().stream().allMatch(partition ->
                        partition.replicas().size() >= REQUIRED_REPLICAS && partition.isr().size() >= REQUIRED_ISR);
                if (!ready) { throw new IllegalStateException("Kafka主题副本尚未满足生产要求: " + topic.name()); }
            }
        } finally {
            client.close(Duration.ofSeconds(3L));
        }
    }
}
