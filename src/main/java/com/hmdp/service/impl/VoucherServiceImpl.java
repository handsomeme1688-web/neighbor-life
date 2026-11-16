package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.SeckillInitializationRecord;
import com.hmdp.mapper.SeckillInitializationMapper;
import com.hmdp.mq.SeckillInitializationHandler;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;


/**
 * VoucherServiceImpl component.
 *
 * @author NeighborLife
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {
    private static final String SHOP_EXISTS_SQL = "SELECT COUNT(*) FROM tb_shop WHERE id=?";
    private static final String VOUCHER_NOT_FOUND_MESSAGE = "优惠券不存在";
    private static final String VOUCHER_ORDER_COUNT_SQL = "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?";


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillInitializationMapper initializationMapper;

    @Resource
    private SeckillInitializationHandler initializationHandler;

    @Value("${app.business-zone:Asia/Shanghai}")
    private String businessZone;
    @Resource
    private com.hmdp.security.AccessService access;
    @Resource
    private com.hmdp.security.AuditService audit;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Resource
    private com.hmdp.mapper.CacheInvalidationMapper cacheInvalidationMapper;
    @Resource
    private com.hmdp.mq.ShopCacheInvalidationHandler invalidationHandler;
    @Resource
    private com.hmdp.utils.CacheClient cacheClient;
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<Long, com.hmdp.dto.VoucherDetailDTO> voucherLocalCache;

    private void requireShop(Long shopId) {
        if (shopId == null || jdbc.queryForObject(SHOP_EXISTS_SQL, Long.class, shopId) == 0) {
            throw new IllegalArgumentException("商铺不存在");
        }
        access.requireShop(shopId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addVoucher(Voucher voucher) {
        requireShop(voucher.getShopId());
        voucher.setId(null).setType(0).setStatus(1);
        save(voucher);
        audit.record("VOUCHER_CREATE", voucher.getId(), null, voucher);
    }

    @Override
    public Result queryDetail(Long id) {
        com.hmdp.dto.VoucherDetailDTO detail = voucherLocalCache.getIfPresent(id);
        if (detail == null) {
            detail = cacheClient.queryWithLogicalExpire("cache:voucher:", id, com.hmdp.dto.VoucherDetailDTO.class,
                    value -> baseMapper.queryDetail(value), 10L, java.util.concurrent.TimeUnit.MINUTES);
            if (detail == null) { return Result.fail(VOUCHER_NOT_FOUND_MESSAGE); }
            voucherLocalCache.put(id, detail);
        }
        return Result.ok(detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateVoucher(Voucher voucher) {
        Voucher before = getById(voucher.getId());
        if (before == null) { return Result.fail(VOUCHER_NOT_FOUND_MESSAGE); }
        access.requireShop(before.getShopId());
        boolean priceChanged = voucher.getPayValue() != null && !voucher.getPayValue().equals(before.getPayValue());
        if (priceChanged && jdbc.queryForObject(VOUCHER_ORDER_COUNT_SQL,
                Long.class, voucher.getId()) > 0) {
            throw new IllegalArgumentException("已产生订单的优惠券请新建券调整价格");
        }
        updateById(voucher);
        audit.record("VOUCHER_UPDATE", voucher.getId(), before, getById(voucher.getId()));
        cacheInvalidationMapper.insertVoucher(voucher.getId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { invalidationHandler.invalidateVoucherAfterCommit(voucher.getId()); }
        });
        return Result.ok();
    }


    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        requireShop(voucher.getShopId());
        validateSeckillVoucher(voucher);
        voucher.setId(null).setType(1).setStatus(1);
        save(voucher);

        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(voucher.getId())
                .setStock(voucher.getStock())
                .setBeginTime(voucher.getBeginTime())
                .setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        ZoneId zone = ZoneId.of(businessZone);
        SeckillInitializationRecord initialization = new SeckillInitializationRecord();
        initialization.setVoucherId(voucher.getId());
        initialization.setStock(voucher.getStock());
        initialization.setBeginSecond(voucher.getBeginTime().atZone(zone).toEpochSecond());
        initialization.setEndSecond(voucher.getEndTime().atZone(zone).toEpochSecond());
        initializationMapper.insert(initialization);
        audit.record("SECKILL_CREATE", voucher.getId(), null, voucher);

        // Redis is initialized only after both database inserts commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                initializationHandler.initializeAfterCommit(initialization);
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

}
