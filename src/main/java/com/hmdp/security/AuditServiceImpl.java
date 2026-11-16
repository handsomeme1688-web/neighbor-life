package com.hmdp.security;

import cn.hutool.json.JSONUtil;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * Persists actor and before/after values with the business transaction.
 * @author NeighborLife
 */
@Service
public class AuditServiceImpl implements AuditService {
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private AccessService access;

    public void record(String action, Object resourceId, Object before, Object after) {
        jdbc.update("INSERT INTO tb_audit_log(actor_id,action,resource_id,before_data,after_data,trace_id) "
                + "VALUES (?,?,?,?,?,?)", access.userId(), action, String.valueOf(resourceId),
                before == null ? null : JSONUtil.toJsonStr(before), after == null ? null : JSONUtil.toJsonStr(after),
                MDC.get("traceId"));
    }
}
