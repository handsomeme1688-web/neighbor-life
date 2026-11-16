package com.hmdp.mq;

import com.hmdp.entity.CacheInvalidationRecord;
import com.hmdp.mapper.CacheInvalidationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Ensures cache invalidations survive broker failures.
 * @author NeighborLife
 */
class ShopCacheInvalidationHandlerTest {
    private ShopCacheInvalidationHandler handler;
    private CacheInvalidationMapper mapper;
    private KafkaTemplate<String, String> kafka;
    private SettableListenableFuture<SendResult<String, String>> delivery;

    @BeforeEach
    void setUp() {
        handler = new ShopCacheInvalidationHandler();
        mapper = mock(CacheInvalidationMapper.class);
        kafka = mock(KafkaTemplate.class);
        ReflectionTestUtils.setField(handler, "cacheInvalidationMapper", mapper);
        ReflectionTestUtils.setField(handler, "kafkaTemplate", kafka);
        ReflectionTestUtils.setField(handler, "invalidationTopic", "test.cache");
        ReflectionTestUtils.setField(handler, "sendTimeoutSeconds", 1L);
        CacheInvalidationRecord event = new CacheInvalidationRecord();
        event.setId(1L);
        event.setShopId(2L);
        when(mapper.findPending()).thenReturn(Collections.singletonList(event));
        delivery = new SettableListenableFuture<>();
        when(kafka.send("test.cache", "2", "2")).thenReturn(delivery);
    }

    @Test
    void retainsOutboxUntilBrokerAcknowledges() {
        delivery.setException(new IllegalStateException("broker offline"));
        handler.relayPendingInvalidations();
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    void removesOutboxAfterBrokerAcknowledges() {
        delivery.set(null);
        handler.relayPendingInvalidations();
        verify(mapper).delete(1L);
    }
}
