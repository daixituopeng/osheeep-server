package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerRecipePreferenceMapper
        extends BaseMapper<DinnerRecipePreferenceEntity> {

    @Select({
        "<script>",
        "SELECT p.* FROM dinner_recipe_preferences p",
        "JOIN dinner_household_members m ON m.id = p.membership_id",
        "AND m.household_id = p.household_id AND m.user_id = p.user_id",
        "WHERE p.household_id = #{householdId} AND m.status = 'ACTIVE'",
        "AND p.recipe_id IN",
        "<foreach collection=\"recipeIds\" item=\"recipeId\"",
        "open=\"(\" separator=\",\" close=\")\">",
        "#{recipeId}",
        "</foreach>",
        "ORDER BY p.recipe_id, p.membership_id",
        "</script>"
    })
    List<DinnerRecipePreferenceEntity> selectActiveByHouseholdAndRecipeIds(
            @Param("householdId") Long householdId,
            @Param("recipeIds") List<Long> recipeIds);

    @Select("SELECT * FROM dinner_recipe_preferences "
            + "WHERE membership_id = #{membershipId} AND recipe_id = #{recipeId} "
            + "LIMIT 1 FOR UPDATE")
    DinnerRecipePreferenceEntity selectByMembershipAndRecipeForUpdate(
            @Param("membershipId") Long membershipId,
            @Param("recipeId") Long recipeId);

    @Select("SELECT * FROM dinner_recipe_preferences "
            + "WHERE household_id = #{householdId} ORDER BY id FOR UPDATE")
    List<DinnerRecipePreferenceEntity> selectByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_recipe_preferences "
            + "WHERE membership_id = #{membershipId} ORDER BY id FOR UPDATE")
    List<DinnerRecipePreferenceEntity> selectByMembershipIdForUpdate(
            @Param("membershipId") Long membershipId);

    @Select("SELECT * FROM dinner_recipe_preferences "
            + "WHERE user_id = #{userId} ORDER BY id FOR UPDATE")
    List<DinnerRecipePreferenceEntity> selectByUserIdForUpdate(
            @Param("userId") Long userId);

    @Update("UPDATE dinner_recipe_preferences "
            + "SET preference = #{preference}, version = version + 1 "
            + "WHERE id = #{id} AND household_id = #{householdId} "
            + "AND membership_id = #{membershipId} AND user_id = #{userId} "
            + "AND recipe_id = #{recipeId} AND version = #{expectedVersion}")
    int updatePreference(
            @Param("id") Long id,
            @Param("householdId") Long householdId,
            @Param("membershipId") Long membershipId,
            @Param("userId") Long userId,
            @Param("recipeId") Long recipeId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("preference") String preference);

    @Delete("DELETE FROM dinner_recipe_preferences WHERE household_id = #{householdId}")
    int deleteByHouseholdId(@Param("householdId") Long householdId);

    @Delete("DELETE FROM dinner_recipe_preferences WHERE membership_id = #{membershipId}")
    int deleteByMembershipId(@Param("membershipId") Long membershipId);

    @Delete("DELETE FROM dinner_recipe_preferences WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
