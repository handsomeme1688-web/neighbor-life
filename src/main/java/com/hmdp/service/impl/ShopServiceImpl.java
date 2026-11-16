package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.CacheInvalidationMapper;
import com.hmdp.mq.ShopCacheInvalidationHandler;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final String SHOP_TYPE_EXISTS_SQL = "SELECT COUNT(*) FROM tb_shop_type WHERE id=?";


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopCacheInvalidationHandler shopCacheInvalidationHandler;

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private CacheInvalidationMapper cacheInvalidationMapper;
    @Resource
    private com.hmdp.security.AccessService access;
    @Resource
    private com.hmdp.security.AuditService audit;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createShop(Shop shop) {
        access.requireAdmin();
        if (jdbc.queryForObject(SHOP_TYPE_EXISTS_SQL, Long.class, shop.getTypeId()) == 0) {
            throw new IllegalArgumentException("商铺类型不存在");
        }
        shop.setId(null).setSold(0).setComments(0).setScore(0);
        save(shop);
        audit.record("SHOP_CREATE", shop.getId(), null, shop);
    }


    @Override
    public Result queryById(Long id) {
        if (id == null) {
            return Result.fail("店铺编号不能为空");
        }
        Shop localShop = shopLocalCache.getIfPresent(id);
        if (localShop != null) {
            return Result.ok(localShop);
        }
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        shopLocalCache.put(id, shop);
        // 7. 返回
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        // 对id做一个判断
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        access.requireShop(id);
        Shop before = getById(id);
        if ((shop.getX() == null) != (shop.getY() == null)) {
            throw new IllegalArgumentException("经纬度需要一起提交");
        }
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在");
        }
        audit.record("SHOP_UPDATE", id, before, getById(id));
        cacheInvalidationMapper.insert(id);
        // Delete only after commit, so a rollback cannot leave stale cache.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                shopCacheInvalidationHandler.invalidateAfterCommit(id);
            }
        });
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 无需坐标查询，按照数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3. 查询redis,按照距离排序,分页. 结果: shopId, distance
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4. 解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // 截取从from到end的部分
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(
                result -> {
                    // 获取店铺id
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    // 获取店铺距离
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr, distance);
                }
        );
        // 5. 根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6. 返回
        return Result.ok(shops);
    }
}
