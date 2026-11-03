package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//拦截器需要实现接口  HandlerInterceptor
//拦截器写好，需要去配置拦截器才能让拦截器生效
public class LoginInterceptor implements HandlerInterceptor {
//LoginInterceptor这个类是自己手动new出来的，不是通过@Component注解（string创建的），不能用@Autowire注入stringRedisTemplate
    //需要通过构造器手动注入
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override//在controller之前执行
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取session中的用户
//        Object user = session.getAttribute("user");
        //1.获取请求头中的token                    //前端代码中的
        String token = request.getHeader("authorization");
        boolean publicRequest = isPublicRequest(request);

        if (StrUtil.isBlank(token)) {
            return allowPublicOrReject(publicRequest, response);
        }
        //2.基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap.isEmpty()) {
            return allowPublicOrReject(publicRequest, response);
        }

        //5.将查询到的哈希数据再转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //6.存在，保存用户信息到ThreadLocal  //在UserHolder里已定义ThreadLocal
        UserHolder.saveUser(userDTO);


        //7.刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //8.放行
        return true;
    }

    private boolean allowPublicOrReject(boolean publicRequest, HttpServletResponse response) {
        if (publicRequest) {
            return true;
        }
        response.setStatus(401);
        return false;
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            return ("/user/code".equals(path) || "/user/login".equals(path))
                    && "POST".equalsIgnoreCase(method);
        }
        return path.startsWith("/shop/")
                || path.startsWith("/shop-type/")
                || path.startsWith("/voucher/list/")
                || "/blog/hot".equals(path);
    }


    //在视图渲染给用户前执行
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);

        //移除用户，避免内存泄漏
        UserHolder.removeUser();

    }
}
