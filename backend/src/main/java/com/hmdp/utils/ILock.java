package com.hmdp.utils;

public interface ILock {


    //获取锁
    //timeoutSec 锁持有的超时时间，过期自动释放
    //return true代表获取成功，false代表获取失败
    boolean tryLock(long timeoutSec);

    void unlock();
}
