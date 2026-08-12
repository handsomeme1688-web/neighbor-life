package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * Flash-sale admission and transactional order persistence.
 *
 * The HTTP thread only runs the Lua script. Database work is performed by the
 * Kafka consumer, so a traffic spike does not directly overwhelm MySQL.
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> CLOSE_COMPENSATE_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        CLOSE_COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        CLOSE_COMPENSATE_SCRIPT.setLocation(new ClassPathResource("order_close_compensate.lua"));
        CLOSE_COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        if (result == 2L) {
            return Result.fail("不能重复下单");
        }
        if (result == 3L) {
            return Result.fail("秒杀尚未开始");
        }
        if (result == 4L) {
            return Result.fail("秒杀已经结束");
        }
        if (result == 5L) {
            return Result.fail("秒杀信息未初始化");
        }
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
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        boolean alreadyCreated = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count() > 0;
        if (alreadyCreated) {
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

        try {
            save(voucherOrder);
        } catch (DuplicateKeyException duplicate) {
            // The unique index is the final concurrency guard. Rethrowing rolls
            // back the stock deduction made earlier in this transaction.
            throw duplicate;
        }
    }

    @Override
    public Result queryOrder(Long orderId) {
        if (orderId == null) {
            return Result.fail("订单编号不能为空");
        }
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(UserHolder.getUser().getId())) {
            return Result.fail("订单不存在");
        }
        return Result.ok(order);
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
                .update();
        if (paid) {
            return Result.ok();
        }

        VoucherOrder current = getById(orderId);
        if (current == null || !current.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        if (current.getStatus() == VoucherOrderStatus.PAID) {
            return Result.ok();
        }
        if (current.getStatus() == VoucherOrderStatus.CANCELED) {
            return Result.fail("订单已超时关闭，不能支付");
        }
        return Result.fail("当前订单状态不能支付");
    }

    @Override
    @Transactional
    public boolean closeUnpaidOrder(Long orderId) {
        boolean closed = update()
                .set("status", VoucherOrderStatus.CANCELED)
                .set("cancel_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", VoucherOrderStatus.UNPAID)
                .update();
        if (!closed) {
            return false;
        }

        VoucherOrder order = getById(orderId);
        boolean stockRestored = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockRestored) {
            throw new IllegalStateException("关闭订单时恢复数据库库存失败, orderId=" + orderId);
        }
        return true;
    }

    @Override
    public void compensateCanceledOrder(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                CLOSE_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                order.getVoucherId().toString(),
                order.getId().toString()
        );
        if (result == null) {
            throw new IllegalStateException("Redis库存补偿暂未完成, orderId=" + order.getId());
        }

        update()
                .set("stock_compensated", 1)
                .eq("id", order.getId())
                .eq("status", VoucherOrderStatus.CANCELED)
                .eq("stock_compensated", 0)
                .update();
    }
}
