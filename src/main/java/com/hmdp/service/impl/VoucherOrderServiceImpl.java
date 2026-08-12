package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
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
}
