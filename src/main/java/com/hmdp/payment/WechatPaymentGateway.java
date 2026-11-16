package com.hmdp.payment;

import cn.hutool.json.JSONUtil;
import com.hmdp.exception.BusinessException;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Official WeChat Pay v3 SDK adapter with public-key verification and AES-GCM decryption.
 * @author NeighborLife
 */
@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "wechat")
public class WechatPaymentGateway implements PaymentGateway {
    private static final String HTTPS_PREFIX = "https://";
    private static final String PROVIDER_ORDER_MISSING = "ORDER_NOT_EXIST";
    private static final int MAX_NOTIFICATION_ID_LENGTH = 128;

    private static final long CALLBACK_WINDOW_SECONDS = 300L;
    private final String appId;
    private final String merchantId;
    private final String notifyUrl;
    private final NativePayService nativePay;
    private final RefundService refunds;
    private final NotificationParser parser;

    public WechatPaymentGateway(@Value("${app.payment.app-id:}") String appId,
            @Value("${app.payment.merchant-id:}") String merchantId,
            @Value("${app.payment.private-key-path:}") String privateKeyPath,
            @Value("${app.payment.merchant-serial:}") String merchantSerial,
            @Value("${app.payment.public-key-path:}") String publicKeyPath,
            @Value("${app.payment.public-key-id:}") String publicKeyId,
            @Value("${app.payment.api-v3-key:}") String apiV3Key,
            @Value("${app.payment.notify-url:}") String notifyUrl) {
        if (appId.isEmpty() || merchantId.isEmpty() || !notifyUrl.startsWith(HTTPS_PREFIX)) {
            throw new IllegalArgumentException("微信支付需要应用、商户及HTTPS回调地址配置");
        }
        this.appId = appId;
        this.merchantId = merchantId;
        this.notifyUrl = notifyUrl;
        RSAPublicKeyConfig config = new RSAPublicKeyConfig.Builder().merchantId(merchantId)
                .privateKeyFromPath(privateKeyPath).merchantSerialNumber(merchantSerial)
                .publicKeyFromPath(publicKeyPath).publicKeyId(publicKeyId).apiV3Key(apiV3Key).build();
        com.wechat.pay.java.core.http.HttpClient client = new com.wechat.pay.java.core.http.DefaultHttpClientBuilder()
                .config(config).connectTimeoutMs(3000).readTimeoutMs(10000).writeTimeoutMs(5000).build();
        nativePay = new NativePayService.Builder().httpClient(client).build();
        refunds = new RefundService.Builder().httpClient(client).build();
        parser = new NotificationParser(config);
    }

    @Override
    public String prepay(Long orderId, Long amount, Instant expiresAt) {
        Amount money = new Amount();
        money.setTotal(Math.toIntExact(amount));
        money.setCurrency("CNY");
        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(merchantId);
        request.setDescription("邻享生活优惠券订单");
        request.setOutTradeNo(orderId.toString());
        request.setTimeExpire(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(expiresAt.atOffset(ZoneOffset.UTC)));
        request.setNotifyUrl(notifyUrl);
        request.setAmount(money);
        return nativePay.prepay(request).getCodeUrl();
    }

    @Override
    public PaymentReceipt query(Long orderId) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(merchantId);
        request.setOutTradeNo(orderId.toString());
        try {
            return receipt(nativePay.queryOrderByOutTradeNo(request), null);
        } catch (com.wechat.pay.java.core.exception.ServiceException exception) {
            if (PROVIDER_ORDER_MISSING.equals(exception.getErrorCode())) {
                return new PaymentReceipt(null, orderId, 0L, "CNY", null, "NOT_CREATED");
            }
            throw exception;
        }
    }

    @Override
    public void close(Long orderId) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setMchid(merchantId);
        request.setOutTradeNo(orderId.toString());
        nativePay.closeOrder(request);
    }

    @Override
    public PaymentReceipt notification(Map<String, String> headers, String body) {
        try {
            String timestamp = headers.get("Wechatpay-Timestamp");
            long seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (seconds < now - CALLBACK_WINDOW_SECONDS || seconds > now + CALLBACK_WINDOW_SECONDS) {
                throw new IllegalArgumentException("回调时间超出校验窗口");
            }
            RequestParam request = new RequestParam.Builder().serialNumber(headers.get("Wechatpay-Serial"))
                    .nonce(headers.get("Wechatpay-Nonce")).timestamp(timestamp)
                    .signature(headers.get("Wechatpay-Signature")).body(body).build();
            Transaction transaction = parser.parse(request, Transaction.class);
            String id = JSONUtil.parseObj(body).getStr("id");
            if (id == null || id.isEmpty() || id.length() > MAX_NOTIFICATION_ID_LENGTH
                    || !"TRANSACTION.SUCCESS".equals(JSONUtil.parseObj(body).getStr("event_type"))) {
                throw new IllegalArgumentException("支付通知标识或类型无效");
            }
            return receipt(transaction, id);
        } catch (RuntimeException exception) {
            throw new BusinessException("INVALID_PAYMENT_SIGNATURE", "支付回调验签失败", HttpStatus.BAD_REQUEST);
        }
    }

    private PaymentReceipt receipt(Transaction transaction, String notificationId) {
        if (!merchantId.equals(transaction.getMchid()) || !appId.equals(transaction.getAppid())
                || transaction.getAmount() == null) {
            throw new BusinessException("INVALID_PAYMENT_IDENTITY", "支付商户或应用校验失败", HttpStatus.BAD_REQUEST);
        }
        return new PaymentReceipt(notificationId, Long.valueOf(transaction.getOutTradeNo()),
                transaction.getAmount().getTotal().longValue(), transaction.getAmount().getCurrency(),
                transaction.getTransactionId(), transaction.getTradeState().name());
    }

    @Override
    public RefundReceipt refund(Long orderId, String refundNo, Long amount, String reason) {
        AmountReq money = new AmountReq();
        money.setCurrency("CNY");
        money.setTotal(amount);
        money.setRefund(amount);
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(orderId.toString());
        request.setOutRefundNo(refundNo);
        request.setReason(reason);
        request.setAmount(money);
        return refundReceipt(refunds.create(request));
    }

    @Override
    public RefundReceipt queryRefund(String refundNo) {
        QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(refundNo);
        return refundReceipt(refunds.queryByOutRefundNo(request));
    }

    private RefundReceipt refundReceipt(Refund refund) {
        return new RefundReceipt(refund.getOutRefundNo(), refund.getRefundId(),
                Long.valueOf(refund.getOutTradeNo()), refund.getAmount().getRefund(), refund.getStatus().name());
    }
}
