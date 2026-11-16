package com.hmdp.mq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.util.concurrent.TimeUnit;

/**
 * Keeps the source offset uncommitted until the DLT broker acknowledges delivery.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
public class ConfirmedDeadLetterRecoverer extends DeadLetterPublishingRecoverer {

    private final long timeoutSeconds;

    public ConfirmedDeadLetterRecoverer(KafkaOperations<Object, Object> template,
            String topic, long timeoutSeconds) {
        super(template, (ConsumerRecord<?, ?> record, Exception exception) ->
                new TopicPartition(topic, record.partition()));
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    protected void publish(ProducerRecord<Object, Object> record, KafkaOperations<Object, Object> template) {
        try {
            template.send(record).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("死信投递被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("死信投递未获确认，保留源消息重试", exception);
        }
    }
}
