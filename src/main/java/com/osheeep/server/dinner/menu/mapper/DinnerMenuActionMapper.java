package com.osheeep.server.dinner.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerMenuActionMapper extends BaseMapper<DinnerMenuActionEntity> {
    @Select({
        "<script>",
        "SELECT * FROM dinner_menu_actions WHERE menu_id IN",
        "<foreach collection=\"menuIds\" item=\"menuId\" open=\"(\" separator=\",\" close=\")\">",
        "#{menuId}",
        "</foreach>",
        "ORDER BY menu_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerMenuActionEntity> selectByMenuIdsForUpdate(
            @Param("menuIds") List<Long> menuIds);
}
