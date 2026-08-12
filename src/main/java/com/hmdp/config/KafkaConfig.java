package com.hmdp.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
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

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic voucherOrderTopic(
            @Value("${app.kafka.voucher-order-topic}") String orderTopic) {
        return TopicBuilder.name(orderTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic voucherOrderDeadLetterTopic(
            @Value("${app.kafka.voucher-order-dlt-topic}") String deadLetterTopic) {
        return TopicBuilder.name(deadLetterTopic).partitions(3).replicas(1)
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

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) ->
                        new TopicPartition(deadLetterTopic, record.partition())
        );
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
        return factory;
    }
}
