package com.hmdp.controller;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherCreateDTO;
import com.hmdp.dto.VoucherUpdateDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Positive;

/**
 * Validated coupon publication, management and public details.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/voucher")
@Validated
public class VoucherController {
    @Resource
    private IVoucherService voucherService;

    @PostMapping
    public Result addVoucher(@Valid @RequestBody VoucherCreateDTO request) {
        Voucher voucher = BeanUtil.copyProperties(request, Voucher.class);
        voucherService.addVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @PostMapping("/seckill")
    public Result addSeckillVoucher(@Valid @RequestBody VoucherCreateDTO request) {
        Voucher voucher = BeanUtil.copyProperties(request, Voucher.class);
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @PutMapping
    public Result update(@Valid @RequestBody VoucherUpdateDTO request) {
        return voucherService.updateVoucher(BeanUtil.copyProperties(request, Voucher.class));
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") @Positive Long id) { return voucherService.queryDetail(id); }

    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") @Positive Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }
}
