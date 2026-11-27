package com.hmdp.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

//================添加布隆过滤器=================
// 按店铺缓存券列表。列表快照保存券的展示信息，每次返回前再从独立库存键补上秒杀库存。
@Slf4j
@Component
public class VoucherListCache {
    // 例如店铺 1 的共享列表键是 cache:voucher-list:v1:1，多个服务实例都能读到。
    private static final String PREFIX = "cache:voucher-list:v1:";
    // 布隆过滤器登记有券的店铺 ID，用于在缓存未命中时快速排除一部分空查询。
    private static final String BLOOM = "bloom:voucher-list:shops:v1";//布隆过滤器的键
    // 保留列表元素的类型，让 Jackson 把 JSON 数组还原成 List<Voucher>。而不是List<LinkedHashMap>
    private static final TypeReference<List<Voucher>> LIST_TYPE = new TypeReference<>() {};

    // 每个服务实例各有一份本地缓存，最多存 2000 个店铺，每条从写入起保留 5 秒。
    private final Cache<Long, String> local = Caffeine.newBuilder()
            .maximumSize(2000)//表示这份缓存最多保留约 2000 个店铺条目。
            .expireAfterWrite(Duration.ofSeconds(5))//表示从写进去开始计时。第 4 秒有人读取，计时也不会重新开始。避免旧数据被热点无限续期。
            .build();
    private final VoucherMapper mapper;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final RBloomFilter<Long> shops;//布隆过滤器
    // 本实例完成店铺 ID 装载后才启用布隆过滤；volatile 让请求线程看到状态更新。
    private volatile boolean ready;//表示 "本实例已经完成店铺 ID 装载"，决定布隆过滤器能不能用于否定判断。

    public VoucherListCache(VoucherMapper mapper, ObjectMapper json,
                            StringRedisTemplate redis, RedissonClient redisson) {
        this.mapper = mapper;
        this.json = json;
        this.redis = redis;
        this.redisson = redisson;
        this.shops = redisson.getBloomFilter(BLOOM, LongCodec.INSTANCE);
    }

    // 应用就绪时Spring根据 @EventListener(ApplicationReadyEvent.class)调用 warmIndex()，从数据库加载已有券的店铺 ID。
    @EventListener(ApplicationReadyEvent.class)
    public void warmIndex() {
        try {

            //尝试初始化过滤器。两个参数表示预计登记 10 万个店铺 ID，目标误判率为 1%。
            shops.tryInit(100000, 0.01);// `tryInit` 也放在 try 里，失败就退化成 "没有索引、但请求仍能走加锁查库"
            for (Long id : mapper.listVoucherShopIds()) {//拿到有优惠券的店铺的id
                shops.add(id);
            }
            // 放在循环之后：初次装载中途失败时，请求仍可继续走加锁查库。
            //布隆过滤器没装完就拿来判断 "不存在" 会误杀真实店铺所以 `ready` 一定要等全量写完才置真。
            ready = true;
        } catch (RuntimeException e) {
            log.warn("券店铺索引未就绪，冷读将使用带锁的数据库查询", e);
        }
    }

    // 新增券时，在事务提交前登记店铺 ID进过滤器。若事务回滚，该店仍可能被判断为有券，最终以实际查询结果为准。
    public void includeShop(Long shopId) {
        shops.tryInit(100000, 0.01);
        shops.add(shopId);
    }

    public void invalidate(Long shopId) {
        // 删掉本实例的本地快照和 Redis 的共享快照。
        // 删不掉别的实例的 Caffeine，其他实例最多脏 5 秒：已有的本地快照按各自的 TTL 到期。
        local.invalidate(shopId);
        redis.delete(PREFIX + shopId);
    }

    public List<Voucher> list(Long shopId) {
        // 1. 先按店铺 ID 查查本机的 Caffeine，命中就省去一次共享列表查询。
        String snapshot = local.getIfPresent(shopId);
        if (snapshot == null) {
            // 2. 本地未命中，去 Redis 找列表；必要时由 readShared 加锁查数据库。
            snapshot = readShared(shopId);// 走 Redis / 锁 / DB
            if (snapshot == null) {
                // 布隆过滤器已排除这个店铺，直接返回空列表。
                return List.of();
            }
            // 将拿到的 JSON 快照放进本地缓存。空列表的 JSON（[]）也能缓存。
            local.put(shopId, snapshot);
        }

        // 本机命中直接返回
        try {
            // 3. 每次从快照还原出本次请求自己的券对象，再补库存。缓存中的 JSON 不会被修改。
            List<Voucher> result = json.readValue(snapshot, LIST_TYPE);
            attachStock(result);
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取券列表缓存", e);
        }
    }

    //查共享 Redis
    private String readShared(Long shopId) {
        String key = PREFIX + shopId;
        String hit = redis.opsForValue().get(key);
        // Redis 中的 [] 表示已查过且列表为空,零张券，也算缓存命中。
        if (hit != null) {
            //如果其他服务实例已经写过列表，`hit != null`，马上就能把 JSON 返回给 list()。
            return hit;
        }

        //假设 Redis 也没有这个键，继续往下走:问布隆过滤器，有没有必要继续查？
        if (ready && shops.isExists() && !shops.contains(shopId)) {
            //“本实例已经装载完索引，Redis 里的过滤器也还存在，而且过滤器确定这个店铺 ID 没登记过，那就直接结束。”
            return null;
        }

        // 同一家店的请求使用同一把重建锁，不同店铺可以分别回填。
        RLock lock = redisson.getLock("lock:" + key);
        try {
            // 最多等锁 2 秒。超时后再读 Redis，看看持锁的请求是否已经回填。
            if (!lock.tryLock(2, TimeUnit.SECONDS)) {
                hit = redis.opsForValue().get(key);
                if (hit == null) {
                    throw new IllegalStateException("等待券列表回填超时");
                }
                //读到了，返回
                return hit;
            }
        } catch (InterruptedException e) {
            // 等锁被打断时恢复中断标记，让上层仍能识别中断。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待券列表回填被中断", e);
        }

        //查数据库，保存券列表
        try {
            // 已经拿到锁，再读一次：排队期间，前一个请求可能刚写好这家店的券列表。
            hit = redis.opsForValue().get(key);
            if (hit != null) {
                return hit;
            }

            List<Voucher> rows = mapper.queryVoucherOfShop(shopId);
            // 从展示快照中移除库存数值，避免本地或 Redis 命中旧快照时返回过时库存。
            // 把这批 Java 对象的 stock 设为 null。这行只改本次查询得到的内存对象，不会执行 SQL 更新，也不会把真实库存清零。
            rows.forEach(v -> v.setStock(null));
            //转成 JSON。快照里保留券 ID、名称、类型等展示信息，库存数值留给后面单独读取。
            String snapshot = json.writeValueAsString(rows);
            // 空列表缓存 5 秒，有券的列表缓存 30 秒。短期缓存空结果可减少重复查库。
            redis.opsForValue().set(key, snapshot, rows.isEmpty() ? 5 : 30, TimeUnit.SECONDS);
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存券列表缓存", e);
        } finally {
            lock.unlock();
        }
    }

    private void attachStock(List<Voucher> rows) {
        // 按当前类型判断，只给 type 非空且非 0 的券补库存。没有这类券时直接返回。
        List<Voucher> flash = rows.stream()
                .filter(v -> v.getType() != null && v.getType() != 0)//挑出 type 非空且不等于 0 的券
                .toList();
        if (flash.isEmpty()) {
            return;
        }

        // 列表缓存按店铺 ID 取；库存按券 ID 取，例如券 12 使用 seckill:stock:12。
        List<String> keys = flash.stream()
                .map(v -> SECKILL_STOCK_KEY + v.getId())
                .toList();
        // 按 keys 的顺序一次批量取库存，避免为每张券单独访问 Redis。
        //multiGet(keys) 一次读取这一批库存键。如果有三张秒杀券，也可以一起取。
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null || values.size() != flash.size()) {
            throw new IllegalStateException("读取秒杀库存失败");
        }

        for (int i = 0; i < flash.size(); i++) {
            String value = values.get(i);
            // 第 i 个值对应第 i 张秒杀券。单个库存键缺失时按 0 展示。
            flash.get(i).setStock(value == null ? 0 : Integer.parseInt(value));//把字符串转成整数
        }
    }
}