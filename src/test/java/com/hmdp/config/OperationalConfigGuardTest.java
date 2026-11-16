package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies production configuration rejects simulated payment and invalid scan capacity.
 * @author NeighborLife
 */
class OperationalConfigGuardTest {
    @Test
    void rejectsSimulatedProductionChannels() {
        OperationalConfigGuard guard = guard();
        ReflectionTestUtils.setField(guard,"smsMode","mock");
        assertThrows(IllegalArgumentException.class,guard::afterSingletonsInstantiated);
    }
    @Test
    void acceptsProtectedProductionConfiguration() {
        assertDoesNotThrow(guard()::afterSingletonsInstantiated);
    }
    @Test
    void rejectsUnboundedScanBatch() {
        OperationalConfigGuard guard = guard();
        ReflectionTestUtils.setField(guard,"batch",Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,guard::afterSingletonsInstantiated);
    }
    private OperationalConfigGuard guard() {
        OperationalConfigGuard guard = new OperationalConfigGuard();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ReflectionTestUtils.setField(guard,"environment",environment);
        ReflectionTestUtils.setField(guard,"timeout",15L);
        ReflectionTestUtils.setField(guard,"batch",100);
        ReflectionTestUtils.setField(guard,"smsMode","aliyun");
        ReflectionTestUtils.setField(guard,"paymentMode","wechat");
        ReflectionTestUtils.setField(guard,"databaseUrl","jdbc:mysql://localhost/db?sslMode=VERIFY_IDENTITY");
        ReflectionTestUtils.setField(guard,"kafkaProtocol","SASL_SSL");
        ReflectionTestUtils.setField(guard,"redisTls",true);
        return guard;
    }
}
