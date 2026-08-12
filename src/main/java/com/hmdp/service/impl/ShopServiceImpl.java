package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mq.ShopCacheInvalidationHandler;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
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


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopCacheInvalidationHandler shopCacheInvalidationHandler;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
       // Shop shop = queryWithPassThrough(id);
//       Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class,
//               this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                // id-> getById(id)
        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        // 7. 返回
        return Result.ok(shop);
    }

//    public Shop queryWithLogicalExpire(Long id) { // 封装缓存穿透的代码
//        // 1. 从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        // 2. 判断是否存在
//        if (StrUtil.isBlank(shopJson)) {
//            // 3. 缓存未命中，直接返回
//            return null;
//        }
//        // 4. 命中,需要判断过期时间
//        // 需要先把json反序列化成对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 5. 判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 5.1 未过期,直接返回店铺信息
//            return shop;
//        }
//        // 5.2 已过期,需要缓存重建
//        // 6. 重建缓存
//        // 6.1 获取互斥锁
//         String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        // 6.2 判断是否获取锁成功
//        if (isLock) {
//            // TODO 6.3 成功,开启独立线程,开始缓存重建
//            // 用一个线程池,不要自己去写一个线程
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 重建缓存
//                    this.saveShop2Redis(id, 20L);
//                    // this.方法代表调用本对象的方法也就是下面那个方法
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    // 释放锁
//                    unLock(lockKey);
//                }
//            });
//        }
//        // 6.4 无论是否成功,返回过期的商铺信息
//        // 7. 返回
//        return shop;
//    }




    /*public Shop queryWithMutex(Long id) { // 封装缓存穿透的代码
        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 缓存命中，直接返回
            Shop shop = BeanUtil.toBean(shopJson, Shop.class); // 反序列化成java对象返回
            return shop;
        }
        // TODO 判断命中的是否是空值
        if (shopJson != null) { // 是空字符串
            return null;
        }
        // TODO 4.实现缓存重建
        // 4.1. 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);// 和缓存的key不是一个key
            // 4.2. 判断是否获取成功
            if (!isLock){
                // 4.3. 如果失败,则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }


            // 4. 如果成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延迟
            Thread.sleep(200);
            // 5. 不存在，返回404
            if (shop == null) {
                // TODO 将空值写入redis
                stringRedisTemplate.opsForValue().
                        set(CACHE_SHOP_KEY + id, "",
                                RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 然后返回错误信息
                return null;
            }
            // 6. 存在，写入redis

            stringRedisTemplate.opsForValue().
                    set(CACHE_SHOP_KEY + id,      // 添加过期时间
                            JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7. 释放互斥锁
            unLock(lockKey);
        }

        // 8. 返回
        return shop;
    }*/




//    public Shop queryWithPassThrough(Long id) { // 封装缓存穿透的代码
//        // 1. 从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        // 2. 判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3. 缓存命中，直接返回
//            Shop shop = BeanUtil.toBean(shopJson, Shop.class); // 反序列化成java对象返回
//            return shop;
//        }
//        // TODO 判断命中的是否是空值
//        if (shopJson != null) { // 是空字符串
//            return null;
//        }
//        // 4. 缓存未命中，根据id查询数据库
//        Shop shop = getById(id);
//        // 5. 不存在，返回404
//        if (shop == null) {
//            // TODO 将空值写入redis
//            stringRedisTemplate.opsForValue().
//                    set(CACHE_SHOP_KEY + id, "",
//                            RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            // 然后返回错误信息
//            return null;
//        }
//        // 6. 存在，写入redis
//
//        stringRedisTemplate.opsForValue().
//                set(CACHE_SHOP_KEY + id,      // 添加过期时间
//                        JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        // 7. 返回
//        return shop;
//    }

//    private boolean tryLock(String key) { // 锁就是redis里存储的一个key
//        // 尝试获取锁
//        Boolean flag = stringRedisTemplate.opsForValue().
//                setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//                // 就是setnx
//        // 转成基本类型返回
//        return BooleanUtil.isTrue(flag);
//
//    }
//    // 释放锁
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }

//    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        // 1. 查询店铺数据
//        Shop shop = getById(id);
//        // 模拟缓存延迟
//        Thread.sleep(200);
//        // 2. 封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);                  // 在当前时间基础上加多少秒
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        // 3. 写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,
//                JSONUtil.toJsonStr(redisData)); // 不是写shop而是写redisData
//    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        // 对id做一个判断
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在");
        }
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
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
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















