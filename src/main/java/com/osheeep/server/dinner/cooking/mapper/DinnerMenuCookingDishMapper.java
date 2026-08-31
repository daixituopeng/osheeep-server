package com.osheeep.server.dinner.cooking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.cooking.entity.DinnerMenuCookingDishEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerMenuCookingDishMapper
        extends BaseMapper<DinnerMenuCookingDishEntity> {

    @Select("SELECT *, method_steps AS method_steps_json "
            + "FROM dinner_menu_cooking_dishes "
            + "WHERE menu_id = #{menuId} ORDER BY sort_order, id")
    List<DinnerMenuCookingDishEntity> selectByMenuId(
            @Param("menuId") Long menuId);

    @Select("SELECT *, method_steps AS method_steps_json "
            + "FROM dinner_menu_cooking_dishes "
            + "WHERE menu_id = #{menuId} ORDER BY sort_order, id FOR UPDATE")
    List<DinnerMenuCookingDishEntity> selectByMenuIdForUpdate(
            @Param("menuId") Long menuId);

    @Select({
        "<script>",
        "SELECT *, method_steps AS method_steps_json",
        "FROM dinner_menu_cooking_dishes WHERE menu_id IN",
        "<foreach collection=\"menuIds\" item=\"menuId\" open=\"(\" separator=\",\" close=\")\">",
        "#{menuId}",
        "</foreach>",
        "ORDER BY menu_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerMenuCookingDishEntity> selectByMenuIdsForUpdate(
            @Param("menuIds") List<Long> menuIds);

    @Update("UPDATE dinner_menu_cooking_dishes "
            + "SET completed_by = #{completedBy}, completed_at = #{completedAt} "
            + "WHERE id = #{dishId} AND menu_id = #{menuId} "
            + "AND completed_by IS NULL AND completed_at IS NULL")
    int markCompleted(
            @Param("dishId") Long dishId,
            @Param("menuId") Long menuId,
            @Param("completedBy") Long completedBy,
            @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE dinner_menu_cooking_dishes "
            + "SET completed_by = NULL, completed_at = NULL "
            + "WHERE id = #{dishId} AND menu_id = #{menuId} "
            + "AND completed_by IS NOT NULL AND completed_at IS NOT NULL")
    int clearCompletion(
            @Param("dishId") Long dishId,
            @Param("menuId") Long menuId);
}
