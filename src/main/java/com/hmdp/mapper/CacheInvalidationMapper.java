package com.hmdp.mapper;

import com.hmdp.entity.CacheInvalidationRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Durable cache invalidation outbox; duplicate deliveries are safe.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
public interface CacheInvalidationMapper {

    /**
     * 在商铺更新事务内持久化缓存失效事件。
     * @param shopId 商铺编号
     */
    @Insert("INSERT INTO tb_cache_invalidation (shop_id) VALUES (#{shopId})")
    void insert(@Param("shopId") Long shopId);

    /**
     * Enqueues a coupon cache invalidation.
     * @param voucherId coupon identity
     */
    @Insert("INSERT INTO tb_cache_invalidation(shop_id,resource_type) VALUES (#{voucherId},'VOUCHER')")
    void insertVoucher(@Param("voucherId") Long voucherId);

    /**
     * 按编号顺序读取有界的待投递事件批次。
     * @return 查询结果
     */
    @Select("SELECT id, shop_id, resource_type FROM tb_cache_invalidation ORDER BY id LIMIT 100")
    List<CacheInvalidationRecord> findPending();

    /**
     * 删除已获得Kafka确认的缓存失效事件。
     * @param id 业务主键
     */
    @Delete("DELETE FROM tb_cache_invalidation WHERE id = #{id}")
    void delete(@Param("id") Long id);
}
