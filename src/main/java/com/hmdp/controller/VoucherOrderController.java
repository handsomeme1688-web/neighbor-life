package com.hmdp.controller;


import com.hmdp.annotation.SlidingWindowRateLimit;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
@Validated
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;


    @PostMapping("seckill/{id}")
    @SlidingWindowRateLimit(
            key = "voucher-order:seckill", windowSeconds = 1,
            globalLimit = 500, ipLimit = 50, userLimit = 10)
    public Result seckillVoucher(
            @PathVariable("id") @Positive(message = "优惠券编号必须大于0") Long voucherId) {

        return voucherOrderService.seckillVoucher(voucherId);
    }

    @GetMapping("/{id}")
    public Result queryOrder(
            @PathVariable("id") @Positive(message = "订单编号必须大于0") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }

    /**
     * Demo payment endpoint. It only verifies the order state transition and
     * does not connect to a real payment provider.
     */
    @PostMapping("/{id}/pay")
    public Result payOrder(
            @PathVariable("id") @Positive(message = "订单编号必须大于0") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }
}
