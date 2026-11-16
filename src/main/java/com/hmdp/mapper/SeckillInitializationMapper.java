package com.hmdp.mapper;

import com.hmdp.entity.SeckillInitializationRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Persists activity initialization with the voucher creation transaction.
 * @author NeighborLife
 */
public interface SeckillInitializationMapper {
    /**
     * Enqueues a new activity.
     * @param record activity initialization data
     */
    @Insert("INSERT INTO tb_seckill_initialization (voucher_id, stock, begin_second, end_second) "
            + "VALUES (#{voucherId}, #{stock}, #{beginSecond}, #{endSecond})")
    void insert(SeckillInitializationRecord record);

    /**
     * Reads a bounded batch for initialization retries.
     * @return pending activity initialization records
     */
    @Select("SELECT voucher_id, stock, begin_second, end_second "
            + "FROM tb_seckill_initialization ORDER BY voucher_id LIMIT 100")
    List<SeckillInitializationRecord> findPending();

    /**
     * Removes a successfully initialized record.
     * @param voucherId activity identity
     */
    @Delete("DELETE FROM tb_seckill_initialization WHERE voucher_id = #{voucherId}")
    void delete(@Param("voucherId") Long voucherId);
}
