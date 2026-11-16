package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {
    /**
     * Creates a normal coupon for an owned shop.
     * @param voucher validated metadata
     */
    void addVoucher(Voucher voucher);
    /**
     * Queries cacheable coupon metadata.
     * @param id coupon identity
     * @return coupon details or missing result
     */
    Result queryDetail(Long id);
    /**
     * Updates owned coupon metadata and invalidates caches.
     * @param voucher whitelisted update
     * @return update result
     */
    Result updateVoucher(Voucher voucher);


    /**
     * 查询商铺当前可用优惠券。
     * @param shopId 商铺编号
     * @return 处理结果
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 事务创建秒杀券并在提交后初始化活动缓存。
     * @param voucher 优惠券及秒杀活动信息
     */
    void addSeckillVoucher(Voucher voucher);
}
