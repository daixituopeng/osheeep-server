package com.osheeep.server.dinner.household;

import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DinnerHouseholdActorLabelService {

    private static final Map<String, Integer> PUBLIC_ORDER = Map.of(
            "ME", 0,
            "PARTNER", 1,
            "EXITED_MEMBER", 2,
            "DELETED_MEMBER", 3);

    private final DinnerHouseholdMemberMapper memberMapper;
    private final UserMapper userMapper;

    public DinnerHouseholdActorLabelService(
            DinnerHouseholdMemberMapper memberMapper,
            UserMapper userMapper
    ) {
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
    }

    public Map<Long, HouseholdActorResponse> resolve(
            Long householdId,
            Long currentUserId,
            Collection<Long> actorUserIds
    ) {
        List<Long> userIds = normalizedUserIds(actorUserIds);
        if (userIds.isEmpty()) {
            return Map.of();
        }
        if (householdId == null || currentUserId == null) {
            throw new IllegalArgumentException("Household actor context is required");
        }

        List<DinnerHouseholdMemberEntity> memberships =
                memberMapper.selectHistoryByHouseholdAndUserIds(householdId, userIds);
        Set<Long> activeUserIds = memberships.stream()
                .filter(membership -> "ACTIVE".equals(membership.getStatus()))
                .map(DinnerHouseholdMemberEntity::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserEntity> usersById = userMapper.selectByIds(userIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        Function.identity(),
                        (first, ignored) -> first));

        Map<Long, HouseholdActorResponse> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            result.put(userId, new HouseholdActorResponse(kind(
                    userId, currentUserId, activeUserIds, usersById)));
        }
        return Map.copyOf(result);
    }

    public List<HouseholdActorResponse> ordered(
            Collection<Long> actorUserIds,
            Map<Long, HouseholdActorResponse> resolved
    ) {
        if (actorUserIds == null || actorUserIds.isEmpty()) {
            return List.of();
        }
        if (resolved == null) {
            throw new IllegalArgumentException("Resolved household actors are required");
        }
        return new LinkedHashSet<>(actorUserIds).stream()
                .filter(Objects::nonNull)
                .map(userId -> Map.entry(userId, requireResolved(userId, resolved)))
                .sorted(Comparator
                        .comparingInt((Map.Entry<Long, HouseholdActorResponse> entry) ->
                                PUBLIC_ORDER.get(entry.getValue().kind()))
                        .thenComparingLong(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .toList();
    }

    private List<Long> normalizedUserIds(Collection<Long> actorUserIds) {
        if (actorUserIds == null || actorUserIds.isEmpty()) {
            return List.of();
        }
        return actorUserIds.stream()
                .filter(Objects::nonNull)
                .filter(userId -> userId > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private String kind(
            Long userId,
            Long currentUserId,
            Set<Long> activeUserIds,
            Map<Long, UserEntity> usersById
    ) {
        if (Objects.equals(userId, currentUserId)) {
            return "ME";
        }
        if (activeUserIds.contains(userId)) {
            return "PARTNER";
        }
        UserEntity user = usersById.get(userId);
        if (user != null && "DELETED".equals(user.getStatus())) {
            return "DELETED_MEMBER";
        }
        return "EXITED_MEMBER";
    }

    private HouseholdActorResponse requireResolved(
            Long userId,
            Map<Long, HouseholdActorResponse> resolved
    ) {
        HouseholdActorResponse actor = resolved.get(userId);
        if (actor == null) {
            throw new IllegalStateException("Unresolved household actor");
        }
        return actor;
    }
}
