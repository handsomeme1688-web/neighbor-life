package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        validateSeckillVoucher(voucher);
        save(voucher);

        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(voucher.getId())
                .setStock(voucher.getStock())
                .setBeginTime(voucher.getBeginTime())
                .setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // Redis is initialized only after both database inserts commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                initializeSeckillInRedis(voucher);
            }
        });
    }

    private void validateSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() < 1) {
            throw new IllegalArgumentException("秒杀库存必须大于 0");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            throw new IllegalArgumentException("秒杀结束时间必须晚于开始时间");
        }
    }

    private void initializeSeckillInRedis(Voucher voucher) {
        String voucherId = voucher.getId().toString();
        ZoneId zone = ZoneId.systemDefault();
        stringRedisTemplate.opsForValue().set(
                SECKILL_STOCK_KEY + voucherId, voucher.getStock().toString());
        stringRedisTemplate.opsForValue().set(
                SECKILL_BEGIN_KEY + voucherId,
                Long.toString(voucher.getBeginTime().atZone(zone).toEpochSecond()));
        stringRedisTemplate.opsForValue().set(
                SECKILL_END_KEY + voucherId,
                Long.toString(voucher.getEndTime().atZone(zone).toEpochSecond()));
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
    }
}
