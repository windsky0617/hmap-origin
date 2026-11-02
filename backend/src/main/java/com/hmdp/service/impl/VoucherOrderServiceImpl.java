package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.awt.dnd.DragGestureEvent;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;



    //DefaultRedisScript：SpringDataRedis封装的类，用来承载 Lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //静态代码块：类被加载的时候自动执行，只执行一次。适合一次性加载脚本。
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();//实例化一个 Lua 脚本容器对象
        //setLocation：指定 lua 脚本文件位置
        //ClassPathResource("seckill.lua")：去 resources 根目录下面读取 seckill.lua 文件
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);//脚本执行完返回的结果类型是long

    }



    // 1. 创建一个单线程线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        // 项目启动完成之后，提交任务到线程池,提交后线程池里的线程会执行任务类里面的方法
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //基于消息队列实现读取订单数据给兹线程执行
    private class VoucherOrderHandler implements Runnable{

        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //!!!!!!!!!!!!!!!!!要再redis提前创建stream.order消息队列，不然会一直循环报错
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//GROUP g1 c1
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//COUNT 1 BLOCK 2000
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())//STREAMS streams.order >
                                                        // >:只读取这个消费组的队列里，从来没有发给过任何消费者的新消息
                    );
                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    //这里用MapRecord，String是自动生成的消息的id，后面两个object是消息：key value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();//获取消息
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认  SACK stream.order g1 id                                  //队列名      消费者组名   消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true){
                try {
                    // 1.获取Pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//GROUP g1 c1
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0")) // 0表示获取pending-list中未确认的消息
                            // >:只读取这个消费组里，从来没有发给过任何消费者的【新消息】
                    );
                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()) {
                        // 如果获取失败，说明pending-list里没有已读取但未确认的异常消息，结束循环
                        break;
                    }
                    // 3.解析消息中的订单信息
                    //这里用MapRecord，String是自动生成的消息的id，后面两个object是消息：key value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();//获取消息
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认                                   //队列名      消费者组名   消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    //如果再抛异常会继续while循环
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");

        log.debug("测试是否拿到userId:{}",userId);
        log.debug("测试是否拿到vocherId:{}",voucherId);


        //1.执行lua脚本(消息队列版)
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );


        //2.判断结果是否为0
        //2.1不为0，代表没有购买资格
        int r = result.intValue();//long类型不能和int类型比较
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        //3.//获取代理对象，实现事务
        //解决方法：现在声明一个类成员变量proxy，在这里（主线程获取）初始化，把代理对象放到当前类里面
        proxy = (IVoucherOrderService) AopContext.currentProxy();//拿到当前实现类的代理对象

        //4.返回订单id
        return Result.ok(orderId);
    }



    //111从阻塞队列获取订单信息来实现
    /*//阻塞队列：当一个线程尝试从这个队列里获取元素，如果此时没有元素，这个线程就会被阻塞，直到有元素才唤醒并且获取元素
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 内部类：任务类
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息（阻塞队列，空的时候take会阻塞等待）
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        // 理论上不用加锁，因为已经在redis里做了并发判断，这里再加是兜底
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();//redisson的知识
        //3.判断是否获取成功
        if (!isLock){
            //获取失败
            log.error("不允许重复下单");
            return ;
        }


        try {
            //此处是线程池里的线程（子线程）去获取，是拿不到ThreadLocal里的代理对象
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }


    //111基于阻塞队列获取订单信息来实现
   /* @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        log.debug(String.valueOf(userId));
        log.debug(String.valueOf(voucherId));


        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );


        //2.判断结果是否为0
        //2.1不为0，代表没有购买资格
        int r = result.intValue();//long类型不能和int类型比较
        if (r != 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4用户id
        voucherOrder.setUserId(userId);
        //2.5秒杀券id
        voucherOrder.setVoucherId(voucherId);
        //2.6放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.//获取代理对象，实现事务
        //解决方法：现在声明一个类成员变量proxy，在这里（主线程获取）初始化，把代理对象放到当前类里面
         proxy = (IVoucherOrderService) AopContext.currentProxy();//拿到当前实现类的代理对象

        //4.返回订单id
        return Result.ok(orderId);





        ===================================================================================

        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.判断秒杀券是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("活动尚未开始！");
        }

        //3.判断秒杀券是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //未开始
            return Result.fail("活动已经结束！");
        }

        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        //秒杀券满足要求返回订单
        Long userId = UserHolder.getUser().getId();//先获取锁，提交了事务，再释放锁
        //synchronized加锁-------只能单机模式下实现一人一单，集群模式下每一个JVM都只有一个不同的锁监视器
*//* synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//拿到当前实现类的代理对象
            return proxy.createVoucherOrder(voucherId);
//            return this.createVoucherOrder(voucherId);//原来的是实现类本身掉用，而不是代理对象调用，就不能实现String事务管理
        }*//*


        //分布式锁
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId,stringRedisTemplate);
        redissonClient.getLock("lock:order:"+userId);
        boolean isLock = lock.tryLock(1200);
        //判断是否获取成功
        if (!isLock){
            //获取失败
            return Result.fail("一个用户只能下一单!");
        }


        try {
            //获取代理对象，实现事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//拿到当前实现类的代理对象
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单,并发安全问题：最开始是0，很多线程同时来，查到count都是0，都往下走去创建订单)
        Long userId = voucherOrder.getUserId();
        //使用synchronized (userId.toString().intern())加锁时
        //userId是 Long 类型。userId.toString()每次调用，都会新建一个 String 对象。
        //举例子：用户 id=1001
        //线程 1：userId.toString() → 新建字符串对象 s1="1001"
        //线程 2：userId.toString() → 新建字符串对象 s2="1001"
        //si,s2内容一样，但是是两个独立的对象
        //synchronized 锁的是对象地址，不是对象内容
        //String.intern()：去字符串常量池查找。================================
        //如果常量池里面已经存在这个字符串（内容相同），直接返回常量池里那个字符串对象的引用。
        //如果常量池没有，就把这个字符串放入常量池，返回常量池引用。
        //相同内容的字符串，调用 intern 之后，拿到的是同一个对象引用！

         {

            //5.1查询订单
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            //5.2判断是否存在
            if (count > 0) {
                log.error("用户已经购买过一次！");
                return ;
            }

                        //乐观锁：原本.eq("stock",voucher.getstock)，因为前面已经判断过了订单是否>0,这里只有库存>0就行了
             //现在改为.gt("stock", 0),两个并发线程确实都可以在逻辑上 “看到 stock>0”，
             // 但是它们不能在数据库里同时完成扣减；数据库对同一行的 update 是串行排队执行的。 ======行锁
            //5.扣减库存 ,实现解决超卖问题：+乐观锁（扣减库存时再次判断此时的库存和查询时库存数量是否相同） CAS法
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") //set stock = stock -1
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where id = ? and stock > 0
                    .update();

            if (!success) {
                log.error("库存不足！");
                return ;
            }

            //7..创建订单
            /*VoucherOrder voucherOrder = new VoucherOrder();
            //7.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //7.2用户id
            //从登录拦截器中获取用户的id
            voucherOrder.setUserId(userId);
            //7.3秒杀券id
            voucherOrder.setVoucherId(voucherOrder);*/
            //写入数据库
            save(voucherOrder);


        }
    }
}
