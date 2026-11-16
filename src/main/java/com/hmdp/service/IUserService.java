package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 撤销当前登录会话。
     * @param token 当前会话令牌
     * @return 处理结果
     */
    Result logout(String token);

    /**
     * 生成登录验证码并写入带过期时间的缓存。
     * @param phone 手机号
     * @param session 当前HTTP会话
     * @return 处理结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 一次性校验验证码并创建登录会话。
     * @param loginForm 手机号与验证码
     * @param session 当前HTTP会话
     * @return 处理结果
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 记录当前用户当天签到。
     * @return 处理结果
     */
    Result sign();

    /**
     * 计算截至今天的连续签到天数。
     * @return 处理结果
     */
    Result signCount();
}
