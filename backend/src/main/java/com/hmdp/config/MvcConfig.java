package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration                 //Mvc相关的配置//加了@Configuration 注解，是由string来构建这个类的对象的，就可以用@Autowire注入
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override    //添加拦截器
    public void addInterceptors(InterceptorRegistry registry) {
        //registry : 拦截器的注册器      默认拦截一切路径                  //排除不需要拦截的路径(放行)
        registry.addInterceptor(new LoginInterceptor(stringRedisTemplate)).excludePathPatterns(

                "/shop/**",
                "/voucher/**",
                "/shop-type/**",
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login"
        );
    }
}
