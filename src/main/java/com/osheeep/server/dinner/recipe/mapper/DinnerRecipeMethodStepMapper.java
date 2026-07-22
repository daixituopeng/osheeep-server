package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerRecipeMethodStepMapper extends BaseMapper<DinnerRecipeMethodStepEntity> {
    @Select({
        "<script>",
        "SELECT * FROM dinner_recipe_method_steps WHERE method_id IN",
        "<foreach collection=\"methodIds\" item=\"methodId\" open=\"(\" separator=\",\" close=\")\">",
        "#{methodId}",
        "</foreach>",
        "ORDER BY method_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerRecipeMethodStepEntity> selectByMethodIdsForUpdate(
            @Param("methodIds") List<Long> methodIds);
}
