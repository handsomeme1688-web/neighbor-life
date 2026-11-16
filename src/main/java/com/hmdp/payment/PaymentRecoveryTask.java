package com.hmdp.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Durable deadline execution, payment reconciliation and refund retries.
 * @author NeighborLife
 */
@Component
public class PaymentRecoveryTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentRecoveryTask.class);
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private PaymentService payments;
    @Resource
    private PaymentGateway gateway;

    @Scheduled(fixedDelayString = "${app.order.deadline-scan-ms:1000}")
    public void runDeadlines() {
        List<Long> orders = jdbc.queryForList("SELECT order_id FROM tb_order_close_task WHERE state='PENDING' "
                + "AND due_time<=NOW() AND next_attempt_time<=NOW() ORDER BY due_time LIMIT 100", Long.class);
        for (Long id : orders) {
            int claimed = jdbc.update("UPDATE tb_order_close_task SET next_attempt_time=DATE_ADD(NOW(),INTERVAL 30 SECOND),"
                    + "attempts=attempts+1 WHERE order_id=? AND state='PENDING' AND next_attempt_time<=NOW()", id);
            if (claimed == 0) { continue; }
            try {
                payments.closeExpired(id);
                Integer state = jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, id);
                if (Integer.valueOf(1).equals(state)) {
                    jdbc.update("UPDATE tb_order_close_task t JOIN tb_voucher_order o ON t.order_id=o.id "
                            + "SET t.due_time=o.expire_time,t.next_attempt_time=o.expire_time WHERE t.order_id=?", id);
                } else {
                    jdbc.update("UPDATE tb_order_close_task SET state='DONE',last_error=NULL WHERE order_id=?", id);
                }
            } catch (RuntimeException exception) {
                jdbc.update("UPDATE tb_order_close_task SET last_error='CLOSE_RETRY' WHERE order_id=?", id);
                LOGGER.error("到期任务待重试, orderId={}", id, exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.payment.reconcile-scan-ms:10000}")
    public void reconcilePayments() {
        List<Long> ids = jdbc.queryForList("SELECT order_id FROM tb_payment WHERE state IN ('PENDING','CREATED') "
                + "ORDER BY update_time LIMIT 100", Long.class);
        for (Long id : ids) {
            try {
                payments.refresh(id);
            } catch (RuntimeException exception) {
                jdbc.update("UPDATE tb_payment SET last_error='QUERY_RETRY' WHERE order_id=?", id);
                LOGGER.warn("支付查单待重试, orderId={}", id);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.payment.refund-scan-ms:5000}")
    public void retryRefunds() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM tb_refund "
                + "WHERE state IN ('PENDING','PROCESSING') AND next_attempt_time<=NOW() ORDER BY next_attempt_time LIMIT 100");
        for (Map<String, Object> row : rows) {
            String refundNo = row.get("refund_no").toString();
            int claimed = jdbc.update("UPDATE tb_refund SET next_attempt_time=DATE_ADD(NOW(),INTERVAL 30 SECOND),"
                    + "attempts=attempts+1 WHERE refund_no=? AND state IN ('PENDING','PROCESSING') AND next_attempt_time<=NOW()", refundNo);
            if (claimed == 0) { continue; }
            try {
                RefundReceipt receipt;
                if ("PROCESSING".equals(row.get("state"))) {
                    receipt = gateway.queryRefund(refundNo);
                } else {
                    receipt = gateway.refund(((Number) row.get("order_id")).longValue(), refundNo,
                            ((Number) row.get("amount")).longValue(), row.get("reason").toString());
                }
                if (receipt == null) { throw new IllegalStateException("退款查询结果为空"); }
                payments.applyRefund(receipt);
            } catch (RuntimeException exception) {
                jdbc.update("UPDATE tb_refund SET last_error='REFUND_RETRY' WHERE refund_no=?", refundNo);
                LOGGER.error("退款待重试, refundNo={}", refundNo, exception);
            }
        }
    }
}
