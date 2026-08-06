package com.osheeep.server.dinner.shopping.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.shopping.entity.DinnerHouseholdShoppingItemEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdShoppingItemMapper
        extends BaseMapper<DinnerHouseholdShoppingItemEntity> {

    @Select("SELECT * FROM dinner_household_shopping_items "
            + "WHERE household_id = #{householdId} ORDER BY created_at, id")
    List<DinnerHouseholdShoppingItemEntity> selectByHouseholdId(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_household_shopping_items "
            + "WHERE household_id = #{householdId} AND ingredient_id = #{ingredientId} "
            + "FOR UPDATE")
    DinnerHouseholdShoppingItemEntity selectByHouseholdAndIngredientForUpdate(
            @Param("householdId") Long householdId,
            @Param("ingredientId") Long ingredientId);

    @Select("SELECT * FROM dinner_household_shopping_items "
            + "WHERE household_id = #{householdId} ORDER BY ingredient_id FOR UPDATE")
    List<DinnerHouseholdShoppingItemEntity> selectAllByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);
}
