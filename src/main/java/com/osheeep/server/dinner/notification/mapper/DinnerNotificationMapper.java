package com.osheeep.server.dinner.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.notification.entity.DinnerNotificationEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerNotificationMapper extends BaseMapper<DinnerNotificationEntity> {

    @Select({
        "<script>",
        "SELECT * FROM dinner_notifications",
        "WHERE recipient_id = #{recipientId}",
        "AND expires_at &gt; #{now}",
        "AND (household_id IS NULL",
        "<if test='householdId != null'>OR household_id = #{householdId}</if>",
        ")",
        "<if test='beforeId != null'>AND id &lt; #{beforeId}</if>",
        "ORDER BY id DESC",
        "LIMIT #{limit}",
        "</script>"
    })
    List<DinnerNotificationEntity> selectVisiblePage(
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("beforeId") Long beforeId,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select({
        "<script>",
        "SELECT COUNT(*) FROM dinner_notifications",
        "WHERE recipient_id = #{recipientId}",
        "AND read_at IS NULL",
        "AND expires_at &gt; #{now}",
        "AND (household_id IS NULL",
        "<if test='householdId != null'>OR household_id = #{householdId}</if>",
        ")",
        "</script>"
    })
    long countVisibleUnread(
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("now") LocalDateTime now
    );

    @Select({
        "<script>",
        "SELECT * FROM dinner_notifications",
        "WHERE id = #{notificationId}",
        "AND recipient_id = #{recipientId}",
        "AND expires_at &gt; #{now}",
        "AND (household_id IS NULL",
        "<if test='householdId != null'>OR household_id = #{householdId}</if>",
        ")",
        "LIMIT 1 FOR UPDATE",
        "</script>"
    })
    DinnerNotificationEntity selectVisibleByIdForUpdate(
            @Param("notificationId") Long notificationId,
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("now") LocalDateTime now
    );

    @Update("UPDATE dinner_notifications SET read_at = #{readAt} "
            + "WHERE id = #{notificationId} AND recipient_id = #{recipientId} "
            + "AND read_at IS NULL")
    int markRead(
            @Param("notificationId") Long notificationId,
            @Param("recipientId") Long recipientId,
            @Param("readAt") LocalDateTime readAt
    );

    @Update({
        "<script>",
        "UPDATE dinner_notifications SET read_at = #{readAt}",
        "WHERE recipient_id = #{recipientId}",
        "AND read_at IS NULL",
        "AND expires_at &gt; #{readAt}",
        "AND (household_id IS NULL",
        "<if test='householdId != null'>OR household_id = #{householdId}</if>",
        ")",
        "</script>"
    })
    int markAllVisibleRead(
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("readAt") LocalDateTime readAt
    );

    @Delete("DELETE FROM dinner_notifications WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);

    @Delete("DELETE FROM dinner_notifications WHERE recipient_id = #{recipientId}")
    int deleteByRecipientId(@Param("recipientId") Long recipientId);

    @Delete("DELETE FROM dinner_notifications WHERE household_id = #{householdId}")
    int deleteByHouseholdId(@Param("householdId") Long householdId);
}
