package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class VoucherOrderKafkaConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @KafkaListener(topics = "${app.kafka.voucher-order-topic}")
    public void createOrder(String payload) {
        VoucherOrderEvent event = JSONUtil.toBean(payload, VoucherOrderEvent.class);
        voucherOrderService.createVoucherOrder(event.toOrder());
        log.info("秒杀订单持久化完成, orderId={}", event.getOrderId());
    }
}
