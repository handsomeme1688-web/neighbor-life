package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    // 只能使用构造函数注入，因为LoginInterceptor这个类的对象是我们自己手动new出来的，不是由spring创建的，所以不能使用@Autowired
    // 看谁用了它，在MvcConfig的拦截器里用到了
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override // 进入controller之前，登录校验
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取session
//        HttpSession session = request.getSession();
        // TODO 1. 获取请求头中的 token
        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
//            //  不存在，拦截  返回401状态码(未授权)
//            response.setStatus(401);
//            return false;
            return true;
        }
        // 2. 基于token获取redis中的用户    // entries返回值是map(没有getAll)
        Map<Object, Object> userMap = stringRedisTemplate.
                opsForHash().entries(LOGIN_USER_KEY + token);

        // 2. 获取session中的用户           // 取出来的是DTO对象
//        Object user = session.getAttribute("user");
        // 3. 判断用户是否存在
        if (userMap.isEmpty()) { // entries方法会做判断，如果是null，会返回空map
//            //  不存在，拦截  返回401状态码(未授权)
//            response.setStatus(401);
//            return false;
            return true;
        }
        // 5. 将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 5. 存在，保存用户信息到 ThreadLocal
//        UserHolder.saveUser((UserDTO) user); // session中取出来的是DTO对象
        UserHolder.saveUser(userDTO);

        // TODO 刷新token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. 放行
        return true;
    }

    @Override // 最后用户业务执行完毕，销毁对应的用户信息，避免内存的泄露
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
