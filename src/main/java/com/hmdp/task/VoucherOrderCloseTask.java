package com.hmdp.task;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes expired unpaid orders and retries Redis stock compensation.
 */
@Slf4j
@Component
public class VoucherOrderCloseTask {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Value("${app.order.close-timeout-minutes:15}")
    private long closeTimeoutMinutes;

    @Value("${app.order.close-batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.order.close-scan-delay-ms:30000}")
    public void closeExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(closeTimeoutMinutes);
        List<VoucherOrder> expiredOrders = voucherOrderService.query()
                .select("id")
                .eq("status", VoucherOrderStatus.UNPAID)
                .le("create_time", deadline)
                .orderByAsc("create_time")
                .last("LIMIT " + batchSize)
                .list();

        for (VoucherOrder order : expiredOrders) {
            try {
                if (voucherOrderService.closeUnpaidOrder(order.getId())) {
                    log.info("超时未支付订单已关闭, orderId={}", order.getId());
                }
            } catch (Exception e) {
                log.error("关闭超时订单失败，等待下次重试, orderId={}", order.getId(), e);
            }
        }

        retryRedisCompensation();
    }

    private void retryRedisCompensation() {
        List<VoucherOrder> pendingOrders = voucherOrderService.query()
                .select("id", "voucher_id")
                .eq("status", VoucherOrderStatus.CANCELED)
                .eq("stock_compensated", 0)
                .orderByAsc("cancel_time")
                .last("LIMIT " + batchSize)
                .list();

        for (VoucherOrder order : pendingOrders) {
            try {
                voucherOrderService.compensateCanceledOrder(order);
                log.info("取消订单的Redis库存已补偿, orderId={}", order.getId());
            } catch (Exception e) {
                log.error("Redis库存补偿失败，等待下次重试, orderId={}", order.getId(), e);
            }
        }
    }
}
