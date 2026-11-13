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

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);//这里的value需要的是string类型，用JSONUtil转为JSONStr
    }

    public void setWithLogicalExpire(String key,Object value, Long time, TimeUnit unit){

        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));

    }
        //类型不确定全用泛型                        key的前缀       针对什么类型的数据
    public <R,ID> R queryWithPassThrough(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){

        String key = keyPrefix + id;
        //1.从redis查询商铺信息的缓存
        String json = stringRedisTemplate.opsForValue().get(key);//此处为普通json

        //2.判断是否存在    + 解决缓存穿透(缓存null值或布隆过滤) 此处缓存null值
        if (StrUtil.isNotBlank(json)){  //只有有值的字符串才为true
            //3.存在，直接返回
            return JSONUtil.toBean(json,type);
        }

        //判断命中的值是否为空值 ""
        if (json != null) {   //此处只剩 null和 ""
            return null ;//为""空值，返回n一个错误信息
        }


        //4.缓存里不存在，根据id查询数据库
        R r = dbFallback.apply(id);       //这里应为一段逻辑，从调用着那里传递过来，用函数function有参有返回值

        //5.不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),CACHE_NULL_TTL, TimeUnit.MINUTES);//设置缓存超时时间
            //返回错误信息
            return null;
        }

        //6.存在，写入redis
       this.set(key,r,time,unit);
        //7.返回商铺信息
        return r;
    }



    //================================================
    //逻辑过期解决缓存击穿    未命中缓存直接返回null ，命中缓存检查是否过期，过期->获取互斥锁，获取锁成功->开启独立线程重建新缓存
    //                                                                              ->自己返回商铺数据

    //缓存重建线程池
    private static final ExecutorService CACHE_REBUTID_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        //1.从redis查询商铺信息的缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，直接返回
            return null;
        }

        //4.命中，需要先把json反序列化薇对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);//这里在反序列化时，因为不知道data是什么类型，就转为Object类型
        JSONObject data = (JSONObject) redisData.getData();//这里强转为JSONObject在序列化回去
        R r = JSONUtil.toBean(data, type);//这里再反序列化就能把json数据变为类对象的数据
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) { //过期时间是否在当前时间之后
            //5.1未过期，直接返回商铺信息
            return r;
        }

        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2.判断是否获取锁成功
        if (isLock) {
            //6.3成功，开启独立线程，实现缓存重建， 交给线程池里面的线程完成
            CACHE_REBUTID_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4,未成功，返回过期的商铺信息
        return r;

    }

    //尝试获取互斥锁  //基于redis里面的setnx实现，这里则为setIfAbsent ：如果不存在，才增加这个key
    private  boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里的返回值是Boolean包装,返回值可能是  true false null  ，是null会空指针异常
        return BooleanUtil.isTrue(flag);//这里不是true，全返回false，解决空指针
    }

    //释放互斥锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }




}
