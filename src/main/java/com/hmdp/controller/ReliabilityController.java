package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.Result;
import com.hmdp.mq.VoucherOrderEvent;
import com.hmdp.ops.MessageQuarantineService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Positive;

/**
 * Bounded operational inspection and controlled poison-message replay.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/ops")
@Validated
public class ReliabilityController {
    @Resource
    private com.hmdp.service.OperationsQueryService queries;
    @Resource
    private MessageQuarantineService quarantine;
    @Resource
    private com.hmdp.ops.InventoryReconciliationService inventory;

    @AdminOnly
    @GetMapping("/vouchers/{id}/reconciliation")
    public Result inventory(@PathVariable("id") @Positive Long id) { return Result.ok(inventory.inspect(id)); }

    @AdminOnly
    @PutMapping("/vouchers/{id}/reconciliation")
    public Result repair(@PathVariable("id") @Positive Long id) {
        inventory.repair(id);
        return Result.ok();
    }


    @AdminOnly
    @GetMapping("/quarantine")
    public Result quarantine() {
        return queries.quarantine();
    }

    @AdminOnly
    @PostMapping("/quarantine/{id}/replay")
    public Result replay(@PathVariable("id") @Positive Long id, @Valid @RequestBody VoucherOrderEvent event) {
        quarantine.replay(id, event);
        return Result.ok();
    }

    @AdminOnly
    @GetMapping("/payments")
    public Result payments() {
        return queries.payments();
    }

    @AdminOnly
    @GetMapping("/comment-reports")
    public Result reports() { return queries.commentReports(); }

    @AdminOnly
    @GetMapping("/refunds")
    public Result refunds() {
        return queries.refunds();
    }
}
