package com.osheeep.server.dinner.ingredient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerIngredientMapper extends BaseMapper<DinnerIngredientEntity> {

    @Select("SELECT * FROM dinner_ingredients "
            + "WHERE household_id = #{householdId} AND scope = 'HOUSEHOLD' "
            + "ORDER BY id FOR UPDATE")
    List<DinnerIngredientEntity> selectAllHouseholdIngredientsForUpdate(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_ingredients "
            + "WHERE status = 'ACTIVE' AND name = #{name} "
            + "AND (scope = 'SYSTEM' OR (scope = 'HOUSEHOLD' "
            + "AND household_id = #{householdId})) ORDER BY id")
    List<DinnerIngredientEntity> selectActiveAccessibleByName(
            @Param("householdId") Long householdId,
            @Param("name") String name);
}
