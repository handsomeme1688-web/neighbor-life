package com.hmdp.ops;

import com.hmdp.security.AccessService;
import com.hmdp.security.AuditService;
import com.hmdp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.*;

/**
 * Inspects reservation differences and repairs drained, offline activities atomically.
 * @author NeighborLife
 */
@Component
public class InventoryReconciliationServiceImpl implements InventoryReconciliationService {
    private static final String ACTIVITY_MUST_BE_OFFLINE = "请先下架活动，再执行库存修复";

    private static final String RELAY_MUST_BE_DRAINED = "请等待在途订单转发完成";

    private static final String ACTIVITY_REPAIR_TOO_LARGE = "请使用分批离线对账处理该活动";

    private static final String OPEN_ORDERS_REMAIN = "请先完成未支付订单关单与待退款处理";

    private static final String UNKNOWN_RESERVATIONS = "存在尚未落库或需要人工核验的预扣记录";

    private static final String LEGACY_RESERVATIONS = "存在缺少订单归属的历史预扣记录";

    private static final String REDIS_REBUILD_PENDING = "库存重建等待重试";
    private static final int MAX_REPAIR_ORDERS = 10000;
    private static final DefaultRedisScript<Long> REBUILD_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> DRAINED_SCRIPT = new DefaultRedisScript<>();
    static {
        REBUILD_SCRIPT.setLocation(new ClassPathResource("rebuild_offline_seckill.lua"));
        REBUILD_SCRIPT.setResultType(Long.class);
        DRAINED_SCRIPT.setLocation(new ClassPathResource("outbox_drained.lua"));
        DRAINED_SCRIPT.setResultType(Long.class);
    }
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private AccessService access;
    @Resource
    private AuditService audit;
    @Value("${app.business-zone:Asia/Shanghai}")
    private String businessZone;

    public Map<String, Object> inspect(Long id) {
        access.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>(6);
        result.put("voucherId", id);
        result.put("databaseStock", jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, id));
        result.put("redisStock", redis.opsForValue().get("seckill:stock:" + id));
        result.put("uncompensatedOrders", jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order "
                + "WHERE voucher_id=? AND status IN (4,6) AND stock_compensated=0", Long.class, id));
        result.put("reservations", redis.opsForHash().size("seckill:reservation:" + id));
        result.put("enabled", jdbc.queryForObject("SELECT status FROM tb_voucher WHERE id=?", Integer.class, id));
        return result;
    }

    public void repair(Long id) {
        access.requireAdmin();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.execute(status -> {
            Integer publication = jdbc.queryForObject("SELECT status FROM tb_voucher WHERE id=?", Integer.class, id);
            if (Integer.valueOf(1).equals(publication)) { throw busy(ACTIVITY_MUST_BE_OFFLINE); }
            redis.opsForValue().set("seckill:enabled:" + id, "0");
            Map<String, Object> inventory = jdbc.queryForMap("SELECT stock,begin_time,end_time "
                    + "FROM tb_seckill_voucher WHERE voucher_id=? FOR UPDATE", id);
            Long drained = redis.execute(DRAINED_SCRIPT, Collections.singletonList("stream.orders"), "kafka-relay");
            if (!Long.valueOf(1L).equals(drained)) { throw busy(RELAY_MUST_BE_DRAINED); }
            List<Map<String, Object>> orders = jdbc.queryForList("SELECT id,user_id,CAST(status AS UNSIGNED) AS status "
                    + "FROM tb_voucher_order WHERE voucher_id=? LIMIT 10001", id);
            if (orders.size() > MAX_REPAIR_ORDERS) { throw busy(ACTIVITY_REPAIR_TOO_LARGE); }
            Map<String, String> knownOrders = new HashMap<>(orders.size());
            for (Map<String, Object> order : orders) {
                int orderStatus = ((Number) order.get("status")).intValue();
                if (orderStatus == 1 || orderStatus == 5 || orderStatus == 7) {
                    throw busy(OPEN_ORDERS_REMAIN);
                }
                knownOrders.put(order.get("user_id").toString(), order.get("id").toString());
            }
            Map<Object, Object> reservations = redis.opsForHash().entries("seckill:reservation:" + id);
            for (Map.Entry<Object, Object> reservation : reservations.entrySet()) {
                if (!Objects.equals(knownOrders.get(reservation.getKey().toString()), reservation.getValue().toString())) {
                    throw busy(UNKNOWN_RESERVATIONS);
                }
            }
            Set<String> purchasers = redis.opsForSet().members("seckill:order:" + id);
            if (purchasers != null && !knownOrders.keySet().containsAll(purchasers)) {
                throw busy(LEGACY_RESERVATIONS);
            }
            List<String> args = new ArrayList<>(orders.size() * 3 + 4);
            args.add(inventory.get("stock").toString());
            ZoneId zone = ZoneId.of(businessZone);
            args.add(Long.toString(((Timestamp) inventory.get("begin_time")).toLocalDateTime().atZone(zone).toEpochSecond()));
            args.add(Long.toString(((Timestamp) inventory.get("end_time")).toLocalDateTime().atZone(zone).toEpochSecond()));
            args.add(Integer.toString(orders.size()));
            for (Map<String, Object> order : orders) {
                args.add(order.get("user_id").toString());
                args.add(order.get("id").toString());
                int value = ((Number) order.get("status")).intValue();
                args.add(value == 4 || value == 6 ? "1" : "0");
            }
            Long result = redis.execute(REBUILD_SCRIPT, Arrays.asList("seckill:stock:" + id, "seckill:begin:" + id,
                    "seckill:end:" + id, "seckill:order:" + id, "seckill:reservation:" + id,
                    "seckill:enabled:" + id), args.toArray(new String[args.size()]));
            if (!Long.valueOf(1L).equals(result)) { throw busy(REDIS_REBUILD_PENDING); }
            jdbc.update("UPDATE tb_voucher_order SET stock_compensated=1 WHERE voucher_id=? AND status IN (4,6)", id);
            audit.record("INVENTORY_REPAIR", id, null, inventory.get("stock"));
            return null;
        });
    }

    private BusinessException busy(String message) {
        return new BusinessException("RECONCILIATION_PENDING", message, HttpStatus.CONFLICT);
    }
}
