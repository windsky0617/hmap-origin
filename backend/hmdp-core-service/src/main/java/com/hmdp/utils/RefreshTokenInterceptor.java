package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


//刷新拦截器：第0层，通过后进入下一层：  登录拦截器：第1层
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        UserHolder.removeUser();

        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            //没有 Token 也返回 true：
            //因为当前拦截器负责的是“尝试识别身份”。没有 Token 就保持匿名，
            //继续让后面的 LoginInterceptor 判断这个接口是否允许匿名访问。
            return true;
        }


        //有token，去redis查询这个用户
        try {
            String key = LOGIN_USER_KEY + token;
            Map<Object, Object> userMap =
                    stringRedisTemplate.opsForHash().entries(key);
            if (userMap.isEmpty()) {
                //Redis 中没有登录信息，登录过期或已退出登录，继续保持匿名
                return true;
            }
            //Redis 中有登录信息，DTO 可以先理解为“用于传递数据的对象”。本项目的 UserDTO 只包含需要使用的部分用户信息。
            UserDTO user = BeanUtil.fillBeanWithMap(
                    userMap, new UserDTO(), false);//这里的 false 表示“不忽略注入错误”
            //当前的DTO：部分用户信息保存到当前线程：
            UserHolder.saveUser(user);
            stringRedisTemplate.expire(
                    key, LOGIN_USER_TTL, TimeUnit.MINUTES);//刷新登录有效期
            return true;
        } catch (RuntimeException ex) {
            UserHolder.removeUser();
            throw ex;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                @Nullable Exception ex) {
        UserHolder.removeUser();
    }
}