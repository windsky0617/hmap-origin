package com.hmdp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 普通优惠券创建参数，对照 Plus 的 VoucherDto。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class VoucherDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull
    private Long shopId;

    @NotBlank
    private String title;

    @NotBlank
    private String subTitle;

    @NotBlank
    private String rules;

    @NotNull
    private Long payValue;

    @NotNull
    private Long actualValue;

    @NotNull
    private Integer type;

    @NotNull
    private Integer status;
}