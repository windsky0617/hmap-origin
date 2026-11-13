package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /*
    全局唯一 ID 生成策略：
    UUID
    Redis 自增
    snowflake 算法
    数据库自增

    Redis 自增 ID 策略：
    每天一个 key，方便统计订单量
    ID 构造是 时间戳 + 计数器
    * */

    /**
     * 开始时间戳，这里的是自定义的，都是基于现在距离Epoch所经历的秒数
     *
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;//总距离时间-自定义的开始时间 = 现在距离自定义的开始时间已经历的秒数

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        //这里用redis的increase自增长 ，每调用一次这个方法，Redis 里面这个 key 对应的 value+1，并且返回【自增之后最新的值】给count
        //Redis INCR 规则：
        //如果这个 key不存在，Redis 会先把这个 key 的 value 初始化为 0，然后 + 1，返回结果是 1
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //将来第二天后date就不一样了，所以拼接的key就不一样，可实现每天更新这个序列号

        // 3.拼接并返回 此处直接返回long类型的数字，在底层就是64位二进制数
        return timestamp << COUNT_BITS | count;

        //拼接过程：
//        00000000 00000000 00000000 01100100 00000000 00000000 00000000 00000000   timestamp原本32位，左移32位，所以右边+32个0
//        00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000001   count = 1，左边补齐0对齐
//                ------------------------------------------------------------------------      或运算拼接
//        00000000 00000000 00000000 01100100 00000000 00000000 00000000 00000001

    }


}
