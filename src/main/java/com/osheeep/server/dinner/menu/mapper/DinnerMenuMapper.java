package com.osheeep.server.dinner.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerMenuMapper extends BaseMapper<DinnerMenuEntity> {
    @Select("""
            SELECT * FROM dinner_menus
            WHERE household_id = #{householdId} AND menu_date = #{menuDate}
            FOR UPDATE
            """)
    DinnerMenuEntity selectByHouseholdAndDateForUpdate(Long householdId, LocalDate menuDate);

    @Select("""
            SELECT * FROM dinner_menus
            WHERE household_id = #{householdId}
              AND menu_date BETWEEN #{startDate} AND #{endDate}
            ORDER BY menu_date
            """)
    List<DinnerMenuEntity> selectByHouseholdAndDateRange(
            @Param("householdId") Long householdId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Select("SELECT * FROM dinner_menus "
            + "WHERE household_id = #{householdId} AND status <> 'COMPLETED' "
            + "ORDER BY id FOR UPDATE")
    List<DinnerMenuEntity> selectUncompletedByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_menus "
            + "WHERE household_id = #{householdId} ORDER BY id FOR UPDATE")
    List<DinnerMenuEntity> selectAllByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Update({
        "<script>",
        "UPDATE dinner_menus",
        "SET status = 'DRAFT', confirmed_by = NULL, confirmed_at = NULL,",
        "version = version + 1",
        "WHERE household_id = #{householdId} AND status &lt;&gt; 'COMPLETED' AND id IN",
        "<foreach collection=\"menuIds\" item=\"menuId\" open=\"(\" separator=\",\" close=\")\">",
        "#{menuId}",
        "</foreach>",
        "</script>"
    })
    int resetUncompletedMenus(
            @Param("householdId") Long householdId,
            @Param("menuIds") List<Long> menuIds);

    @Update("UPDATE dinner_menus "
            + "SET status = 'DRAFT', confirmed_by = NULL, confirmed_at = NULL, "
            + "version = version + 1 "
            + "WHERE id = #{menuId} AND status = 'CONFIRMED' "
            + "AND version = #{expectedVersion}")
    int clearConfirmationAndAdvanceVersion(
            @Param("menuId") Long menuId,
            @Param("expectedVersion") Long expectedVersion);
}
