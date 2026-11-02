package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jdk.nashorn.internal.scripts.JS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.security.auth.login.Configuration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Autowired
    private ResourceLoader resourceLoader;
    @Autowired
    private CacheClient cacheClient;

    /*
    给商铺加缓存，访问时先从redis缓存里面查询，没有这个缓存才去数据库里查询。并把这个数据加入缓存
    提高查询速度
     */
    @Override
    public Result queryById(Long id) {

//        String key = "CACHE_SHOP_KEY"+id;
//        //1.从redis查询商铺信息的缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2.判断是否存在    + 解决缓存穿透(缓存null值或布隆过滤) 此处缓存null值
//        if (StrUtil.isNotBlank(shopJson)){  //只有有值的字符串才为true
//            //3.存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        //判断命中的值是否为空值 ""
//        if (shopJson != null) {   //此处只剩 null和 ""
//            return Result.fail("店铺不存在!");
//        }
//        //4.缓存里不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //5.不存在，返回错误
//        if (shop == null) {
//        //将空值写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),CACHE_NULL_TTL, TimeUnit.MINUTES);//设置缓存超时时间
//        //返回错误信息
//            return Result.fail("店铺不存在!");
//        }
//        //6.存在，写入redis
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存超时时间
//        //7.返回商铺信息
//        return Result.ok(shop);

    //缓存穿透 ,这类中定义了queryWithPassThrough方法可以包含上面的功能完成
    //    Shop shop = queryWithPassThrough(id);

        //解决缓存穿透，用CacheClient缓存工具类里的方法                              //lambda表达式，函数式编程
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

//        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        if (shop == null) {
//            return  Result.fail("店铺不存在!");
//        }

        //返回
        System.out.println(shop.toString());
        return Result.ok(shop);

    }

    //================================================
    //逻辑过期解决缓存击穿    未命中缓存直接返回null ，命中缓存检查是否过期，过期->获取互斥锁，获取锁成功->开启独立线程重建新缓存
    //                                                                              ->自己返回商铺数据

    /*//缓存重建线程池
    private static final ExecutorService CACHE_REBUTID_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {

        String key = "CACHE_SHOP_KEY" + id;
        //1.从redis查询商铺信息的缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，直接返回
            return null;
        }

        //4.命中，需要先把json反序列化薇对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);//这里在反序列化时，因为不知道data是什么类型，就转为Object类型
        JSONObject data = (JSONObject) redisData.getData();//这里强转为JSONObject在序列化回去
        Shop shop = JSONUtil.toBean(data, Shop.class);//这里再反序列化就能把json数据变为类对象的数据
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) { //过期时间是否在当前时间之后
            //5.1未过期，直接返回商铺信息
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4,未成功，返回过期的商铺信息
        return shop;

    }*/



    //===================================
    //互斥锁解决缓存击穿
    /*public Shop queryWithMutex(Long id){

        String key = "CACHE_SHOP_KEY"+id;
        //1.从redis查询商铺信息的缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在    + 解决缓存穿透(缓存null值或布隆过滤) 此处缓存null值
        if (StrUtil.isNotBlank(shopJson)){  //只有有值的字符串才为true
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的值是否为空值 ""
        if (shopJson != null) {   //此处只剩 null和 ""
            //返回一个错误信息
            return null ;
        }

        //4.实现缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //4.1获取互斥锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3获取失败，休眠并重试获取
                Thread.sleep(50);//这里有异常，用try finally ，可以就算有异常，也释放锁
                queryWithMutex(id);//这里是递归，又回到了最上面的方法开始执行
            }


            //4.获取成功，根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);//模拟重建的延迟，延迟越高，出现的并发线程越多

            //5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),CACHE_NULL_TTL, TimeUnit.MINUTES);//设置缓存超时时间
                //返回错误信息
                return null;
            }

            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存超时时间
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);

        }

        //8.返回商铺信息
        return shop;
    }*/

    //============================================================
    //缓存穿透解决 queryWithPassThrough
    /*public Shop queryWithPassThrough(Long id){

        String key = "CACHE_SHOP_KEY"+id;
        //1.从redis查询商铺信息的缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在    + 解决缓存穿透(缓存null值或布隆过滤) 此处缓存null值
        if (StrUtil.isNotBlank(shopJson)){  //只有有值的字符串才为true
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的值是否为空值 ""
        if (shopJson != null) {   //此处只剩 null和 ""
            return null;
        }


        //4.缓存里不存在，根据id查询数据库
        Shop shop = getById(id);

        //5.不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(""),CACHE_NULL_TTL, TimeUnit.MINUTES);//设置缓存超时时间
            //返回错误信息
            return null;
        }

        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存超时时间

        //7.返回商铺信息
        return shop;
    }*/



    //尝试获取互斥锁  //基于redis里面的setnx实现，这里则为setIfAbsent ：如果不存在，才增加这个key
    /*private  boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里的返回值是Boolean包装,返回值可能是  true false null  ，是null会空指针异常
        return BooleanUtil.isTrue(flag);//这里不是true，全返回false，解决空指针
    }

    //释放互斥锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }*/


    public void saveShop2Redis(Long id,Long expireSeconds ) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟去数据库查询数据时的延迟
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();//这个是包装类，为了不改动代码的情况下使shop类有expireTime这个成员变量
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set("CACHE_SHOP_KEY"+id,JSONUtil.toJsonStr(redisData));
    }

//==================================================================

    @Override
    @Transactional
    public Result update(Shop shop) {

        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.先更新数据库
        updateById(shop);//mybatis-plus的方法，自动拿到已在实体类表明的主键id去根据id去数据库更新
        //2.后删除缓存
        stringRedisTemplate.delete("CACHE_SHOP_KEY"+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        //1.判断 是否需要根据坐标查询
        if (x == null || y == null) {
            //不需要坐标查询 ，根据类型按数据库分页查询
            Page<Shop> page =query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;//以及事先存入商铺的地理位置
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(                                             // GEOSEARCH key BYLONAT x,y BYRADIUS 5000 m WITHDISTANCE
                        key,
                        GeoReference.fromCoordinate(x, y),   //中心点坐标，x = 经度，y = 纬度（用户当前位置）；从这个点向外搜索
                        new Distance(5000),   //默认：米                        //includeDistance()：用户的
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                        //.includeDistance()：返回结果里附带每个店铺到中心点的距离**，后面我们要拿这个 distance 给前端展示距离
                        // limit(end)：拿到全部前end条，  end：永远从角标0开始
                );

        // 4.解析出id
        if (results == null) {
            //如果指定距离内没有店铺，返回空集合
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() <= from){
            //如果分页查询的开始位置 > 满足条件的店铺的总数,返回空集合：表示没有下一页
            return  Result.ok(Collections.emptyList());
        }

        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());



        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr)); //ids ： List<店铺的id>
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance); //distanceMap :Map<店铺的id ，距离 >
        });

        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        // 6.返回
        return Result.ok(shops);
    }
}
