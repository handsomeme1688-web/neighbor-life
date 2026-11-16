package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.security.AccessService;
import com.hmdp.service.OperationsQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;

/**
 * Reads bounded operational queues after checking the current administrator role.
 * @author NeighborLife
 */
@Service
public class OperationsQueryServiceImpl implements OperationsQueryService {
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private AccessService access;
    @Override
    public Result quarantine() {
        access.requireAdmin();
        return Result.ok(jdbc.queryForList("SELECT * FROM tb_message_quarantine ORDER BY id DESC LIMIT 100"));
    }
    @Override
    public Result payments() {
        access.requireAdmin();
        return Result.ok(jdbc.queryForList("SELECT order_id,amount,state,last_error,update_time FROM tb_payment ORDER BY update_time DESC LIMIT 100"));
    }
    @Override
    public Result refunds() {
        access.requireAdmin();
        return Result.ok(jdbc.queryForList("SELECT * FROM tb_refund ORDER BY update_time DESC LIMIT 100"));
    }
    @Override
    public Result commentReports() {
        access.requireAdmin();
        return Result.ok(jdbc.queryForList("SELECT * FROM tb_comment_report ORDER BY create_time DESC LIMIT 100"));
    }
}
