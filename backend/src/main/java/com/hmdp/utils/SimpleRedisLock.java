package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    //线程池的线程有限，用原始Threadid会重复，加UUID来实现线程标识唯一
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+ "-";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId =ID_PREFIX + Thread.currentThread().getId();

        //获取锁，redis里的setnx                                                         value要String类型，这里把threadId转为一个字符串
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId, timeoutSec, TimeUnit.SECONDS);
        //这里返回要一个基本类型，包装类可能有true，false，null（会空指针），
        return Boolean.TRUE.equals(success);

    }

    @Override
    public void unlock() {  //解决不同线程锁误删问题
        //获取线程标识
        String threadId =ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的线程标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //判断标识是否一致
        if (threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
