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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionUsageException;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.nio.channels.NonWritableChannelException;
import java.security.cert.CRLException;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. 如果不符合, 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 3. 符合, 生成验证码
        String code = RandomUtil.randomNumbers(6);
//        // 4. 保存验证码到session
//        session.setAttribute("code", code);
        // 4. 保存验证码到redis                  // phone前面加上业务前缀
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        // 5. 发送验证码
        log.debug("发送短信验证码成功, 验证码: {}", code);
        // 6. 返回ok
        return Result.ok();

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号 (每个请求都要做独立的校验)
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. 如果不符合, 返回错误信息
            return Result.fail("手机号格式错误");
        }
        // 2. 校验验证码
        // 首先从session里获取验证码, 然后和用户输入的(表单中)验证码进行比较
        // TODO 从redis里获取验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
//        if(cacheCode == null || !cacheCode.toString().equals(code)){
        if(cacheCode == null || !cacheCode.equals(code)){
            // 3. 不一致,报错
            return Result.fail("验证码错误");
        }

        // 4. 一致, 根据手机号查询用户 (去数据库查) select * from user where phone = ?
                                            // 不需要自己去写sql, mybatis-plus会自动生成
        User user = query().eq("phone", phone).one();

        // 5. 判断用户是否存在
        if (user == null) {
            // 6. 不存在, 创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7. 不管存不存在, 保存用户信息到session中 (存的是DTO)
        // TODO 保存用户信息到redis中
        // 7.1 随机生成token, 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2 将User对象转为哈希去存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3 保存数据到redis当中
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
                                                    // token加一下业务前缀                  // 将User转成map
        // 7.4 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8. 返回token到前端
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
//        return Result.ok();             // hutool里的工具类, 将user转为UserDTO
        return Result.ok(token);                                 // copyProperties 拷贝属性
    }

    @Override
    public Result sign() {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + ":" + yyyyMM;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth(); // ctrl q 获取注释文档
        // 5. 写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {

        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId + ":" + yyyyMM;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截至今天为止的所有签到记录, 返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.size() == 0) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 循环遍历
        int count = 0;
        while (true) {
            // 让这个数字与1做与运算, 得到这个数字的最后一个bit位
            if ((num & 1) == 0) {
                // 如果为0, 连续签到天数为0
                break;
            }else{
                // 如果为1, 计数器+1
                count++;
            }
            // 把数字右移1位，继续判断下一个bit位
            num = num >> 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        save(user);
        return user;
    }
}
