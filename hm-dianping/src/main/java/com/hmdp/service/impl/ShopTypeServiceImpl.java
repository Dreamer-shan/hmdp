package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopTypeString() {
        String shopTypeString = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY);
        if (StrUtil.isNotBlank(shopTypeString)) {
            //toList
            List<ShopType> typeList = JSONUtil.toList(shopTypeString, ShopType.class);
            return Result.ok(typeList);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (CollectionUtil.isEmpty(typeList)) {
            return Result.fail("分类不存在");
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(typeList));
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, RedisConstants.CACHE_SHOP_TYPE_LIST_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }

    @Override
    public Result queryShopTypeList() {
        //range 0~-1  取出全部
        List<String> shopTypeListString = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, 0, -1);
        // final List<ShopType> typeList = new ArrayList<>();
        List<ShopType> typeList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(shopTypeListString)) {
            List<ShopType> finalTypeList = typeList;
            // lambda 表达式只能引用标记了 final 的外层局部变量，这就是说不能在 lambda 内部修改定义在域外的局部变量，否则会编译错误。
            // 或者把typeList使用final修饰,final关键字修饰一个引用类型变量时，该变量不能重新指向新的对象，但是其值本身可以改变
            shopTypeListString.forEach(shopType -> finalTypeList.add(JSONUtil.toBean(shopType, ShopType.class)));
            return Result.ok(typeList);
        }
        typeList = query().orderByAsc("sort").list();
        //存入redis
        typeList.forEach(shopType -> stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopType)));
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, RedisConstants.CACHE_SHOP_TYPE_LIST_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }

    @Override
    public Result queryShopTypeZset() {
        Set<String> shopTypes = stringRedisTemplate.opsForZSet().range(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, 0, -1);
        final List<ShopType> typeList = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(shopTypes)) {
            shopTypes.forEach(shopType -> typeList.add(JSONUtil.toBean(shopType, ShopType.class)));
            return Result.ok(typeList);
        }
        //final修饰，不能指向别的对象了
        // typeList = query().orderByAsc("sort").list();
        List<ShopType> typeList1 = query().orderByAsc("sort").list();
        if (CollectionUtil.isEmpty(typeList1)) {
            return Result.fail("分类不存在");
        }
        typeList1.forEach(shopType -> stringRedisTemplate.opsForZSet().add(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopType), shopType.getSort()));
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY, RedisConstants.CACHE_SHOP_TYPE_LIST_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList1);
    }
}
