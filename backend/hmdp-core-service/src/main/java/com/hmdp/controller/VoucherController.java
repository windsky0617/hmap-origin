package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherDto;
import com.hmdp.dto.VoucherDto;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucherDto 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@Valid @RequestBody VoucherDto voucherDto) {
        final Long voucherId = voucherService.addVoucher(voucherDto);
        return Result.ok(voucherId);
    }

    /**
     * 新增秒杀券
     * @param seckillVoucherDto 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(
            @Valid @RequestBody SeckillVoucherDto seckillVoucherDto) {
        final Long voucherId = voucherService.addSeckillVoucher(seckillVoucherDto);
        return Result.ok(voucherId);
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(
            @Positive(message = "店铺ID必须大于0")
            @PathVariable("shopId") Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }
}