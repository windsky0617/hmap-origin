package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
//顺序必须是先装载身份、再校验登录。公开路径的判断集中保留在 LoginInterceptor 中。
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new RefreshTokenInterceptor(stringRedisTemplate))//注册刷新拦截器
                .addPathPatterns("/**")//匹配全部请求路径，拦截器会作用到所有接口
                .order(0);//设置执行顺序，数值越小，前置处理越早执行

        registry.addInterceptor(new LoginInterceptor())//注册登录拦截器
                .addPathPatterns("/**")
                .excludePathPatterns("/error")//排除项
                .order(1);
    }
}


/*package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration                 //Mvc相关的配置//加了@Configuration 注解，是由string来构建这个类的对象的，就可以用@Autowire注入
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override    //添加拦截器
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .excludePathPatterns("/error");
    }
}*/
