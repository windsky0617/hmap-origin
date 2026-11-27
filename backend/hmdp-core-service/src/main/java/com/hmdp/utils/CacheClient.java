package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LOGICAL_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    // 后台成功刷新店铺后，新数据在接下来的 60 秒内视为新鲜。
    private static final long HOT_SECONDS = 60;

    // 逻辑过期后，最多再允许返回旧数据 60 秒。
    private static final long STALE_SECONDS = 60;

    // 普通缓存未命中时，请求最多等 2 秒获取重建锁。
    private static final long WAIT_SECONDS = 2;

    // 后面拼上完整缓存键，使不同店铺、普通缓存和逻辑缓存分别使用各自的锁。
    private static final String LOCK_PREFIX = "lock:cache:rebuild:v1:";

    // 后台查库期间，店铺可能已经被更新，原来的逻辑缓存也可能已被删除。
    // 因此，在 Redis 内一次完成“比较旧值”和“写入或删除”。
    // KEYS[1]：逻辑缓存键。
    // ARGV[1]：提交后台任务时读到的原始 JSON。
    // ARGV[2]：准备写入的新 JSON；空串表示数据库已经查不到这条记录。
    // ARGV[3]：新缓存的 Redis TTL，单位为秒。
    private static final DefaultRedisScript<Long> FINISH_REFRESH = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end " +
                    "if ARGV[2] == '' then return redis.call('DEL', KEYS[1]) end " +
                    "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1",
            Long.class
    );

    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redisson;

    // 缓存重建线程池：最多 2 个线程执行刷新，最多 32 个任务排队。
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32)
    );

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redisson) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisson = redisson;
    }

    // 店铺详情的查询入口。load 是调用方传入的查库方法，店铺服务传入 this::getById。
    public Shop queryShopById(Long id, Function<Long, Shop> load) {
        // 1. 先读预热使用的逻辑键，例如 cache:shop:logical:v1:1。
        Shop hot = queryWithLogicalExpire(
                CACHE_SHOP_LOGICAL_KEY, id, Shop.class, load, HOT_SECONDS, TimeUnit.SECONDS
        );
        if (hot != null) {
            return hot;
        }

        // 2. 没有可用的预热数据时，再走普通缓存，例如 cache:shop:1。
        return queryWithPassThrough(
                CACHE_SHOP_KEY, id, Shop.class, load, CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // StringRedisTemplate 写入的是字符串，所以先把对象转成 JSON，再设置 Redis TTL。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        long seconds = unit.toSeconds(time);

        // wrap 把数据和逻辑过期时间装进 RedisData。
        // 例如预热 10 秒：JSON 里的逻辑时间是 10 秒后，Redis 在 10 + 60 秒后让键到期。
        stringRedisTemplate.opsForValue().set(
                key, wrap(value, seconds), seconds + STALE_SECONDS, TimeUnit.SECONDS
        );
    }

    private String wrap(Object value, long seconds) {
        // 设置逻辑过期：先把原数据和过期时间装进 RedisData。
        RedisData entry = new RedisData();
        entry.setData(value);
        entry.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        return JSONUtil.toJsonStr(entry);
    }

    // 单看 value == null 无法区分“Redis 没有这个键”和“命中了空值标记”。
    // hit 记录是否命中；value 保存命中的数据，允许为 null。
    private record Lookup<R>(boolean hit, R value) {}

    private <R> Lookup<R> readPlain(String prefix, String key, Class<R> type) {
        // 判断读到的是非空 JSON、空值标记，还是根本没有这个键。
        String raw = stringRedisTemplate.opsForValue().get(key);

        if (raw == null) {
            // Redis 没有这个键，需要继续尝试重建。
            return new Lookup<>(false, null);
        }

        if (StrUtil.isBlank(raw)) {
            // 写入空值标记时用的是 ""。命中后直接返回 null，省去一次查库。
            // ShopServiceImpl 收到 null 后，会返回“店铺不存在!”。
            return new Lookup<>(true, null);
        }

        if (CACHE_SHOP_KEY.equals(prefix)) {
            // 普通店铺键预期存 Shop JSON。老预热可能误写了带 expireTime 的 RedisData。
            JSONObject cachedShop = JSONUtil.parseObj(raw);
            if (cachedShop.containsKey("expireTime")) {
                // 把旧包装当作未命中，后续查库完成后用正确格式覆盖旧键。
                return new Lookup<>(false, null);
            }
            return new Lookup<>(true, JSONUtil.toBean(cachedShop, type));
        }

        // 其他缓存键正常命中时，直接按调用方指定的类型转换。
        return new Lookup<>(true, JSONUtil.toBean(raw, type));
    }

    // R 是返回数据的类型，例如 Shop。ID 是主键类型，例如 Long。
    // prefix 是 Redis 键的前缀，type 告诉 Hutool 要把 JSON 转成哪个类。
    public <R, ID> R queryWithPassThrough(
            String prefix, ID id, Class<R> type, Function<ID, R> load,
            Long time, TimeUnit unit) {
        // 1. 从 Redis 查询缓存，例如 prefix + id 得到 cache:shop:1。
        String key = prefix + id;
        Lookup<R> first = readPlain(prefix, key, type);
        if (first.hit()) {
            return first.value();
        }

        // 2. 未命中时，尝试获取这个缓存键的重建锁。
        // getLock 得到锁对象，执行 tryLock 时才真正尝试拿锁。
        RLock lock = redisson.getLock(LOCK_PREFIX + key);
        try {
            if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                // 等锁超时，再读最后一次。其他请求可能刚刚写好店铺或空值标记。
                Lookup<R> last = readPlain(prefix, key, type);
                if (last.hit()) {
                    return last.value();
                }
                throw new IllegalStateException("等待缓存重建超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待缓存重建被中断", e);
        }

        try {
            // 3. 拿到锁后再查一次。
            // 例如 A 已经查库并回填，B 等到锁后就可以直接用 A 写好的缓存。
            Lookup<R> second = readPlain(prefix, key, type);
            if (second.hit()) {
                return second.value();
            }

            // 4. 持锁复查后仍未命中，才按 ID 查数据库。
            // load 是调用方传进来的函数。店铺详情传入 this::getById。
            R value = load.apply(id);

            if (value == null) {
                // 5. 数据库也查不到时，写入带短 TTL 的空串，避免同一 ID 反复查库。
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            } else {
                // 6. 查到数据后，写入普通 JSON 并设置 Redis TTL。
                set(key, value, time, unit);
            }

            // 7. 把本次查到的数据返回给调用方。
            return value;
        } finally {
            // 前面已经成功拿到锁，由当前请求线程释放。
            lock.unlock();
        }
    }

    // 逻辑过期用于缓解热点缓存击穿。命中过期数据时，在允许的时间内先返回旧值，
    // 刷新工作交给后台线程。没有可用逻辑缓存时，返回 null，让店铺入口继续查普通缓存。
    public <R, ID> R queryWithLogicalExpire(
            String prefix, ID id, Class<R> type, Function<ID, R> load,
            Long time, TimeUnit unit) {
        // 1. 从 Redis 查询逻辑过期缓存。店铺传入 CACHE_SHOP_LOGICAL_KEY 前缀。
        String key = prefix + id;
        String raw = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否读到了缓存数据。
        if (StrUtil.isBlank(raw)) {
            return null;
        }

        // 3. 先把外层 JSON 转成 RedisData，取出里面的 data 和 expireTime。
        RedisData entry = JSONUtil.toBean(raw, RedisData.class);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = entry.getExpireTime();

        // 4. 缺少过期时间或数据，或者已经到达“逻辑过期时间 + 60 秒”，就停止使用旧值。
        if (expiry == null || entry.getData() == null
                || !now.isBefore(expiry.plusSeconds(STALE_SECONDS))) {
            return null;
        }

        // RedisData 的 data 字段声明为 Object。这里取出 JSONObject，
        // 再按调用方传入的 type，把内层对象转成 Shop 等具体类型。
        R cached = JSONUtil.toBean((JSONObject) entry.getData(), type);

        // 5. 到了逻辑过期时间，但还在允许读旧值的窗口内，提交后台刷新任务。
        if (!now.isBefore(expiry)) {
            try {
                workers.execute(() -> refresh(key, raw, id, load, unit.toSeconds(time)));
            } catch (RejectedExecutionException ignored) {
                // 队列已满或应用正在关闭。本次请求仍可以返回刚读到的旧值。
            }
        }

        // 6. 未过期时直接返回新鲜数据；刚过期时返回旧数据，无需等待后台查库。
        return cached;
    }

    private <R, ID> void refresh(
            String key, String observed, ID id, Function<ID, R> load, long seconds) {
        try {
            // 1. 后台线程自己尝试拿锁。已有线程在刷新这个键时，本次任务直接结束。
            RLock lock = redisson.getLock(LOCK_PREFIX + key);//拿到要获取的锁的java对象
            if (!lock.tryLock()) {//尝试去获取这个对象的锁
                return;
            }

            try {
                // 2. 任务可能在队列里等过。若缓存已经变化或被删除，省去这次查库。
                //现在正在操作的值和redis里存放的不一样，直接放弃操作，取消后续流程
                if (!observed.equals(stringRedisTemplate.opsForValue().get(key))) {
                    return;
                }

                // 3. 查询数据库。查到时重新包装数据；查不到时让 Lua 删除旧逻辑键。
                R value = load.apply(id);
                String replacement = value == null ? "" : wrap(value, seconds);//三元表达式

                // 4. 查库期间仍可能有人改店名并删除缓存，所以写回前由 Lua 再比较一次。
                // 只有 Redis 中仍存着最初那份 observed，才允许覆盖或删除。
                stringRedisTemplate.execute(
                        FINISH_REFRESH, List.of(key), observed, replacement,
                        Long.toString(seconds + STALE_SECONDS)
                );
            } finally {
                // 后台刷新任务结束时，由拿到锁的后台线程释放锁。
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("刷新逻辑缓存失败，key={}", key, e);
        }
    }

    @PreDestroy
    //告诉 Spring：“销毁这个对象之前，先执行这个方法。”
    public void close() {//由spring调用
        //CacheClient 类上有 @Component，所以 Spring 负责创建和管理它。

        // Spring 销毁这个 Bean 时，停止线程池并尝试中断正在运行的刷新任务。
        workers.shutdownNow();
    }
}