package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 更新当前用户的关注关系。
     * @param followUserId 目标用户编号
     * @param isFollow 是否关注
     * @return 处理结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询当前用户是否已关注目标用户。
     * @param followUserId 目标用户编号
     * @return 处理结果
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户与目标用户的共同关注。
     * @param id 业务主键
     * @return 处理结果
     */
    Result followCommons(Long id);
}
