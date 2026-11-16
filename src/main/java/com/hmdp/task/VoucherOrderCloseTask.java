package com.hmdp.task;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.OrderEventRecord;
import com.hmdp.mapper.OrderEventMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Closes expired unpaid orders and retries Redis stock compensation.
 * @author NeighborLife
 */
@Slf4j
@Component
public class VoucherOrderCloseTask {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private OrderEventMapper orderEventMapper;

    @Value("${app.order.close-timeout-minutes:15}")
    private long closeTimeoutMinutes;

    @Value("${app.order.close-batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.order.close-scan-delay-ms:30000}")
    public void closeExpiredOrders() {
        List<VoucherOrder> expiredOrders = voucherOrderService.query()
                .select("id")
                .in("status", VoucherOrderStatus.UNPAID, VoucherOrderStatus.CLOSING)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} MINUTE)", closeTimeoutMinutes)
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
        retryFailedReservations();
    }

    private void retryRedisCompensation() {
        List<VoucherOrder> pendingOrders = voucherOrderService.query()
                .select("id", "voucher_id")
                .in("status", VoucherOrderStatus.CANCELED, VoucherOrderStatus.REFUNDED)
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

    private void retryFailedReservations() {
        List<OrderEventRecord> events = orderEventMapper.findPendingCompensation(batchSize);
        for (OrderEventRecord event : events) {
            try {
                voucherOrderService.compensateFailedOrder(new VoucherOrder()
                        .setId(event.getOrderId()).setUserId(event.getUserId())
                        .setVoucherId(event.getVoucherId()));
            } catch (RuntimeException exception) {
                log.error("失败订单预扣补偿待重试, orderId={}", event.getOrderId(), exception);
            }
        }
    }
}
