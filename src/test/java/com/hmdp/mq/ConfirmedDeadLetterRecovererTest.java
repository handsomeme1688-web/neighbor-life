package com.hmdp.mq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ensures a failed DLT publish keeps the source record eligible for retry.
 * @author NeighborLife
 */
class ConfirmedDeadLetterRecovererTest {
    @Test
    void propagatesUnacknowledgedDeadLetterPublish() {
        KafkaOperations<Object, Object> kafka = mock(KafkaOperations.class);
        SettableListenableFuture<SendResult<Object, Object>> future = new SettableListenableFuture<>();
        future.setException(new IllegalStateException("broker offline"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(future);
        ConfirmedDeadLetterRecoverer recoverer = new ConfirmedDeadLetterRecoverer(kafka, "test.DLT", 1L);
        ConsumerRecord<String, String> source = new ConsumerRecord<>("test", 0, 0L, "key", "payload");
        assertThrows(IllegalStateException.class, () -> recoverer.accept(source, new IllegalStateException("failed")));
    }
}
