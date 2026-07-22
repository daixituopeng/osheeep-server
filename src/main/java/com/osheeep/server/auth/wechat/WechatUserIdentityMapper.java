package com.osheeep.server.auth.wechat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WechatUserIdentityMapper extends BaseMapper<WechatUserIdentityEntity> {
    @Select("SELECT * FROM wechat_user_identities "
            + "WHERE user_id = #{userId} ORDER BY id LIMIT 1 FOR UPDATE")
    WechatUserIdentityEntity selectByUserIdForUpdate(@Param("userId") Long userId);
}
