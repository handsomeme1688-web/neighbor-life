package com.hmdp.mapper;

import com.hmdp.entity.OrderEventRecord;
import com.hmdp.entity.VoucherOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Locks an order event in MySQL before selecting its terminal outcome.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
public interface OrderEventMapper {

    /**
     * 创建订单处理记录，保留已有记录的身份与终态。
     * @param order 包含订单、用户和优惠券编号的事件
     */
    @Insert("INSERT INTO tb_order_event (order_id, user_id, voucher_id) "
            + "VALUES (#{id}, #{userId}, #{voucherId}) "
            + "ON DUPLICATE KEY UPDATE order_id = order_id")
    void insertIfAbsent(VoucherOrder order);

    /**
     * 通过行锁读取订单处理记录。
     * @param orderId 订单编号
     * @return 处理记录，缺失时为空
     */
    @Select("SELECT order_id, user_id, voucher_id, outcome, compensated "
            + "FROM tb_order_event WHERE order_id = #{orderId} FOR UPDATE")
    OrderEventRecord lock(@Param("orderId") Long orderId);

    /**
     * 在落单或拒绝事务内记录最终处理结果。
     * @param orderId 订单编号
     * @param outcome 最终处理结果
     */
    @Update("UPDATE tb_order_event SET outcome = #{outcome} WHERE order_id = #{orderId}")
    void finish(@Param("orderId") Long orderId, @Param("outcome") String outcome);

    /**
     * 读取已拒绝但尚未完成Redis补偿的事件。
     * @param limit 批次大小
     * @return 查询结果
     */
    @Select("SELECT order_id, user_id, voucher_id, outcome, compensated FROM tb_order_event "
            + "WHERE outcome = 'REJECTED' AND compensated = 0 ORDER BY update_time LIMIT #{limit}")
    List<OrderEventRecord> findPendingCompensation(@Param("limit") int limit);

    /**
     * 标记失败事件已完成Redis补偿。
     * @param orderId 订单编号
     */
    @Update("UPDATE tb_order_event SET compensated = 1 "
            + "WHERE order_id = #{orderId} AND outcome = 'REJECTED'")
    void markCompensated(@Param("orderId") Long orderId);
}
