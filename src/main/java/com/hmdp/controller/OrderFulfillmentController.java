package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.RedemptionDTO;
import com.hmdp.service.IOrderFulfillmentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Positive;

/**
 * Buyer wallet credentials and merchant redemption operations.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/voucher-order")
@Validated
public class OrderFulfillmentController {
    @Resource
    private IOrderFulfillmentService fulfillment;
    @GetMapping("/{id}/redemption")
    public Result credential(@PathVariable("id") @Positive Long id) { return fulfillment.issue(id); }
    @PostMapping("/{id}/redeem")
    public Result redeem(@PathVariable("id") @Positive Long id, @Valid @RequestBody RedemptionDTO request) {
        return fulfillment.redeem(id, request.getCode());
    }
}
