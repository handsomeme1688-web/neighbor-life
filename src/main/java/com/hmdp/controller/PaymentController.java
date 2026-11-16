package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.payment.MockPaymentGateway;
import com.hmdp.payment.PaymentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.constraints.Positive;
import java.util.HashMap;
import java.util.Map;

/**
 * Customer payment/refund operations and signature-verified provider notifications.
 * @author NeighborLife
 */
@RestController
@Validated
public class PaymentController {
    private static final String SIMULATION_DISABLED_MESSAGE = "本地支付模拟通道尚未启用";

    @Resource
    private PaymentService payments;
    @Resource
    private ObjectProvider<MockPaymentGateway> simulation;

    @PostMapping("/payments/{id}")
    public Result initiate(@PathVariable("id") @Positive Long id) { return Result.ok(payments.initiate(id)); }
    @GetMapping("/payments/{id}")
    public Result query(@PathVariable("id") @Positive Long id) { return Result.ok(payments.queryOwned(id)); }
    @PostMapping("/payments/{id}/refund")
    public Result refund(@PathVariable("id") @Positive Long id) { return Result.ok(payments.requestRefund(id)); }

    @PostMapping("/payments/wechat/notify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notification(@RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Signature") String signature,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Nonce") String nonce, @RequestBody String body) {
        Map<String, String> headers = new HashMap<>(4);
        headers.put("Wechatpay-Serial", serial);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", nonce);
        payments.notification(headers, body);
    }

    @AdminOnly
    @PostMapping("/ops/simulation/payments/{id}/settle")
    public Result settleSimulation(@PathVariable("id") @Positive Long id) {
        MockPaymentGateway gateway = simulation.getIfAvailable();
        if (gateway == null) { throw new IllegalArgumentException(SIMULATION_DISABLED_MESSAGE); }
        gateway.settle(id);
        return Result.ok();
    }
}
