package com.hmdp.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.dto.VoucherDetailDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.Collections;

/**
 * Observable backlogs and cache statistics for reliability alerts.
 * @author NeighborLife
 */
@Component
public class ReliabilityMetrics implements MeterBinder {
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private Cache<Long, Shop> shopLocalCache;
    @Resource
    private Cache<Long, VoucherDetailDTO> voucherLocalCache;

    @Override
    public void bindTo(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, shopLocalCache, "shops", Collections.emptyList());
        CaffeineCacheMetrics.monitor(registry, voucherLocalCache, "vouchers", Collections.emptyList());
        bind(registry, "neighbor.cache.outbox.pending", "SELECT COUNT(*) FROM tb_cache_invalidation");
        bind(registry, "neighbor.activity.outbox.pending", "SELECT COUNT(*) FROM tb_seckill_initialization");
        bind(registry, "neighbor.refunds.pending", "SELECT COUNT(*) FROM tb_refund WHERE state IN ('PENDING','PROCESSING')");
        bind(registry, "neighbor.orders.compensation.pending", "SELECT COUNT(*) FROM tb_voucher_order "
                + "WHERE status IN (4,6) AND stock_compensated=0");
        bind(registry, "neighbor.messages.quarantined", "SELECT COUNT(*) FROM tb_message_quarantine WHERE state='PENDING'");
        bind(registry, "neighbor.deadlines.overdue", "SELECT COUNT(*) FROM tb_order_close_task WHERE state='PENDING' AND due_time<NOW()");
        Gauge.builder("neighbor.stream.pending", this, value -> value.streamPending()).register(registry);
    }

    private void bind(MeterRegistry registry, String name, String sql) {
        Gauge.builder(name, this, value -> value.count(sql)).register(registry);
    }

    private double count(String sql) {
        try { return jdbc.queryForObject(sql, Double.class); }
        catch (RuntimeException exception) { return Double.NaN; }
    }

    private double streamPending() {
        try {
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                    redis.opsForStream().pending("stream.orders", "kafka-relay");
            return summary == null ? 0D : summary.getTotalPendingMessages();
        } catch (RuntimeException exception) { return Double.NaN; }
    }
}
