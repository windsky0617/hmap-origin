package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> getShopType() {
        //1.从redis查询缓存
        String key = "tb:shop:type:list";
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在，直接返回
            List<ShopType> list = JSONUtil.toList(json, ShopType.class);
            return list;
        }
        //3.不存在，查数据库                                            //把结果封装成一个list返回
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        return typeList;
    }
}
