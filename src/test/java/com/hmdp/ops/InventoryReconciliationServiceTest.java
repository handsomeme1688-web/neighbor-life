package com.hmdp.ops;

import com.hmdp.exception.BusinessException;
import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Protects offline repair from live orders and unknown reservations.
 * @author NeighborLife
 */
class InventoryReconciliationServiceTest {
    private InventoryReconciliationServiceImpl inventory;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private HashOperations<String,Object,Object> reservations;
    private PlatformTransactionManager transactions;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        inventory = new InventoryReconciliationServiceImpl();
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class, invocation -> "execute".equals(invocation.getMethod().getName())
                ? 1L : Answers.RETURNS_DEFAULTS.answer(invocation));
        reservations = mock(HashOperations.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        StreamOperations<String,Object,Object> stream = mock(StreamOperations.class);
        SetOperations<String,String> purchasers = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForStream()).thenReturn(stream);
        when(redis.opsForHash()).thenReturn(reservations);
        when(redis.opsForSet()).thenReturn(purchasers);
        when(stream.size("stream.orders")).thenReturn(0L);
        when(reservations.entries(anyString())).thenReturn(Collections.singletonMap("1", "10"));
        when(purchasers.members(anyString())).thenReturn(Collections.singleton("1"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(2);
        Map<String,Object> stock = new HashMap<>(3);
        stock.put("stock", 3);
        stock.put("begin_time", Timestamp.from(Instant.now().minusSeconds(60L)));
        stock.put("end_time", Timestamp.from(Instant.now().plusSeconds(3600L)));
        when(jdbc.queryForMap(anyString(), eq(1L))).thenReturn(stock);
        Map<String,Object> order = new HashMap<>(3);
        order.put("id", 10L);order.put("user_id", 1L);order.put("status", 6);
        when(jdbc.queryForList(anyString(), eq(1L))).thenReturn(Collections.singletonList(order));
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        audit = mock(AuditService.class);
        ReflectionTestUtils.setField(inventory,"jdbc",jdbc);
        ReflectionTestUtils.setField(inventory,"redis",redis);
        ReflectionTestUtils.setField(inventory,"transactionManager",transactions);
        ReflectionTestUtils.setField(inventory,"access",mock(AccessService.class));
        ReflectionTestUtils.setField(inventory,"audit",audit);
        ReflectionTestUtils.setField(inventory,"businessZone","Asia/Shanghai");
    }

    @Test
    void rejectsActiveActivityBeforeRebuilding() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L))).thenReturn(1);
        assertThrows(BusinessException.class, () -> inventory.repair(1L));
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsUnpersistedReservation() {
        when(reservations.entries(anyString())).thenReturn(Collections.singletonMap("1", "other-order"));
        assertThrows(BusinessException.class, () -> inventory.repair(1L));
        verify(transactions).rollback(any());
    }

    @Test
    void repairsDrainedTerminalOrdersAndWritesAudit() {
        inventory.repair(1L);
        verify(audit).record(eq("INVENTORY_REPAIR"),eq(1L),isNull(),eq(3));
        verify(transactions).commit(any());
    }
}
