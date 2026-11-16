package com.hmdp.service;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import javax.annotation.Resource;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * MySQL transaction and state-race regression tests using isolated fixture IDs.
 * @author NeighborLife
 */
@SpringBootTest(classes = OrderReliabilityIntegrationTest.TestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_INTEGRATION", matches = "true")
public class OrderReliabilityIntegrationTest {
    private static final long ID_LOWER_BOUND = 6_000_000_000_000_000L;
    private static final long ID_UPPER_BOUND = 7_000_000_000_000_000L;
    private static final long START_TIMEOUT_SECONDS = 5L;
    @Resource
    private IVoucherOrderService service;
    @Resource
    private JdbcTemplate jdbc;
    private long voucherId;
    private long orderId;
    private long userId;

    @BeforeEach
    void setUp() {
        voucherId = ThreadLocalRandom.current().nextLong(ID_LOWER_BOUND, ID_UPPER_BOUND);
        orderId = voucherId + 1L;
        userId = voucherId + 2L;
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,status) VALUES (?,1,'test',100,200,1)", voucherId);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, 3, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))", voucherId);
        authenticate();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tb_inventory_log WHERE voucher_id=?", voucherId);
        jdbc.update("DELETE FROM tb_order_close_task WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM tb_payment_notification WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM tb_refund WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM tb_payment WHERE order_id=?", orderId);
        jdbc.update("DELETE FROM tb_order_event WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_voucher WHERE id=?", voucherId);
        UserHolder.removeUser();
    }

    @Test
    void repeatedDeliveryDeductsStockOnce() {
        service.createVoucherOrder(order());
        service.createVoucherOrder(order());
        assertEquals(2, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?",
                Integer.class, orderId));
    }

    @Test
    void durableRejectionPreventsLateCreation() {
        assertTrue(service.rejectFailedOrder(order()));
        service.createVoucherOrder(order());
        assertEquals(3, stock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?",
                Integer.class, orderId));
    }

    @Test
    void transactionRollsBackStockWhenInsertFails() {
        jdbc.update("INSERT INTO tb_voucher_order (id,user_id,voucher_id) VALUES (?,?,?)",
                orderId, userId + 1L, voucherId + 1L);
        try {
            assertThrows(RuntimeException.class, () -> service.createVoucherOrder(order()));
            assertEquals(3, stock());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_event WHERE order_id = ?",
                    Integer.class, orderId));
        } finally {
            jdbc.update("DELETE FROM tb_voucher_order WHERE id = ?", orderId);
        }
    }

    @Test
    void expiredOrderCannotPayBeforeScheduledScannerRuns() {
        service.createVoucherOrder(order());
        expireOrder();
        assertFalse(service.payOrder(orderId).getSuccess());
        assertTrue(service.closeUnpaidOrder(orderId));
        assertFalse(service.closeUnpaidOrder(orderId));
        assertEquals(3, stock());
    }

    @Test
    void unexpiredOrderCannotBeClosed() {
        service.createVoucherOrder(order());
        assertFalse(service.closeUnpaidOrder(orderId));
        assertTrue(service.payOrder(orderId).getSuccess());
        assertTrue(service.payOrder(orderId).getSuccess());
        assertEquals(2, stock());
    }

    @Test
    void creationAndDeadLetterChooseOneDurableOutcome() throws Exception {
        ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new CustomizableThreadFactory("order-race-test-"),
                new ThreadPoolExecutor.AbortPolicy());
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<?> creation = workers.submit(() -> {
                await(start);
                service.createVoucherOrder(order());
            });
            Future<Boolean> rejection = workers.submit(() -> {
                await(start);
                return service.rejectFailedOrder(order());
            });
            creation.get(10L, TimeUnit.SECONDS);
            boolean rejected = rejection.get(10L, TimeUnit.SECONDS);
            assertEquals(rejected ? 3 : 2, stock());
            assertEquals(rejected ? 0 : 1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?", Integer.class, orderId));
        } finally {
            workers.shutdownNow();
        }
    }

    private void authenticate() {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucherId);
    }

    private void expireOrder() {
        jdbc.update("UPDATE tb_voucher_order SET create_time = DATE_SUB(NOW(), INTERVAL 16 MINUTE), expire_time=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?",
                orderId);
    }

    private void await(CyclicBarrier start) {
        try {
            start.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("test start timed out", exception);
        }
    }

    /**
     * Isolates the persistence layer from Kafka and Redis infrastructure.
     * @author NeighborLife
     */
    @Configuration
    @EnableAutoConfiguration
    @MapperScan("com.hmdp.mapper")
    @Import({VoucherOrderServiceImpl.class, SeckillVoucherServiceImpl.class,
            com.hmdp.payment.PaymentServiceImpl.class, com.hmdp.security.AccessServiceImpl.class})
    public static class TestConfiguration {
        @Bean
        public com.hmdp.payment.PaymentGateway paymentGateway() {
            return new com.hmdp.payment.MockPaymentGateway();
        }

        @Bean
        public StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        public RedisIdWorker redisIdWorker() {
            return mock(RedisIdWorker.class);
        }
    }
}
