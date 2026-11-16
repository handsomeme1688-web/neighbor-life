package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {
    /**
     * Queries metadata without cached inventory.
     * @param id coupon identity
     * @return metadata or null when absent
     */
    @org.apache.ibatis.annotations.Select("SELECT v.id,v.shop_id,v.title,v.sub_title,v.rules,v.pay_value,v.actual_value,"
            + "v.type,v.status,s.begin_time,s.end_time FROM tb_voucher v LEFT JOIN tb_seckill_voucher s "
            + "ON s.voucher_id=v.id WHERE v.id=#{id}")
    com.hmdp.dto.VoucherDetailDTO queryDetail(@Param("id") Long id);


    /**
     * 查询商铺当前可用优惠券。
     * @param shopId 商铺编号
     * @return 查询结果
     */
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
