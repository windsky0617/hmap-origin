package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.cache.VoucherListCache;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherDto;
import com.hmdp.dto.VoucherDto;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherListCache listCache;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        return Result.ok(listCache.list(shopId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addVoucher(VoucherDto dto) {
        return saveBaseVoucher(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addSeckillVoucher(SeckillVoucherDto dto) {
        Long id = saveBaseVoucher(BeanUtil.copyProperties(dto, VoucherDto.class));

        SeckillVoucher seckill = new SeckillVoucher();
        seckill.setVoucherId(id);
        seckill.setStock(dto.getStock());
        seckill.setBeginTime(dto.getBeginTime());
        seckill.setEndTime(dto.getEndTime());
        seckillVoucherService.save(seckill);

        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + id, dto.getStock().toString());
        return id;
    }

    private Long saveBaseVoucher(VoucherDto dto) {
        Voucher voucher = BeanUtil.copyProperties(dto, Voucher.class);
        save(voucher);

        Long shopId = voucher.getShopId();
        listCache.includeShop(shopId);//登记店铺 ID进布隆过滤器

        //把Caffeine和 Redis中当前店铺的旧券列表删除，下次再查询  Caffeine->Redis ->拿锁 -> Mysql 就能返回新的券列表
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    listCache.invalidate(shopId);
                } catch (RuntimeException e) {
                    log.error("券已提交，列表失效失败，店铺ID={}", shopId, e);
                }
            }
        });
        return voucher.getId();
    }
}