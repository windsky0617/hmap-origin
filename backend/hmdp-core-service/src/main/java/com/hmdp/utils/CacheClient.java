package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // StringRedisTemplate 写入的是字符串，所以先把对象转成 JSON，再设置 Redis TTL。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期：先把原数据和过期时间装进 RedisData。
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入 Redis。过期时间在 JSON 内，这次写入没有设置 Redis TTL。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // R 是返回数据的类型，例如 Shop。ID 是主键类型，例如 Long。
    // keyPrefix 是 Redis 键的前缀，type 告诉 Hutool 要把 JSON 转成哪个类。
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        // 1. 从 Redis 查询缓存，例如 keyPrefix + id 得到 cache:shop:1。
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断读到的是非空 JSON、空值标记，还是根本没有这个键。
        if (StrUtil.isNotBlank(json)) {
            // 3. 其他缓存键正常命中时，直接按调用方指定的类型转换。
            if (!CACHE_SHOP_KEY.equals(keyPrefix)) {
                return JSONUtil.toBean(json, type);
            }

            // 普通店铺键预期存 Shop JSON。老预热可能误写了带 expireTime 的 RedisData。
            JSONObject cachedShop = JSONUtil.parseObj(json);
            if (!cachedShop.containsKey("expireTime")) {
                return JSONUtil.toBean(cachedShop, type);
            }
            // 识别到旧包装后，继续走第 4 步。查库完成后会用正确格式覆盖旧键。
        } else if (json != null) {
            // 写入空值标记时用的是 ""。命中后直接返回 null，省去一次查库。
            // ShopServiceImpl 收到 null 后，会返回“店铺不存在!”。
            return null;
        }

        // 4. Redis 没有这个键，或者普通店铺键里是旧的逻辑过期包装，按 ID 查数据库。
        // dbFallback 是调用方传进来的函数。店铺详情传入 this::getById。
        R result = dbFallback.apply(id);

        // 5. 数据库也查不到时，写入带短 TTL 的空串，避免同一 ID 反复查库。
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 查到数据后，写入普通 JSON 并设置 Redis TTL。
        set(key, result, time, unit);

        // 7. 把本次查到的数据返回给调用方。
        return result;
    }


    //================================================
    //逻辑过期解决缓存击穿    未命中缓存直接返回null ，命中缓存检查是否过期，过期->获取互斥锁，获取锁成功->开启独立线程重建新缓存
    //
    // 命中过期数据时，抢到锁的请求提交后台重建任务，当前请求仍然可以先返回旧值。
    // 这是缓存重建线程池。S5.1 的店铺详情仍走上面的普通 TTL 方法。
    private static final ExecutorService CACHE_REBUTID_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        // 1. 从 Redis 查询逻辑过期缓存。读取店铺逻辑过期缓存时应传入对应的逻辑键前缀。
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否读到了缓存数据。
        if (StrUtil.isBlank(json)) {
            // 3. 没有读到可用缓存时，当前方法直接返回 null。
            return null;
        }

        // 4. 先把外层 JSON 转成 RedisData，取出里面的 data 和 expireTime。
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // RedisData 的 data 字段声明为 Object。这里取出的是 JSONObject。
        JSONObject data = (JSONObject) redisData.getData();
        // 再按调用方传入的 type，把内层对象转成 Shop 等具体类型。
        R result = JSONUtil.toBean(data, type);

        // 5. 过期时间晚于当前时间，说明缓存还没过期。
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回缓存里的数据。
            return result;
        }

        // 5.2 已过期，尝试重建缓存。
        // 6.1 先按 ID 获取互斥锁，避免多个请求同时提交重建任务。
        String lockKey = LOCK_SHOP_KEY + id;
        boolean acquired = tryLock(lockKey);

        // 6.2 判断是否拿到锁。
        if (acquired) {
            // 6.3 拿到锁后，把查库和重写缓存交给线程池。
            CACHE_REBUTID_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库。
                    R refreshed = dbFallback.apply(id);
                    // 写入 Redis，给新数据设置新的逻辑过期时间。
                    setWithLogicalExpire(key, refreshed, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 后台任务结束时释放锁。
                    unlock(lockKey);
                }
            });
        }

        // 6.4 当前请求直接返回旧数据。拿到锁的请求也无需等后台任务完成。
        return result;
    }

    // 尝试获取互斥锁。setIfAbsent 只在键不存在时写入，并给锁设置 10 秒有效期。
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        // flag 是 Boolean 包装类型，可能为 null。
        // BooleanUtil.isTrue 只在 flag 为 true 时返回 true，避免直接拆箱引发空指针。
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁。
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}



