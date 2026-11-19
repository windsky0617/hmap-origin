package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀优惠券创建参数，对照 Plus 的 SeckillVoucherDto。
 * 当前仅迁移原有秒杀链路支持的字段，会员字段留到对应功能阶段。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class SeckillVoucherDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull
    private Long shopId;

    @NotBlank
    private String title;

    @NotBlank
    private String subTitle;

    private String rules;

    @NotNull
    private Long payValue;

    @NotNull
    private Long actualValue;

    @NotNull
    private Integer type;

    @NotNull
    private Integer status;

    @NotNull
    private Integer stock;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}