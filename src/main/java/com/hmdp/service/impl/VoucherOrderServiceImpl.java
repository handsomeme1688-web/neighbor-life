package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.OrderEventRecord;
import com.hmdp.mapper.OrderEventMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;

/**
 * Flash-sale admission and transactional order persistence.
 *
 * The HTTP thread only runs the Lua script. Database work is performed by the
 * Kafka consumer, so a traffic spike does not directly overwhelm MySQL.
 * @author NeighborLife
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    private static final String SALE_DISABLED_MESSAGE = "优惠券已下架";
    private static final String ENABLED_VOUCHER_SQL = "SELECT COUNT(*) FROM tb_voucher WHERE id=? AND status=1";
    private static final String RESERVATION_USER_FIELD = "userId";


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> CLOSE_COMPENSATE_SCRIPT;
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String REJECTED = "REJECTED";
    private static final long DUPLICATE_RESERVATION = 2L;
    private static final long SALE_NOT_STARTED = 3L;
    private static final long SALE_ENDED = 4L;
    private static final long SALE_NOT_INITIALIZED = 5L;
    private static final long SALE_DISABLED = 6L;
    private static final long COMPENSATION_PENDING = 2L;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        CLOSE_COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        CLOSE_COMPENSATE_SCRIPT.setLocation(new ClassPathResource("order_close_compensate.lua"));
        CLOSE_COMPENSATE_SCRIPT.setResultType(Long.class);

        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderEventMapper orderEventMapper;

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Resource
    private com.hmdp.payment.PaymentService paymentService;

    @Value("${app.order.close-timeout-minutes:15}")
    private long closeTimeoutMinutes;

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (voucherId == null) {
            return Result.fail("优惠券不能为空");
        }
        long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // Lua atomically checks stock/duplicate purchase, reserves inventory,
        // and appends an event to the Redis Stream outbox.
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                Long.toString(userId),
                Long.toString(orderId)
        );
        if (result == null) {
            return Result.fail("秒杀服务暂不可用");
        }
        if (result == 1L) {
            return Result.fail("库存不足");
        }
        if (result == DUPLICATE_RESERVATION) {
            return Result.fail("不能重复下单");
        }
        if (result == SALE_NOT_STARTED) {
            return Result.fail("秒杀尚未开始");
        }
        if (result == SALE_ENDED) {
            return Result.fail("秒杀已经结束");
        }
        if (result == SALE_NOT_INITIALIZED) {
            return Result.fail("秒杀信息未初始化");
        }
        if (result == SALE_DISABLED) { return Result.fail(SALE_DISABLED_MESSAGE); }
        if (result != 0L) {
            return Result.fail("秒杀服务暂不可用");
        }
        return Result.ok(orderId);
    }

    /**
     * Called through the Spring proxy by the Kafka listener. The conditional
     * stock update and order insert share one database transaction.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        OrderEventRecord event = lockEvent(voucherOrder);
        if (REJECTED.equals(event.getOutcome()) || SUCCEEDED.equals(event.getOutcome())) {
            return;
        }
        if (jdbc.queryForObject(ENABLED_VOUCHER_SQL, Long.class,
                voucherOrder.getVoucherId()) == 0) {
            throw new IllegalStateException("优惠券当前已停止销售");
        }
        boolean alreadyCreated = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count() > 0;
        if (alreadyCreated) {
            orderEventMapper.finish(voucherOrder.getId(), SUCCEEDED);
            log.info("忽略重复订单消息, orderId={}", voucherOrder.getId());
            return;
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("数据库库存不足, voucherId=" + voucherOrder.getVoucherId());
        }

        voucherOrder.setStatus(VoucherOrderStatus.UNPAID);
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单持久化失败, orderId=" + voucherOrder.getId());
        }
        jdbc.update("UPDATE tb_voucher_order o JOIN tb_voucher v ON o.voucher_id=v.id "
                + "SET o.pay_value=v.pay_value,o.expire_time=DATE_ADD(o.create_time,INTERVAL ? MINUTE) WHERE o.id=?",
                closeTimeoutMinutes, voucherOrder.getId());
        jdbc.update("INSERT INTO tb_inventory_log(order_id,voucher_id,reason,quantity_delta) VALUES (?,?,'ORDER_CREATED',-1)",
                voucherOrder.getId(), voucherOrder.getVoucherId());
        jdbc.update("INSERT INTO tb_order_close_task(order_id,due_time) "
                + "SELECT id,expire_time FROM tb_voucher_order WHERE id=?", voucherOrder.getId());
        orderEventMapper.finish(voucherOrder.getId(), SUCCEEDED);
    }

    @Override
    public Result queryOrder(Long orderId) {
        if (orderId == null) {
            return Result.fail("订单编号不能为空");
        }
        VoucherOrder order = getById(orderId);
        Long actor = UserHolder.getUser().getId();
        if (order != null && order.getUserId().equals(actor)) { return Result.ok(order); }
        if (order == null) {
            java.util.List<java.util.Map<String,Object>> failed = jdbc.queryForList(
                    "SELECT order_id AS id,outcome AS processingState,failure_reason AS reason "
                    + "FROM tb_order_event WHERE order_id=? AND user_id=?", orderId, actor);
            if (!failed.isEmpty()) { return Result.ok(failed.get(0)); }
            java.util.Map<Object,Object> queued = stringRedisTemplate.opsForHash().entries("seckill:request:" + orderId);
            if (actor.toString().equals(queued.get(RESERVATION_USER_FIELD))) {
                java.util.Map<String,Object> state = new java.util.HashMap<>(2);
                state.put("id", orderId);
                state.put("processingState", "QUEUED");
                return Result.ok(state);
            }
        }
        return Result.fail("ORDER_NOT_FOUND", "订单不存在");
    }

    @Override
    public Result payOrder(Long orderId) {
        if (orderId == null) {
            return Result.fail("订单编号不能为空");
        }
        Long userId = UserHolder.getUser().getId();
        boolean paid = update()
                .set("status", VoucherOrderStatus.PAID)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", VoucherOrderStatus.UNPAID)
                .apply("create_time > DATE_SUB(NOW(), INTERVAL {0} MINUTE)", closeTimeoutMinutes)
                .update();
        if (paid) {
            return Result.ok();
        }

        VoucherOrder current = getById(orderId);
        if (current == null || !current.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        if (Objects.equals(current.getStatus(), VoucherOrderStatus.PAID)) {
            return Result.ok();
        }
        if (Objects.equals(current.getStatus(), VoucherOrderStatus.CANCELED)
                || Objects.equals(current.getStatus(), VoucherOrderStatus.UNPAID)) {
            return Result.fail("订单已超时，不能支付");
        }
        return Result.fail("当前订单状态不能支付");
    }

    @Override
    public boolean closeUnpaidOrder(Long orderId) {
        return paymentService.closeExpired(orderId);
    }

    @Override
    public void compensateCanceledOrder(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                CLOSE_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                order.getVoucherId().toString(),
                order.getId().toString()
        );
        if (result == null || result == COMPENSATION_PENDING) {
            throw new IllegalStateException("Redis库存补偿暂未完成, orderId=" + order.getId());
        }

        update()
                .set("stock_compensated", 1)
                .eq("id", order.getId())
                .in("status", VoucherOrderStatus.CANCELED, VoucherOrderStatus.REFUNDED)
                .eq("stock_compensated", 0)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rejectFailedOrder(VoucherOrder order) {
        OrderEventRecord event = lockEvent(order);
        if (SUCCEEDED.equals(event.getOutcome())) {
            return false;
        }
        boolean exists = query().eq("user_id", order.getUserId())
                .eq("voucher_id", order.getVoucherId()).count() > 0;
        orderEventMapper.finish(order.getId(), exists ? SUCCEEDED : REJECTED);
        if (!exists) {
            jdbc.update("UPDATE tb_order_event SET failure_reason='PERSISTENCE_RETRY_EXHAUSTED' WHERE order_id=?", order.getId());
        }
        return !exists;
    }

    @Override
    public void compensateFailedOrder(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                order.getVoucherId().toString(), order.getUserId().toString(), order.getId().toString());
        if (result == null || result == COMPENSATION_PENDING) {
            throw new IllegalStateException("失败订单的Redis预扣补偿待重试, orderId=" + order.getId());
        }
        orderEventMapper.markCompensated(order.getId());
    }

    private OrderEventRecord lockEvent(VoucherOrder order) {
        if (order.getId() == null || order.getUserId() == null || order.getVoucherId() == null) {
            throw new IllegalArgumentException("订单消息缺少编号、用户或优惠券信息");
        }
        orderEventMapper.insertIfAbsent(order);
        OrderEventRecord event = orderEventMapper.lock(order.getId());
        if (event == null || !Objects.equals(event.getUserId(), order.getUserId())
                || !Objects.equals(event.getVoucherId(), order.getVoucherId())) {
            throw new IllegalArgumentException("订单消息身份与处理记录不一致");
        }
        return event;
    }
}
