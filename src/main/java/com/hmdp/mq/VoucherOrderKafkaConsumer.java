package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * VoucherOrderKafkaConsumer component.
 *
 * @author NeighborLife
 */
@Slf4j
@Component
public class VoucherOrderKafkaConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private com.hmdp.ops.MessageQuarantineService quarantineService;

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
        VoucherOrderEvent event;
        try {
            event = JSONUtil.toBean(payload, VoucherOrderEvent.class);
            event.toOrder();
        } catch (RuntimeException invalid) {
            quarantineService.quarantine(payload);
            log.error("无效订单消息已隔离，等待管理端处理");
            return;
        }
        if (!voucherOrderService.rejectFailedOrder(event.toOrder())) {
            log.warn("死信消息对应订单已存在，跳过库存补偿, orderId={}", event.getOrderId());
            return;
        }
        voucherOrderService.compensateFailedOrder(event.toOrder());
        log.warn("订单进入死信队列，Redis预扣已补偿, orderId={}", event.getOrderId());
    }
}
