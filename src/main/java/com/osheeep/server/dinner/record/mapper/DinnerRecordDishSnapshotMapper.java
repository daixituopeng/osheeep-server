package com.osheeep.server.dinner.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerRecordDishSnapshotMapper extends BaseMapper<DinnerRecordDishSnapshotEntity> {
    @Select({
        "<script>",
        "SELECT * FROM dinner_record_dish_snapshots WHERE record_id IN",
        "<foreach collection=\"recordIds\" item=\"recordId\" open=\"(\" separator=\",\" close=\")\">",
        "#{recordId}",
        "</foreach>",
        "ORDER BY record_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerRecordDishSnapshotEntity> selectByRecordIdsForUpdate(
            @Param("recordIds") List<Long> recordIds);
}
