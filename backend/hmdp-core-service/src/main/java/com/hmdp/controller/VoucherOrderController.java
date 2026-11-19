package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.Positive;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(
            @Positive(message = "优惠券ID必须大于0")
            @PathVariable("id") Long voucherId) {

        Result result = voucherOrderService.seckillVoucher(voucherId);

        // 只转换当前成功响应中的 Long 订单编号。
        // 在 JSON 序列化之前转换，避免前端解析数字时丢失精度。
        if (Boolean.TRUE.equals(result.getSuccess())
                && result.getData() instanceof Long orderId) {
            result.setData(Long.toString(orderId));
        }

        return result;

        //return voucherOrderService.seckillVoucher(voucherId);
    }
}