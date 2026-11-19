package com.hmdp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginFormDTO {

    /**
     * 只校验必填。
     * 手机号格式继续由现有 Service 规则校验。
     */
    @NotBlank(message = "手机号不能为空")//@NotBlank 拒绝 null、空字符串和空白字符串。
    private String phone;

    /**
     * 当前服务生成六位数字验证码。
     * 格式正确不代表验证码正确，仍需查询 Redis 验证。
     */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "[0-9]{6}", message = "验证码必须为六位数字")//@Pattern 限制完整字符串为六位数字；它本身不能替代必填检查。
    private String code;


    private String password;
}



/*
package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
*/
