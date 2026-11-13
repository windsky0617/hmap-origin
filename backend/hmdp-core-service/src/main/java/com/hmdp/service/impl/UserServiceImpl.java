package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource//String data 提供的api
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }


        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //4.保存验证码到session
//        session.setAttribute("code",code);

        //4.保存验证码到redis //set key value ex 120(秒)     //key:LOGIN_CODE_KEY+phone,vlaue:code,TTl存活时间:2min
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码=====需要调用第三方的平台
        log.debug("发送短信验证码成功,验证码:{}",code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //1.校验手机号
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }

//        //3.从session中取得校验码并校验
//        String code = loginForm.getCode();
//        Object cacheCode = session.getAttribute("code");

        //3.从redis中取得校验码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if (cacheCode == null || code == null || !cacheCode.equals(code)) {
            //3.不符合，返回错误信息
            return Result.fail("验证码错误或已过期");
        }

        //4.根据手机号查询用户
        User user = query().eq("phone", phone).one();


        //5.判断用户是否存在
        if (user == null) {
            //6.用户不存在，创建新用户并保存
          user = createUserWithPhone(phone);
        }
        
//        //7.保存用户信息到sessino中
//        //为了不把敏感信息返回并减少返回的信息，缓解内存压力，现在只传部分信息----UserDTO
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//把user对象中的属性复制到UserDTo中，并创建出一个UserDTo对象
//        session.setAttribute("user",userDTO);

        //7.保存用户信息到redis中

        //7.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);//自动将userDIO转为HashMap类型 //此处会报错：Long类型转string类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)//忽略空的值           //把Long类型的字段的值转为string类型的值
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString() )
        );
        //7.3存储到redis
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);//操作的是Hash结构的数据用opsForHash
        //7.4设置token有效期，有效期过后token失效，对应的值也消除，释放内存
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);//在拦截器中定义：只有用户什么都没有访问，token才会过期
                                                                            //访问了就刷新有效期

        //8.返回token到前端

        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (token != null && !token.trim().isEmpty()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token.trim());
        }
        return Result.ok();
    }

    @Override
    public Result sign() { //实现签到功能 基于redis的BitMap ：0/1
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        //角标从0开始，一共0——31 //dayOfMonth - 1：表示这个月的第几天，第一天为角标0     true：设置为1  默认：false:0
        return Result.ok();

    }

    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()    //创建子命令  BitField里面set，get，incred都是子命令
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))//u14:无符号位查询 14个
                        .valueAt(0)//从角标0开始查
        );
        if (result == null || result.isEmpty()) { //Redis BITFIELD 没有返回任何数据组
            //这个判断的真实目的：防止 result.get(0)空指针，是防御性判空
            // 没有任何签到结果
            return Result.ok(0);
        }

        Long num = result.get(0);//获取的是返回的那个二进制转十进制的数字结果
        if (num == null || num == 0) {//拿到数字了，但值是 0，所有 bit 都是 0，无签到
            return Result.ok(0);
        }
        // 6. 循环遍历
        int count = 0;
        while (true) {
            // 6.1. 让这个数字与1做与运算，得到数字的最后一个bit位 // 判断这个bit位是否为0
            //Java 里的 Long，存的是十进制数值，但计算机底层永远是二进制存储；位运算直接操作它底层的二进制比特，和你看到它是十进制数字没有冲突。
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            } else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1; //num =num>>>1
            // >>：有符号右移（算术右移）高位补符号位：正数补 0，负数补 1
            // >>>无符号右移（逻辑右移）高位永远补0，不管正负
        }
        return Result.ok(count);

    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6) );
        //2。保存用户,写入MySQL数据库!!!!!!!
        save(user);//mybatis-plus提供，自动生成INSERT INTO user (phone, nick_name) VALUES (?,?)
        log.debug("创建了用户，并把用户保存在了数据库");

        return user;
    }
}
