package com.osheeep.server.dinner.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerCookingRecordMapper extends BaseMapper<DinnerCookingRecordEntity> {
    @Select("SELECT * FROM dinner_cooking_records "
            + "WHERE household_id = #{householdId} ORDER BY id FOR UPDATE")
    List<DinnerCookingRecordEntity> selectAllByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);
}
