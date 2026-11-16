package com.hmdp.payment;

import com.hmdp.exception.BusinessException;
import com.hmdp.security.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates verified money movements, order state and durable compensation.
 * @author NeighborLife
 */
@Service
public class PaymentServiceImpl implements PaymentService {
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_CODE_URL = "code_url";
    private static final String PAYMENT_SUCCESS = "SUCCESS";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_ORDER_ID = "order_id";
    private static final String PAYMENT_NOT_CREATED = "NOT_CREATED";
    private static final String REFUND_ABNORMAL = "ABNORMAL";
    private static final String PAYMENT_CLOSED = "CLOSED";
    private static final String FIELD_USER_ID = "user_id";

    private static final int UNPAID = 1;
    private static final int PAID = 2;
    private static final int CANCELED = 4;
    private static final int REFUNDING = 5;
    private static final int REFUNDED = 6;
    private static final int CLOSING = 7;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private PaymentGateway gateway;
    @Resource
    private AccessService access;

    public Map<String, Object> initiate(Long orderId) {
        Map<String, Object> order = transaction().execute(status -> {
            Map<String, Object> current = ownedOrder(orderId, true);
            if (number(current, FIELD_STATUS) != UNPAID
                    || !((Timestamp) current.get("expire_time")).toInstant().isAfter(Instant.now())) {
                throw failure("ORDER_NOT_PAYABLE", "订单当前状态或期限不支持支付");
            }
            Long amount = nullableNumber(current.get("pay_value"));
            if (amount == null || amount < 1L) {
                throw failure("INVALID_PRICE", "订单缺少有效的价格快照");
            }
            jdbc.update("INSERT INTO tb_payment(order_id,amount) VALUES (?,?) "
                    + "ON DUPLICATE KEY UPDATE order_id=order_id", orderId, amount);
            return current;
        });
        Map<String, Object> payment = payment(orderId, false);
        if (payment.get(FIELD_CODE_URL) == null) {
            String codeUrl = gateway.prepay(orderId, number(order, "pay_value"),
                    ((Timestamp) order.get("expire_time")).toInstant());
            jdbc.update("UPDATE tb_payment SET code_url=?,state='PENDING' WHERE order_id=? "
                    + "AND state IN ('CREATED','PENDING')", codeUrl, orderId);
        }
        return payment(orderId, false);
    }

    public Map<String, Object> queryOwned(Long orderId) {
        ownedOrder(orderId, false);
        return payment(orderId, false);
    }

    public void refresh(Long orderId) {
        PaymentReceipt receipt = gateway.query(orderId);
        if (PAYMENT_SUCCESS.equals(receipt.getState())) {
            applyPaid(receipt);
        }
    }

    public void notification(Map<String, String> headers, String body) {
        PaymentReceipt receipt = gateway.notification(headers, body);
        if (!PAYMENT_SUCCESS.equals(receipt.getState())) {
            throw failure("UNSUPPORTED_PAYMENT_EVENT", "支付通知状态尚未成功");
        }
        applyPaid(receipt);
    }

    public void applyPaid(PaymentReceipt receipt) {
        transaction().execute(status -> {
            Map<String, Object> order = order(receipt.getOrderId(), true);
            Map<String, Object> payment = payment(receipt.getOrderId(), true);
            if (!Objects.equals(nullableNumber(payment.get(FIELD_AMOUNT)), receipt.getAmount())
                    || !"CNY".equals(receipt.getCurrency()) || receipt.getTransactionId() == null
                    || receipt.getTransactionId().isEmpty() || !PAYMENT_SUCCESS.equals(receipt.getState())) {
                throw failure("PAYMENT_MISMATCH", "支付金额、币种或交易状态校验失败");
            }
            Object existingTransaction = payment.get("transaction_id");
            if (existingTransaction != null && !existingTransaction.equals(receipt.getTransactionId())) {
                throw failure("PAYMENT_IDENTITY_CONFLICT", "支付交易编号冲突");
            }
            if (receipt.getNotificationId() != null) {
                List<Map<String, Object>> duplicate = jdbc.queryForList("SELECT order_id,transaction_id "
                        + "FROM tb_payment_notification WHERE notification_id=?", receipt.getNotificationId());
                boolean conflictingNotice = !duplicate.isEmpty()
                        && (number(duplicate.get(0), FIELD_ORDER_ID) != receipt.getOrderId()
                        || !receipt.getTransactionId().equals(duplicate.get(0).get("transaction_id")));
                if (conflictingNotice) {
                    throw failure("NOTIFICATION_CONFLICT", "支付通知编号冲突");
                }
                jdbc.update("INSERT IGNORE INTO tb_payment_notification(notification_id,order_id,transaction_id) "
                        + "VALUES (?,?,?)", receipt.getNotificationId(), receipt.getOrderId(), receipt.getTransactionId());
            }
            jdbc.update("UPDATE tb_payment SET state=IF(state='REFUNDED',state,'SUCCESS'),transaction_id=? "
                    + "WHERE order_id=?", receipt.getTransactionId(), receipt.getOrderId());
            long orderState = number(order, FIELD_STATUS);
            if (orderState == UNPAID || orderState == CLOSING) {
                jdbc.update("UPDATE tb_voucher_order SET status=?,pay_time=NOW(),pay_type=3 WHERE id=?",
                        PAID, receipt.getOrderId());
            } else if (orderState == CANCELED) {
                enqueueRefund(receipt.getOrderId(), receipt.getAmount(), "LATE_PAYMENT");
            }
            return null;
        });
    }

    public boolean closeExpired(Long orderId) {
        boolean closing = Boolean.TRUE.equals(transaction().execute(status -> {
            int changed = jdbc.update("UPDATE tb_voucher_order SET status=? WHERE id=? AND status=? "
                    + "AND expire_time<=NOW()", CLOSING, orderId, UNPAID);
            return changed > 0 || number(order(orderId, true), FIELD_STATUS) == CLOSING;
        }));
        if (!closing) {
            return false;
        }
        List<Map<String, Object>> payments = jdbc.queryForList("SELECT order_id FROM tb_payment WHERE order_id=?", orderId);
        if (!payments.isEmpty()) {
            PaymentReceipt receipt = gateway.query(orderId);
            if (PAYMENT_SUCCESS.equals(receipt.getState())) {
                applyPaid(receipt);
                return false;
            }
            try {
                if (!PAYMENT_NOT_CREATED.equals(receipt.getState())) { gateway.close(orderId); }
            } catch (RuntimeException exception) {
                refresh(orderId);
                throw exception;
            }
        }
        return Boolean.TRUE.equals(transaction().execute(status -> {
            Map<String, Object> order = order(orderId, true);
            int changed = jdbc.update("UPDATE tb_voucher_order SET status=?,cancel_time=NOW() WHERE id=? AND status=?",
                    CANCELED, orderId, CLOSING);
            if (changed == 1) {
                int restored = jdbc.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?",
                        order.get("voucher_id"));
                if (restored != 1) {
                    throw new IllegalStateException("关单恢复库存失败");
                }
                jdbc.update("INSERT INTO tb_inventory_log(order_id,voucher_id,reason,quantity_delta) VALUES (?,?,'ORDER_CANCELED',1)",
                        orderId, order.get("voucher_id"));
                jdbc.update("UPDATE tb_payment SET state='CLOSED' WHERE order_id=? AND state IN ('CREATED','PENDING')", orderId);
            }
            return changed == 1;
        }));
    }

    public String requestRefund(Long orderId) {
        return transaction().execute(status -> {
            Map<String, Object> order = ownedOrder(orderId, true);
            long state = number(order, FIELD_STATUS);
            if (state != PAID && state != REFUNDING && state != REFUNDED) {
                throw failure("ORDER_NOT_REFUNDABLE", "当前订单状态不支持退款");
            }
            Map<String, Object> payment = payment(orderId, true);
            enqueueRefund(orderId, number(payment, FIELD_AMOUNT), "BUYER_REQUEST");
            jdbc.update("UPDATE tb_voucher_order SET status=? WHERE id=? AND status=?", REFUNDING, orderId, PAID);
            return "refund-" + orderId;
        });
    }

    public void applyRefund(RefundReceipt receipt) {
        transaction().execute(status -> {
            Map<String, Object> order = order(receipt.getOrderId(), true);
            Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM tb_refund WHERE refund_no=? FOR UPDATE", receipt.getRefundNo());
            if (number(refund, FIELD_ORDER_ID) != receipt.getOrderId()
                    || !Objects.equals(nullableNumber(refund.get(FIELD_AMOUNT)), receipt.getAmount())) {
                throw failure("REFUND_MISMATCH", "退款编号或金额校验失败");
            }
            if (PAYMENT_CLOSED.equals(receipt.getState()) || REFUND_ABNORMAL.equals(receipt.getState())) {
                jdbc.update("UPDATE tb_refund SET state='REQUIRES_ACTION',last_error=? WHERE refund_no=?",
                        receipt.getState(), receipt.getRefundNo());
                return null;
            }
            if (!PAYMENT_SUCCESS.equals(receipt.getState())) {
                jdbc.update("UPDATE tb_refund SET state='PROCESSING',provider_refund_id=? WHERE refund_no=?",
                        receipt.getProviderRefundId(), receipt.getRefundNo());
                return null;
            }
            jdbc.update("UPDATE tb_refund SET state='SUCCESS',provider_refund_id=? WHERE refund_no=?",
                    receipt.getProviderRefundId(), receipt.getRefundNo());
            jdbc.update("UPDATE tb_payment SET state='REFUNDED' WHERE order_id=?", receipt.getOrderId());
            int changed = jdbc.update("UPDATE tb_voucher_order SET status=?,refund_time=NOW(),stock_compensated=0 "
                    + "WHERE id=? AND status=?", REFUNDED, receipt.getOrderId(), REFUNDING);
            if (changed == 1) {
                jdbc.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?", order.get("voucher_id"));
                jdbc.update("INSERT INTO tb_inventory_log(order_id,voucher_id,reason,quantity_delta) VALUES (?,?,'ORDER_REFUNDED',1)",
                        receipt.getOrderId(), order.get("voucher_id"));
            }
            return null;
        });
    }

    private void enqueueRefund(Long orderId, Long amount, String reason) {
        jdbc.update("INSERT INTO tb_refund(refund_no,order_id,amount,reason) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE order_id=order_id", "refund-" + orderId, orderId, amount, reason);
    }

    private Map<String, Object> ownedOrder(Long orderId, boolean lock) {
        Map<String, Object> order = order(orderId, lock);
        if (number(order, FIELD_USER_ID) != access.userId()) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在", HttpStatus.NOT_FOUND);
        }
        return order;
    }

    private Map<String, Object> order(Long orderId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,user_id,voucher_id,pay_value,expire_time,CAST(status AS UNSIGNED) AS status "
                + "FROM tb_voucher_order WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), orderId);
        if (rows.isEmpty()) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private Map<String, Object> payment(Long orderId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_id,amount,currency,state,transaction_id,code_url "
                + "FROM tb_payment WHERE order_id=?" + (lock ? " FOR UPDATE" : ""), orderId);
        if (rows.isEmpty()) {
            throw new BusinessException("PAYMENT_NOT_FOUND", "支付记录不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Long nullableNumber(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private BusinessException failure(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
