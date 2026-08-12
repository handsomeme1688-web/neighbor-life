package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Component
public class VoucherOrderKafkaConsumer {

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = "${app.kafka.voucher-order-topic}")
    public void createOrder(String payload) {
        VoucherOrderEvent event = JSONUtil.toBean(payload, VoucherOrderEvent.class);
        voucherOrderService.createVoucherOrder(event.toOrder());
        log.info("秒杀订单持久化完成, orderId={}", event.getOrderId());
    }

    /**
     * After normal retries are exhausted the record is moved to the DLT. This
     * listener releases the Redis reservation so the user may retry later.
     * The Lua script is idempotent and restores stock at most once.
     */
    @KafkaListener(
            topics = "${app.kafka.voucher-order-dlt-topic}",
            groupId = "${spring.kafka.consumer.group-id}-compensator",
            containerFactory = "compensationKafkaListenerContainerFactory"
    )
    public void compensateDeadLetter(String payload) {
        VoucherOrderEvent event = JSONUtil.toBean(payload, VoucherOrderEvent.class);
        boolean orderExists = voucherOrderService.query()
                .eq("user_id", event.getUserId())
                .eq("voucher_id", event.getVoucherId())
                .count() > 0;
        if (orderExists) {
            log.warn("死信消息对应订单已存在，跳过库存补偿, orderId={}", event.getOrderId());
            return;
        }
        Long compensated = stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                Collections.emptyList(),
                event.getVoucherId().toString(),
                event.getUserId().toString()
        );
        log.error("订单进入死信队列，Redis 预扣已补偿, orderId={}, compensated={}",
                event.getOrderId(), compensated);
    }
}
