package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    /**
     * Creates a shop and records the administrative audit.
     * @param shop validated shop metadata
     */
    void createShop(Shop shop);


    /**
     * 通过两级缓存查询商铺详情。
     * @param id 业务主键
     * @return 处理结果
     */
    Result queryById(Long id);

    /**
     * 更新商铺并持久化缓存失效事件。
     * @param shop 商铺更新信息
     * @return 处理结果
     */
    Result update(Shop shop);

    /**
     * 按商铺类型分页查询，可附带距离排序。
     * @param typeId 商铺类型编号
     * @param current 从1开始的页码
     * @param x 经度，可为空
     * @param y 纬度，可为空
     * @return 处理结果
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
