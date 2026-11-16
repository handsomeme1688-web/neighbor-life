package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 原子预扣库存并返回异步订单编号。
     * @param voucherId 优惠券编号
     * @return 处理结果
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 在处理记录锁保护下事务扣减库存并创建订单。
     * @param voucherOrder 包含订单、用户和优惠券编号的事件
     */
    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 查询属于当前用户的订单。
     * @param orderId 订单编号
     * @return 处理结果
     */
    Result queryOrder(Long orderId);

    /**
     * 在支付期限内将当前用户的未支付订单改为已支付。
     * @param orderId 订单编号
     * @return 处理结果
     */
    Result payOrder(Long orderId);

    /**
     * 条件关闭已到期的未支付订单并恢复数据库库存。
     * @param orderId 订单编号
     * @return 是否完成目标状态变更
     */
    boolean closeUnpaidOrder(Long orderId);

    /**
     * 幂等恢复已取消订单对应的Redis库存。
     * @param voucherOrder 包含订单、用户和优惠券编号的事件
     */
    void compensateCanceledOrder(VoucherOrder voucherOrder);

    /**
     * 持久化失败事件的终态，串行协调落单与补偿。
     * @param voucherOrder 包含订单、用户和优惠券编号的事件
     * @return 是否完成目标状态变更
     */
    boolean rejectFailedOrder(VoucherOrder voucherOrder);

    /**
     * 释放属于该失败订单的Redis预扣记录。
     * @param voucherOrder 包含订单、用户和优惠券编号的事件
     */
    void compensateFailedOrder(VoucherOrder voucherOrder);
}
