package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Objects;
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

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        // Shop shop = queryWithPassThrough(id);

        //缓存击穿
        Shop shop = queryWithMutex(id);
        if (Objects.isNull(shop)){
            return Result.fail("该店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
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
