package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author hongyuan.shan
 * @date 2023/02/25 15:41
 * @description
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR=
            new ThreadPoolExecutor(10,10,0,
                    TimeUnit.MINUTES,new LinkedBlockingDeque<>(100));

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }



    /**
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack 函数
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return    R表示返回值   类型不确定的都要用泛型
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if (json != null){
            return null;
        }
        R apply = dbFallBack.apply(id);
        if (apply == null){
            stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, apply, time, unit);
        return apply;
    }


    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit){
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //不存在  直接返回
        if (StrUtil.isBlank(json)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //为什么不能强转
        // Shop shop = (Shop) redisData.getData();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //没过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //直接返回店铺信息
            return r;
        }
        //过期了,重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //获取锁失败, 直接返回
        if (!tryLock(lockKey)){
            return r;
        }

        CACHE_REBUILD_EXECUTOR.execute(() -> {
            try {
                R r1 = dbFallBack.apply(id);
                this.setWithLogicalExpire(key, r1, time, unit);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unLock(lockKey);
            }
        });
        return r;
    }

    private Boolean tryLock(String key){
        Boolean ret = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ret);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
