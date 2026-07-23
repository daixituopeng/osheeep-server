package com.osheeep.server.dinner.subscription.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.subscription.entity.DinnerSubscriptionDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerSubscriptionDeliveryMapper
        extends BaseMapper<DinnerSubscriptionDeliveryEntity> {

    @Select({
        "SELECT DISTINCT scenario FROM dinner_subscription_deliveries",
        "WHERE recipient_id = #{recipientId}",
        "AND household_id = #{householdId}",
        "AND status IN ('WAITING_EVENT', 'READY', 'SENDING', 'REJECTED')",
        "AND expires_at > #{now}"
    })
    List<String> selectBlockingScenarios(
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("now") LocalDateTime now
    );

    @Select({
        "SELECT * FROM dinner_subscription_deliveries",
        "WHERE recipient_id = #{recipientId}",
        "AND household_id = #{householdId}",
        "AND scenario = #{scenario}",
        "AND status = 'WAITING_EVENT'",
        "AND expires_at > #{now}",
        "ORDER BY id",
        "LIMIT 1 FOR UPDATE"
    })
    DinnerSubscriptionDeliveryEntity selectWaitingForUpdate(
            @Param("recipientId") Long recipientId,
            @Param("householdId") Long householdId,
            @Param("scenario") String scenario,
            @Param("now") LocalDateTime now
    );

    @Update("UPDATE dinner_subscription_deliveries "
            + "SET status = 'READY', notification_type = #{notificationType}, "
            + "reference_type = #{referenceType}, reference_id = #{referenceId}, "
            + "reference_version = #{referenceVersion}, "
            + "event_dedupe_key = #{eventDedupeKey}, "
            + "next_attempt_at = #{nextAttemptAt}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'WAITING_EVENT' "
            + "AND event_dedupe_key IS NULL")
    int markReady(
            @Param("id") Long id,
            @Param("notificationType") String notificationType,
            @Param("referenceType") String referenceType,
            @Param("referenceId") Long referenceId,
            @Param("referenceVersion") Long referenceVersion,
            @Param("eventDedupeKey") String eventDedupeKey,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Select({
        "SELECT * FROM dinner_subscription_deliveries",
        "WHERE expires_at > #{now}",
        "AND (",
        "  (status = 'READY' AND next_attempt_at <= #{now})",
        "  OR (status = 'SENDING' AND updated_at <= #{staleBefore})",
        ")",
        "ORDER BY COALESCE(next_attempt_at, updated_at), id",
        "LIMIT 1 FOR UPDATE SKIP LOCKED"
    })
    DinnerSubscriptionDeliveryEntity selectNextClaimableForUpdate(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore
    );

    @Update("UPDATE dinner_subscription_deliveries "
            + "SET status = 'SENDING', attempt_count = #{attemptCount}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = #{expectedStatus} "
            + "AND attempt_count = #{previousAttemptCount}")
    int markSending(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("previousAttemptCount") int previousAttemptCount,
            @Param("attemptCount") int attemptCount,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("UPDATE dinner_subscription_deliveries "
            + "SET status = 'SENT', sent_at = #{sentAt}, updated_at = #{sentAt}, "
            + "next_attempt_at = NULL, last_error_code = NULL "
            + "WHERE id = #{id} AND status = 'SENDING' "
            + "AND attempt_count = #{attemptCount}")
    int markSent(
            @Param("id") Long id,
            @Param("attemptCount") int attemptCount,
            @Param("sentAt") LocalDateTime sentAt
    );

    @Update("UPDATE dinner_subscription_deliveries "
            + "SET status = 'READY', next_attempt_at = #{nextAttemptAt}, "
            + "last_error_code = #{errorCode}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'SENDING' "
            + "AND attempt_count = #{attemptCount}")
    int markRetry(
            @Param("id") Long id,
            @Param("attemptCount") int attemptCount,
            @Param("errorCode") Integer errorCode,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("UPDATE dinner_subscription_deliveries "
            + "SET status = 'TERMINAL_FAILED', next_attempt_at = NULL, "
            + "last_error_code = #{errorCode}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status = 'SENDING' "
            + "AND attempt_count = #{attemptCount}")
    int markTerminal(
            @Param("id") Long id,
            @Param("attemptCount") int attemptCount,
            @Param("errorCode") Integer errorCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Delete("DELETE FROM dinner_subscription_deliveries "
            + "WHERE recipient_id = #{recipientId}")
    int deleteByRecipientId(@Param("recipientId") Long recipientId);

    @Delete("DELETE FROM dinner_subscription_deliveries "
            + "WHERE household_id = #{householdId}")
    int deleteByHouseholdId(@Param("householdId") Long householdId);

    @Delete("DELETE FROM dinner_subscription_deliveries "
            + "WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
