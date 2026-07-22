package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdDissolutionTransactionTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");
    private static final String KEY = "7b20fb9b-a868-48bf-98e5-36643b9921b1";

    @Mock private UserMapper userMapper;
    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdDataPurger dataPurger;

    private DinnerHouseholdDissolutionTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new DinnerHouseholdDissolutionTransaction(
                userMapper, identityMapper, operationMapper, householdMapper, memberMapper,
                dataPurger, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser());
    }

    @Test
    void mismatchedWechatIdentityStopsBeforeHouseholdLocks() {
        when(identityMapper.selectByUserIdForUpdate(7L))
                .thenReturn(identity("openid-other"));

        assertBusinessError(
                () -> transaction.dissolve(command(8L), "我们的小家", "openid-7"),
                ErrorCode.DINNER_HOUSEHOLD_IDENTITY_MISMATCH);

        verifyNoInteractions(householdMapper, dataPurger);
        verify(memberMapper, never()).selectActiveByUserId(any());
    }

    @Test
    void nonOwnerCannotDissolveHousehold() {
        DinnerHouseholdMemberEntity owner = member(31L, 8L, "OWNER");
        DinnerHouseholdMemberEntity actor = member(32L, 7L, "MEMBER");
        arrangeContext(actor, List.of(owner, actor));

        assertBusinessError(
                () -> transaction.dissolve(command(8L, 32L), "我们的小家", "openid-7"),
                ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);

        verifyNoInteractions(dataPurger);
    }

    @Test
    void staleHouseholdVersionWinsBeforeDestructiveWork() {
        DinnerHouseholdMemberEntity owner = member(31L, 7L, "OWNER");
        arrangeContext(owner, List.of(owner));

        assertBusinessError(
                () -> transaction.dissolve(command(7L), "我们的小家", "openid-7"),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        verifyNoInteractions(dataPurger);
    }

    @Test
    void normalizedNameMustExactlyMatchStoredHouseholdName() {
        DinnerHouseholdMemberEntity owner = member(31L, 7L, "OWNER");
        arrangeContext(owner, List.of(owner));

        assertBusinessError(
                () -> transaction.dissolve(command(8L), "另一个家", "openid-7"),
                ErrorCode.DINNER_HOUSEHOLD_NAME_MISMATCH);

        verifyNoInteractions(dataPurger);
    }

    @Test
    void ownerHardDeletesAggregateThenStoresReplayOnlyResult() {
        DinnerHouseholdMemberEntity owner = member(31L, 7L, "OWNER");
        DinnerHouseholdMemberEntity member = member(32L, 8L, "MEMBER");
        arrangeContext(owner, List.of(owner, member));
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class))).thenReturn(1);

        HouseholdMutationResponse result = transaction.dissolve(
                command(8L), "我们的小家", "openid-7");

        assertThat(result).isEqualTo(new HouseholdMutationResponse(
                "HOUSEHOLD_DISSOLUTION", false, false, null));
        verify(dataPurger).purgeHousehold(11L, List.of(owner, member), Set.of());
        ArgumentCaptor<DinnerHouseholdOperationEntity> stored =
                ArgumentCaptor.forClass(DinnerHouseholdOperationEntity.class);
        verify(operationMapper).insert(stored.capture());
        assertThat(stored.getValue().getHouseholdId()).isEqualTo(11L);
        assertThat(stored.getValue().getResultHouseholdVersion()).isNull();
        assertThat(stored.getValue().getResultPayload())
                .isEqualTo("{\"actorHasHousehold\":false}");
        assertThat(stored.getValue().getExpiresAt())
                .isEqualTo(stored.getValue().getCreatedAt().plusDays(14));
    }

    @Test
    void innerCommittedReplaySkipsIdentityAndAggregateLocks() {
        DinnerHouseholdOperationEntity existing = new DinnerHouseholdOperationEntity();
        existing.setActorId(7L);
        existing.setActorMembershipId(31L);
        existing.setOperationType("HOUSEHOLD_DISSOLUTION");
        existing.setIdempotencyKey(KEY);
        existing.setRequestFingerprint("fingerprint");
        existing.setResultSchemaVersion(1);
        existing.setResultPayload("{\"actorHasHousehold\":false}");
        existing.setExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        when(operationMapper.selectByActorAndIdempotencyKeyForUpdate(7L, KEY))
                .thenReturn(existing);

        HouseholdMutationResponse result = transaction.dissolve(
                command(8L), "我们的小家", "unused-openid");

        assertThat(result.replayed()).isTrue();
        verifyNoInteractions(identityMapper, householdMapper, dataPurger);
    }

    @Test
    void purgeFailureNeverStoresACommittedReplayResult() {
        DinnerHouseholdMemberEntity owner = member(31L, 7L, "OWNER");
        arrangeContext(owner, List.of(owner));
        doThrow(new IllegalStateException("injected purge failure"))
                .when(dataPurger).purgeHousehold(11L, List.of(owner), Set.of());

        assertThatThrownBy(() -> transaction.dissolve(
                command(8L), "我们的小家", "openid-7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected purge failure");

        verify(operationMapper, never()).insert(any(DinnerHouseholdOperationEntity.class));
    }

    private void arrangeContext(
            DinnerHouseholdMemberEntity candidate,
            List<DinnerHouseholdMemberEntity> memberships
    ) {
        when(identityMapper.selectByUserIdForUpdate(7L)).thenReturn(identity("openid-7"));
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household());
        when(memberMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(memberships);
    }

    private HouseholdOperationCommand command(Long expectedVersion) {
        return command(expectedVersion, 31L);
    }

    private HouseholdOperationCommand command(Long expectedVersion, Long membershipId) {
        return new HouseholdOperationCommand(
                7L, membershipId, expectedVersion, null, null,
                "HOUSEHOLD_DISSOLUTION", KEY, "fingerprint");
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setStatus("ACTIVE");
        return user;
    }

    private WechatUserIdentityEntity identity(String openid) {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setId(71L);
        identity.setUserId(7L);
        identity.setOpenid(openid);
        return identity;
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(11L);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(8L);
        household.setInviteRevision(4L);
        return household;
    }

    private DinnerHouseholdMemberEntity member(Long id, Long userId, String role) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(id);
        member.setHouseholdId(11L);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus("ACTIVE");
        member.setSeatNo("OWNER".equals(role) ? 1 : 2);
        member.setVersion(2L);
        member.setHistoryVisibleFrom(LocalDateTime.parse("2026-07-01T00:00:00"));
        return member;
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
