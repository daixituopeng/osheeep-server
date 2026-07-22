package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdOperationMapper extends BaseMapper<DinnerHouseholdOperationEntity> {
    @Select("SELECT * FROM dinner_household_operations "
            + "WHERE actor_id = #{actorId} AND idempotency_key = #{idempotencyKey} "
            + "ORDER BY id DESC LIMIT 1")
    DinnerHouseholdOperationEntity selectByActorAndIdempotencyKey(
            @Param("actorId") Long actorId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM dinner_household_operations "
            + "WHERE actor_id = #{actorId} AND idempotency_key = #{idempotencyKey} "
            + "ORDER BY id DESC LIMIT 1 FOR UPDATE")
    DinnerHouseholdOperationEntity selectByActorAndIdempotencyKeyForUpdate(
            @Param("actorId") Long actorId,
            @Param("idempotencyKey") String idempotencyKey);

    @Delete("DELETE FROM dinner_household_operations "
            + "WHERE actor_id = #{actorId} AND idempotency_key = #{idempotencyKey} "
            + "AND expires_at <= #{expiredAt}")
    int deleteExpiredByActorAndIdempotencyKey(
            @Param("actorId") Long actorId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("expiredAt") LocalDateTime expiredAt);

    @Select("SELECT * FROM dinner_household_operations "
            + "WHERE household_id = #{householdId} ORDER BY id FOR UPDATE")
    List<DinnerHouseholdOperationEntity> selectAllByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select({
        "<script>",
        "SELECT * FROM dinner_household_operations WHERE actor_id = #{actorId}",
        "<if test='membershipIds != null and membershipIds.size() > 0'>",
        "OR target_member_id IN",
        "<foreach collection=\"membershipIds\" item=\"membershipId\" open=\"(\" separator=\",\" close=\")\">",
        "#{membershipId}",
        "</foreach>",
        "</if>",
        "ORDER BY id FOR UPDATE",
        "</script>"
    })
    List<DinnerHouseholdOperationEntity> selectByActorOrTargetMembershipIdsForUpdate(
            @Param("actorId") Long actorId,
            @Param("membershipIds") List<Long> membershipIds);
}
