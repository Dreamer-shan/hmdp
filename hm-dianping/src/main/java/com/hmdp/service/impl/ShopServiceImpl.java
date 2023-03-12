package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR=
            new ThreadPoolExecutor(10,10,0,
                    TimeUnit.MINUTES,new LinkedBlockingDeque<>(100));

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        // Shop shop = queryWithPassThrough(id);
        //传一个匿名函数
        // cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        //互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        if (Objects.isNull(shop)){
            return Result.fail("该店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicalExpire(Long id){
        String shopString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //不存在  直接返回
        if (StrUtil.isBlank(shopString)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopString, RedisData.class);
        //为什么不能强转
        // Shop shop = (Shop) redisData.getData();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //没过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //直接返回店铺信息
            return shop;
        }
        //过期了,重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获取锁失败, 直接返回
        if (!tryLock(lockKey)){
            return shop;
        }
        shopString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopString)){
             redisData = JSONUtil.toBean(shopString, RedisData.class);
             shop = (Shop) redisData.getData();
             //没过期
             if (redisData.getExpireTime().isAfter(LocalDateTime.now())){
                 return shop;
             }
        }
        CACHE_REBUILD_EXECUTOR.execute(() -> {
            try {
                saveShop2Redis(id, 20L);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unLock(lockKey);
            }
        });
        return shop;
    }

    //预热
    public void saveShop2Redis(Long id, Long expire){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    public Shop queryWithMutex(Long id){
        //先redis
        String shopString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopString)){
            Shop shop = JSONUtil.toBean(shopString, Shop.class);
            return shop;
        }
        //如果是穿透情况, redis返回是"", 此时也去查mysql也查不到东西,所以直接返回,不要去请求数据库
        if ("".equals(shopString)){
            return null;
        }
        String lockKey = null;
        Shop shop = null;
        try {
            //实现缓存重建
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            if (!tryLock(lockKey)){
                //失败,休眠后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功，根据id查询数据库
            shop = getById(id);
            //模拟重建所需时间
            Thread.sleep(200);
            if (Objects.isNull(shop)){
                //将空值写入redis解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally {
            unLock(lockKey);
        }
        return shop;
    }


    public Shop queryWithPassThrough(Long id){
        //先redis
        String shopString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopString)){
            Shop shop = JSONUtil.toBean(shopString, Shop.class);
            return shop;
        }
        //如果是穿透情况, redis返回是"", 此时也去查mysql也查不到东西，所以直接返回,不要去请求数据库
        if ("".equals(shopString)){
            return null;
        }
        // redis不存在,查mysql
        Shop shop = getById(id);
        if (Objects.isNull(shop)){
            //将空值写入redis解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (Objects.isNull(shop.getId())){
            return Result.fail("店铺id不能为空");
        }
        //先更新数据库,再删除缓存
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private Boolean tryLock(String key){
        Boolean ret = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ret);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
