package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerRecipeMethodMapper extends BaseMapper<DinnerRecipeMethodEntity> {
    @Select({
        "<script>",
        "SELECT * FROM dinner_recipe_methods WHERE recipe_id IN",
        "<foreach collection=\"recipeIds\" item=\"recipeId\" open=\"(\" separator=\",\" close=\")\">",
        "#{recipeId}",
        "</foreach>",
        "ORDER BY recipe_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerRecipeMethodEntity> selectByRecipeIdsForUpdate(
            @Param("recipeIds") List<Long> recipeIds);
}
