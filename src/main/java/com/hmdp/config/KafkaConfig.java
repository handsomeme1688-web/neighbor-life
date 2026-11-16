package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import com.hmdp.mq.ConfirmedDeadLetterRecoverer;

/**
 * KafkaConfig component.
 *
 * @author NeighborLife
 */
@Configuration
public class KafkaConfig {
    @Value("${app.kafka.replication-factor:1}")
    private short replicationFactor;
    @Value("${app.kafka.min-in-sync-replicas:1}")
    private int minInSyncReplicas;


    @Bean
    public NewTopic voucherOrderTopic(
            @Value("${app.kafka.voucher-order-topic}") String orderTopic) {
        return TopicBuilder.name(orderTopic).partitions(3).replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minInSyncReplicas)).build();
    }

    @Bean
    public NewTopic voucherOrderDeadLetterTopic(
            @Value("${app.kafka.voucher-order-dlt-topic}") String deadLetterTopic) {
        return TopicBuilder.name(deadLetterTopic).partitions(3).replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minInSyncReplicas))
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic shopCacheInvalidationTopic(
            @Value("${app.kafka.shop-cache-invalidation-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minInSyncReplicas)).build();
    }

    @Bean
    public NewTopic shopCacheInvalidationDeadLetterTopic(
            @Value("${app.kafka.shop-cache-invalidation-dlt-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(replicationFactor)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minInSyncReplicas))
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.kafka.voucher-order-dlt-topic}") String deadLetterTopic,
            @Value("${app.kafka.retry-interval-ms:1000}") long retryInterval,
            @Value("${app.kafka.retry-count:2}") long retryCount) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new ConfirmedDeadLetterRecoverer(
                kafkaTemplate, deadLetterTopic, 5L);
        factory.setErrorHandler(new SeekToCurrentErrorHandler(
                recoverer,
                new FixedBackOff(retryInterval, retryCount)
        ));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> compensationKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setErrorHandler(new SeekToCurrentErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> shopCacheKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${app.kafka.shop-cache-invalidation-dlt-topic}") String deadLetterTopic,
            @Value("${app.kafka.retry-interval-ms:1000}") long retryInterval,
            @Value("${app.kafka.retry-count:2}") long retryCount) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        DeadLetterPublishingRecoverer recoverer = new ConfirmedDeadLetterRecoverer(
                kafkaTemplate, deadLetterTopic, 5L);
        factory.setErrorHandler(new SeekToCurrentErrorHandler(
                recoverer,
                new FixedBackOff(retryInterval, retryCount)
        ));
        return factory;
    }
}
