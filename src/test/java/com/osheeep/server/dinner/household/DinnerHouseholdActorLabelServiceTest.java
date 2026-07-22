package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdActorLabelServiceTest {

    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private UserMapper userMapper;

    private DinnerHouseholdActorLabelService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdActorLabelService(memberMapper, userMapper);
    }

    @Test
    void resolvesAllRelationsWithOneMembershipAndOneUserBatch() {
        List<Long> userIds = List.of(7L, 8L, 9L, 10L);
        when(memberMapper.selectHistoryByHouseholdAndUserIds(11L, userIds))
                .thenReturn(List.of(
                        membership(41L, 7L, "ACTIVE"),
                        membership(42L, 8L, "ACTIVE"),
                        membership(43L, 9L, "LEFT"),
                        membership(44L, 10L, "REMOVED")));
        when(userMapper.selectByIds(userIds)).thenReturn(List.of(
                user(7L, "ACTIVE"), user(8L, "ACTIVE"),
                user(9L, "ACTIVE"), user(10L, "DELETED")));

        Map<Long, HouseholdActorResponse> result =
                service.resolve(11L, 7L, List.of(10L, 8L, 7L, 9L, 8L));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                7L, new HouseholdActorResponse("ME"),
                8L, new HouseholdActorResponse("PARTNER"),
                9L, new HouseholdActorResponse("EXITED_MEMBER"),
                10L, new HouseholdActorResponse("DELETED_MEMBER")));
        verify(memberMapper).selectHistoryByHouseholdAndUserIds(11L, userIds);
        verify(userMapper).selectByIds(userIds);
        verify(userMapper, never()).selectById(7L);
    }

    @Test
    void currentAndActiveRelationsTakePriorityOverDeletedAndExitedFallbacks() {
        List<Long> userIds = List.of(7L, 8L);
        when(memberMapper.selectHistoryByHouseholdAndUserIds(11L, userIds))
                .thenReturn(List.of(
                        membership(41L, 7L, "LEFT"),
                        membership(45L, 7L, "ACTIVE"),
                        membership(42L, 8L, "ACTIVE")));
        when(userMapper.selectByIds(userIds)).thenReturn(List.of(
                user(7L, "DELETED"), user(8L, "DELETED")));

        Map<Long, HouseholdActorResponse> result = service.resolve(11L, 7L, userIds);

        assertThat(result.get(7L).kind()).isEqualTo("ME");
        assertThat(result.get(8L).kind()).isEqualTo("PARTNER");
    }

    @Test
    void orderedActorsDeduplicateByUserAndUsePublicRelationOrder() {
        Map<Long, HouseholdActorResponse> resolved = Map.of(
                7L, new HouseholdActorResponse("ME"),
                8L, new HouseholdActorResponse("PARTNER"),
                9L, new HouseholdActorResponse("EXITED_MEMBER"),
                10L, new HouseholdActorResponse("DELETED_MEMBER"));

        List<HouseholdActorResponse> result = service.ordered(
                List.of(10L, 9L, 7L, 8L, 9L), resolved);

        assertThat(result).extracting(HouseholdActorResponse::kind)
                .containsExactly("ME", "PARTNER", "EXITED_MEMBER", "DELETED_MEMBER");
    }

    @Test
    void emptyActorSetDoesNotQueryPersistence() {
        assertThat(service.resolve(11L, 7L, List.of())).isEmpty();
        verifyNoInteractions(memberMapper, userMapper);
    }

    private DinnerHouseholdMemberEntity membership(Long id, Long userId, String status) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(11L);
        membership.setUserId(userId);
        membership.setStatus(status);
        return membership;
    }

    private UserEntity user(Long id, String status) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}
