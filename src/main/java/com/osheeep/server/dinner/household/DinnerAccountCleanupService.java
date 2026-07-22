package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DinnerAccountCleanupService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerHouseholdDataPurger dataPurger;

    public DinnerAccountCleanupService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper,
            DinnerHouseholdDataPurger dataPurger
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.operationMapper = operationMapper;
        this.inviteMapper = inviteMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.recipeMapper = recipeMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.inventoryMapper = inventoryMapper;
        this.ingredientMapper = ingredientMapper;
        this.dataPurger = dataPurger;
    }

    /** Called only from {@link com.osheeep.server.user.AccountDeletionTransaction}. */
    public void removeUser(Long userId, LocalDateTime deletedAt) {
        requirePositive(userId, "User id");
        Objects.requireNonNull(deletedAt, "Deletion time is required");

        Set<Long> knownMembershipIds = new HashSet<>();
        DinnerHouseholdMemberEntity candidate = memberMapper.selectActiveByUserId(userId);
        if (isCandidate(candidate, userId)) {
            DinnerHouseholdEntity household =
                    householdMapper.selectByIdForUpdate(candidate.getHouseholdId());
            if (household != null) {
                List<DinnerHouseholdMemberEntity> memberships =
                        requireList(memberMapper.selectAllByHouseholdIdForUpdate(
                                household.getId()));
                memberships.stream()
                        .filter(member -> Objects.equals(userId, member.getUserId()))
                        .map(DinnerHouseholdMemberEntity::getId)
                        .filter(Objects::nonNull)
                        .forEach(knownMembershipIds::add);
                List<DinnerHouseholdMemberEntity> activeMembers =
                        validateCurrentHousehold(userId, candidate, household, memberships);
                if (activeMembers.size() == 1) {
                    dataPurger.purgeHousehold(
                            household.getId(), memberships, Set.of(userId));
                } else {
                    removeFromSurvivingHousehold(
                            userId, deletedAt, household, memberships, activeMembers);
                }
            }
        }

        purgePrivateDrafts(userId);
        purgeHistoricalMembershipAndOperationData(userId, knownMembershipIds);
        revokeRemainingOpenInvites(userId, deletedAt);
    }

    private void removeFromSurvivingHousehold(
            Long userId,
            LocalDateTime deletedAt,
            DinnerHouseholdEntity household,
            List<DinnerHouseholdMemberEntity> memberships,
            List<DinnerHouseholdMemberEntity> activeMembers
    ) {
        Long householdId = household.getId();
        DinnerHouseholdMemberEntity actorMembership = activeMembers.stream()
                .filter(member -> Objects.equals(userId, member.getUserId()))
                .findFirst()
                .orElseThrow(this::householdVersionConflict);
        DinnerHouseholdMemberEntity survivor = activeMembers.stream()
                .filter(member -> !Objects.equals(userId, member.getUserId()))
                .findFirst()
                .orElseThrow(this::householdVersionConflict);

        List<DinnerInviteCodeEntity> invites = requireList(
                inviteMapper.selectAllOpenByHouseholdIdForUpdate(householdId));
        List<DinnerMenuEntity> menus = requireList(
                menuMapper.selectUncompletedByHouseholdIdForUpdate(householdId));
        List<Long> menuIds = menus.stream().map(DinnerMenuEntity::getId).toList();
        if (menuIds.stream().anyMatch(Objects::isNull)) {
            throw householdVersionConflict();
        }
        List<DinnerMenuSelectionEntity> selections = menuIds.isEmpty()
                ? List.of()
                : requireList(selectionMapper.selectByMenuIdsForUpdate(menuIds));

        LockedPrivateDrafts privateDrafts = lockPrivateDrafts(userId);
        inventoryMapper.selectAllByHouseholdIdForUpdate(householdId);
        ingredientMapper.selectAllHouseholdIngredientsForUpdate(householdId);

        for (DinnerInviteCodeEntity invite : invites) {
            if (invite == null || invite.getId() == null
                    || !Objects.equals(householdId, invite.getHouseholdId())
                    || inviteMapper.revokeOpenInvite(
                    invite.getId(), householdId, deletedAt, "MEMBERSHIP_CHANGED") != 1) {
                throw householdVersionConflict();
            }
        }
        if (!menuIds.isEmpty()) {
            long actorSelectionCount = selections.stream()
                    .filter(selection -> selection != null
                            && Objects.equals(userId, selection.getUserId()))
                    .count();
            if (selectionMapper.deleteByMenuIdsAndUserId(menuIds, userId)
                    != actorSelectionCount) {
                throw householdVersionConflict();
            }
            if (menuMapper.resetUncompletedMenus(householdId, menuIds) != menus.size()) {
                throw householdVersionConflict();
            }
        }
        deletePrivateDrafts(privateDrafts);

        List<Long> actorMembershipIds = memberships.stream()
                .filter(member -> Objects.equals(userId, member.getUserId()))
                .map(DinnerHouseholdMemberEntity::getId)
                .toList();
        if (!actorMembershipIds.isEmpty()
                && memberMapper.deleteBatchIds(actorMembershipIds) != actorMembershipIds.size()) {
            throw householdVersionConflict();
        }
        if (OWNER.equals(actorMembership.getRole())) {
            if (memberMapper.promoteActiveMember(
                    survivor.getId(), householdId, survivor.getUserId(), survivor.getVersion())
                    != 1) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
            }
            if (householdMapper.advanceMembershipInviteAndOwnership(
                    householdId,
                    household.getVersion(),
                    household.getInviteRevision(),
                    deletedAt) != 1) {
                throw householdVersionConflict();
            }
            verifyOnlySurvivorIsOwner(householdId, survivor);
        } else if (householdMapper.advanceMembershipAndInviteRevision(
                householdId, household.getVersion(), household.getInviteRevision()) != 1) {
            throw householdVersionConflict();
        }
    }

    private void verifyOnlySurvivorIsOwner(
            Long householdId,
            DinnerHouseholdMemberEntity expectedSurvivor
    ) {
        List<DinnerHouseholdMemberEntity> remaining = requireList(
                memberMapper.selectActiveByHouseholdIdForUpdate(householdId));
        if (remaining.size() != 1) {
            throw householdVersionConflict();
        }
        DinnerHouseholdMemberEntity owner = remaining.getFirst();
        if (!Objects.equals(expectedSurvivor.getId(), owner.getId())
                || !Objects.equals(expectedSurvivor.getUserId(), owner.getUserId())
                || !OWNER.equals(owner.getRole())
                || !ACTIVE.equals(owner.getStatus())
                || !Objects.equals(expectedSurvivor.getVersion() + 1L, owner.getVersion())) {
            throw householdVersionConflict();
        }
    }

    private void purgePrivateDrafts(Long userId) {
        deletePrivateDrafts(lockPrivateDrafts(userId));
    }

    private LockedPrivateDrafts lockPrivateDrafts(Long userId) {
        List<DinnerRecipeEntity> drafts = requireList(
                recipeMapper.selectAllDraftsByCreatorForUpdate(userId));
        if (drafts.isEmpty()) {
            return new LockedPrivateDrafts(List.of(), List.of());
        }
        Long previousId = null;
        List<Long> draftIds = new ArrayList<>();
        for (DinnerRecipeEntity draft : drafts) {
            if (draft == null || draft.getId() == null
                    || !Objects.equals(userId, draft.getCreatorId())
                    || !"HOUSEHOLD".equals(draft.getScope())
                    || !"DRAFT".equals(draft.getStatus())
                    || previousId != null && draft.getId() <= previousId) {
                throw householdVersionConflict();
            }
            draftIds.add(draft.getId());
            previousId = draft.getId();
        }

        requireList(recipeMapper.selectLineageReferencesForUpdate(draftIds));
        List<DinnerRecipeMethodEntity> methods = requireList(
                methodMapper.selectByRecipeIdsForUpdate(draftIds));
        List<Long> methodIds = methods.stream()
                .map(DinnerRecipeMethodEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!methodIds.isEmpty()) {
            requireList(stepMapper.selectByMethodIdsForUpdate(methodIds));
        }
        requireList(recipeIngredientMapper.selectByRecipeIdsForUpdate(draftIds));
        return new LockedPrivateDrafts(List.copyOf(draftIds), List.copyOf(methodIds));
    }

    private void deletePrivateDrafts(LockedPrivateDrafts locked) {
        List<Long> draftIds = locked.draftIds();
        List<Long> methodIds = locked.methodIds();
        if (draftIds.isEmpty()) {
            return;
        }
        recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                .in(DinnerRecipeEntity::getSourceRecipeId, draftIds)
                .set(DinnerRecipeEntity::getSourceRecipeId, null));
        recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                .in(DinnerRecipeEntity::getRevisionOfRecipeId, draftIds)
                .set(DinnerRecipeEntity::getRevisionOfRecipeId, null)
                .set(DinnerRecipeEntity::getBasePublishedVersion, null));
        if (!methodIds.isEmpty()) {
            stepMapper.delete(Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                    .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds));
        }
        methodMapper.delete(Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                .in(DinnerRecipeMethodEntity::getRecipeId, draftIds));
        recipeIngredientMapper.delete(Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                .in(DinnerRecipeIngredientEntity::getRecipeId, draftIds));
        if (recipeMapper.deleteBatchIds(draftIds) != draftIds.size()) {
            throw householdVersionConflict();
        }
    }

    private void purgeHistoricalMembershipAndOperationData(
            Long userId,
            Set<Long> knownMembershipIds
    ) {
        List<DinnerHouseholdMemberEntity> remainingMemberships = requireList(
                memberMapper.selectAllByUserIdForUpdate(userId));
        for (DinnerHouseholdMemberEntity membership : remainingMemberships) {
            if (membership == null || membership.getId() == null
                    || !Objects.equals(userId, membership.getUserId())) {
                throw householdVersionConflict();
            }
            knownMembershipIds.add(membership.getId());
        }
        List<Long> membershipIds = knownMembershipIds.stream().sorted().toList();
        requireList(operationMapper.selectByActorOrTargetMembershipIdsForUpdate(
                userId, membershipIds));
        LambdaQueryWrapper<DinnerHouseholdOperationEntity> operationDelete =
                Wrappers.<DinnerHouseholdOperationEntity>lambdaQuery()
                        .eq(DinnerHouseholdOperationEntity::getActorId, userId);
        if (!membershipIds.isEmpty()) {
            operationDelete.or().in(
                    DinnerHouseholdOperationEntity::getTargetMemberId, membershipIds);
        }
        operationMapper.delete(operationDelete);
        if (!remainingMemberships.isEmpty()) {
            List<Long> remainingIds = remainingMemberships.stream()
                    .map(DinnerHouseholdMemberEntity::getId)
                    .toList();
            if (memberMapper.deleteBatchIds(remainingIds) != remainingIds.size()) {
                throw householdVersionConflict();
            }
        }
    }

    private void revokeRemainingOpenInvites(Long userId, LocalDateTime deletedAt) {
        inviteMapper.update(null, Wrappers.<DinnerInviteCodeEntity>lambdaUpdate()
                .eq(DinnerInviteCodeEntity::getCreatedBy, userId)
                .isNull(DinnerInviteCodeEntity::getConsumedAt)
                .isNull(DinnerInviteCodeEntity::getRevokedAt)
                .set(DinnerInviteCodeEntity::getRevokedAt, deletedAt)
                .set(DinnerInviteCodeEntity::getRevocationReason, "MEMBER_REVOKED"));
    }

    private List<DinnerHouseholdMemberEntity> validateCurrentHousehold(
            Long userId,
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdEntity household,
            List<DinnerHouseholdMemberEntity> memberships
    ) {
        if (!ACTIVE.equals(household.getStatus())
                || household.getVersion() == null || household.getVersion() < 1
                || household.getInviteRevision() == null || household.getInviteRevision() < 0) {
            throw householdVersionConflict();
        }
        List<DinnerHouseholdMemberEntity> activeMembers = new ArrayList<>();
        Set<Long> activeUsers = new HashSet<>();
        Set<Integer> activeSeats = new HashSet<>();
        Long previousId = null;
        for (DinnerHouseholdMemberEntity member : memberships) {
            if (member == null || member.getId() == null
                    || !Objects.equals(household.getId(), member.getHouseholdId())
                    || previousId != null && member.getId() <= previousId) {
                throw householdVersionConflict();
            }
            previousId = member.getId();
            if (!ACTIVE.equals(member.getStatus())) {
                continue;
            }
            if (member.getUserId() == null
                    || !(OWNER.equals(member.getRole()) || "MEMBER".equals(member.getRole()))
                    || member.getSeatNo() == null
                    || member.getSeatNo() < 1
                    || member.getSeatNo() > 2
                    || member.getVersion() == null
                    || member.getVersion() < 1
                    || !activeUsers.add(member.getUserId())
                    || !activeSeats.add(member.getSeatNo())) {
                throw householdVersionConflict();
            }
            activeMembers.add(member);
        }
        if (activeMembers.isEmpty() || activeMembers.size() > 2) {
            throw householdVersionConflict();
        }
        DinnerHouseholdMemberEntity actor = activeMembers.stream()
                .filter(member -> Objects.equals(userId, member.getUserId()))
                .findFirst()
                .orElseThrow(this::householdVersionConflict);
        if (!sameSnapshot(candidate, actor)
                || activeMembers.stream().filter(member -> OWNER.equals(member.getRole())).count()
                != 1) {
            throw householdVersionConflict();
        }
        return activeMembers;
    }

    private boolean sameSnapshot(
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdMemberEntity locked
    ) {
        return Objects.equals(candidate.getId(), locked.getId())
                && Objects.equals(candidate.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(candidate.getUserId(), locked.getUserId())
                && Objects.equals(candidate.getRole(), locked.getRole())
                && Objects.equals(candidate.getStatus(), locked.getStatus())
                && Objects.equals(candidate.getVersion(), locked.getVersion());
    }

    private boolean isCandidate(DinnerHouseholdMemberEntity candidate, Long userId) {
        return candidate != null
                && candidate.getId() != null
                && candidate.getHouseholdId() != null
                && Objects.equals(userId, candidate.getUserId())
                && ACTIVE.equals(candidate.getStatus());
    }

    private <T> List<T> requireList(List<T> rows) {
        if (rows == null) {
            throw householdVersionConflict();
        }
        return List.copyOf(rows);
    }

    private void requirePositive(Long value, String label) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private record LockedPrivateDrafts(List<Long> draftIds, List<Long> methodIds) {
    }
}
