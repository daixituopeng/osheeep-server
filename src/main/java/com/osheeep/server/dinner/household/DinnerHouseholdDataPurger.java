package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.cooking.entity.DinnerMenuCookingDishEntity;
import com.osheeep.server.dinner.cooking.mapper.DinnerMenuCookingDishMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.notification.mapper.DinnerNotificationMapper;
import com.osheeep.server.dinner.subscription.mapper.DinnerSubscriptionDeliveryMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipePreferenceMapper;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks and removes one household aggregate while retaining personal drafts.
 * Image assets are global catalog data and are deliberately never removed here.
 */
@Service
public class DinnerHouseholdDataPurger {

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerCookingRecordMapper recordMapper;
    private final DinnerRecordDishSnapshotMapper snapshotMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private DinnerNotificationMapper notificationMapper;
    private DinnerSubscriptionDeliveryMapper subscriptionDeliveryMapper;
    private DinnerRecipePreferenceMapper recipePreferenceMapper;
    private DinnerMenuCookingDishMapper cookingDishMapper;

    public DinnerHouseholdDataPurger(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.operationMapper = operationMapper;
        this.inviteMapper = inviteMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.recordMapper = recordMapper;
        this.snapshotMapper = snapshotMapper;
        this.recipeMapper = recipeMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.inventoryMapper = inventoryMapper;
        this.ingredientMapper = ingredientMapper;
    }

    @Autowired(required = false)
    void setNotificationMapper(DinnerNotificationMapper notificationMapper) {
        this.notificationMapper = Objects.requireNonNull(notificationMapper);
    }

    @Autowired(required = false)
    void setSubscriptionDeliveryMapper(
            DinnerSubscriptionDeliveryMapper subscriptionDeliveryMapper
    ) {
        this.subscriptionDeliveryMapper =
                Objects.requireNonNull(subscriptionDeliveryMapper);
    }

    @Autowired(required = false)
    void setRecipePreferenceMapper(DinnerRecipePreferenceMapper recipePreferenceMapper) {
        this.recipePreferenceMapper = Objects.requireNonNull(recipePreferenceMapper);
    }

    @Autowired
    void setCookingDishMapper(DinnerMenuCookingDishMapper cookingDishMapper) {
        this.cookingDishMapper = Objects.requireNonNull(cookingDishMapper);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeHousehold(
            Long householdId,
            List<DinnerHouseholdMemberEntity> lockedMemberships,
            Set<Long> draftCreatorIdsToDelete
    ) {
        requirePositive(householdId, "Household id");
        validateLockedMemberships(householdId, lockedMemberships);
        Set<Long> deletedDraftCreators = draftCreatorIdsToDelete == null
                ? Set.of()
                : Set.copyOf(draftCreatorIdsToDelete);

        // The parent transaction has already locked user, identity, household and members.
        // From here the lock order is fixed for every destructive lifecycle.
        List<?> invites = requireList(inviteMapper.selectAllByHouseholdIdForUpdate(householdId));
        List<DinnerMenuEntity> menus = requireList(
                menuMapper.selectAllByHouseholdIdForUpdate(householdId));
        List<Long> menuIds = ids(menus.stream().map(DinnerMenuEntity::getId).toList(), "menu");
        List<DinnerMenuSelectionEntity> selections = menuIds.isEmpty()
                ? List.of()
                : requireList(selectionMapper.selectByMenuIdsForUpdate(menuIds));
        List<DinnerMenuCookingDishEntity> cookingDishes =
                menuIds.isEmpty() || cookingDishMapper == null
                        ? List.of()
                        : requireList(cookingDishMapper.selectByMenuIdsForUpdate(menuIds));
        List<DinnerMenuActionEntity> actions = menuIds.isEmpty()
                ? List.of()
                : requireList(actionMapper.selectByMenuIdsForUpdate(menuIds));
        List<DinnerCookingRecordEntity> records = requireList(
                recordMapper.selectAllByHouseholdIdForUpdate(householdId));
        List<Long> recordIds = ids(
                records.stream().map(DinnerCookingRecordEntity::getId).toList(), "record");
        List<DinnerRecordDishSnapshotEntity> snapshots = recordIds.isEmpty()
                ? List.of()
                : requireList(snapshotMapper.selectByRecordIdsForUpdate(recordIds));

        LockedRecipeRows lockedRecipeRows = lockRecipeRows(householdId);
        List<DinnerRecipeEntity> recipes = lockedRecipeRows.householdRecipes();
        List<Long> recipeIds = ids(
                recipes.stream().map(DinnerRecipeEntity::getId).toList(), "recipe");
        List<DinnerRecipeEntity> lineageReferences = lockedRecipeRows.lineageReferences();
        Map<Long, DinnerRecipeEntity> externalSources = lockedRecipeRows.externalSources();
        List<DinnerRecipeMethodEntity> methods = recipeIds.isEmpty()
                ? List.of()
                : requireList(methodMapper.selectByRecipeIdsForUpdate(recipeIds));
        List<Long> methodIds = ids(
                methods.stream().map(DinnerRecipeMethodEntity::getId).toList(), "recipe method");
        List<DinnerRecipeMethodStepEntity> steps = methodIds.isEmpty()
                ? List.of()
                : requireList(stepMapper.selectByMethodIdsForUpdate(methodIds));
        List<DinnerRecipeIngredientEntity> recipeIngredients = recipeIds.isEmpty()
                ? List.of()
                : requireList(recipeIngredientMapper.selectByRecipeIdsForUpdate(recipeIds));
        List<DinnerRecipePreferenceEntity> recipePreferences =
                recipePreferenceMapper == null
                        ? List.of()
                        : requireList(recipePreferenceMapper
                                .selectByHouseholdIdForUpdate(householdId));
        List<DinnerHouseholdInventoryEntity> inventory = requireList(
                inventoryMapper.selectAllByHouseholdIdForUpdate(householdId));
        List<DinnerIngredientEntity> householdIngredients = requireList(
                ingredientMapper.selectAllHouseholdIngredientsForUpdate(householdId));
        List<?> operations = requireList(
                operationMapper.selectAllByHouseholdIdForUpdate(householdId));

        // Keep references live so mocks and reviewers can verify that every selected row was
        // locked before the first delete. Structural validation below also rejects corrupt sets.
        validateAggregateRows(householdId, menus, selections, cookingDishes, actions,
                records, snapshots,
                recipes, lineageReferences, methods, steps, recipeIngredients, inventory,
                householdIngredients, operations, invites);
        validateRecipePreferences(householdId, recipePreferences);

        Set<Long> deletingRecipeIds = new HashSet<>();
        List<DinnerRecipeEntity> retainedDrafts = recipes.stream()
                .filter(recipe -> isRetainedDraft(recipe, deletedDraftCreators))
                .toList();
        Set<Long> retainedDraftIds = new HashSet<>(
                retainedDrafts.stream().map(DinnerRecipeEntity::getId).toList());
        for (Long recipeId : recipeIds) {
            if (!retainedDraftIds.contains(recipeId)) {
                deletingRecipeIds.add(recipeId);
            }
        }

        List<Long> deletingIds = deletingRecipeIds.stream().sorted().toList();
        List<Long> retainedIds = retainedDraftIds.stream().sorted().toList();
        List<Long> householdIngredientIds = ids(householdIngredients.stream()
                .map(DinnerIngredientEntity::getId).toList(), "household ingredient");
        if (!householdIngredientIds.isEmpty() && !retainedIds.isEmpty()) {
            recipeIngredientMapper.delete(Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                    .in(DinnerRecipeIngredientEntity::getRecipeId, retainedIds)
                    .in(DinnerRecipeIngredientEntity::getIngredientId, householdIngredientIds));
        }
        detachRetainedDrafts(householdId, retainedDrafts, externalSources, deletingRecipeIds);

        deleteMenuAndRecordData(householdId, menuIds, recordIds);
        deleteRecipePreferences(
                recipePreferences,
                () -> recipePreferenceMapper.deleteByHouseholdId(householdId));
        inviteMapper.delete(Wrappers.lambdaQuery(
                com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity.class)
                .eq(com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity::getHouseholdId,
                        householdId));
        inventoryMapper.delete(Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                .eq(DinnerHouseholdInventoryEntity::getHouseholdId, householdId));

        clearLineageReferences(deletingIds);
        deleteRecipeData(deletingIds, methods);
        if (!deletingIds.isEmpty()) {
            recipeMapper.deleteBatchIds(deletingIds);
        }
        ingredientMapper.delete(Wrappers.<DinnerIngredientEntity>lambdaQuery()
                .eq(DinnerIngredientEntity::getHouseholdId, householdId));

        memberMapper.delete(Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId));
        if (householdMapper.deleteById(householdId) != 1) {
            throw new IllegalStateException("Expected exactly one household row to be deleted");
        }
        operationMapper.delete(Wrappers.lambdaQuery(
                com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity.class)
                .eq(com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity::getHouseholdId,
                        householdId));
        if (notificationMapper != null) {
            notificationMapper.deleteByHouseholdId(householdId);
        }
        if (subscriptionDeliveryMapper != null) {
            subscriptionDeliveryMapper.deleteByHouseholdId(householdId);
        }
    }

    private LockedRecipeRows lockRecipeRows(Long householdId) {
        List<DinnerRecipeEntity> discoveredHouseholdRecipes =
                requireList(recipeMapper.selectByHouseholdId(householdId));
        List<Long> householdRecipeIds = sortedRecipeIds(
                discoveredHouseholdRecipes, "household recipe");
        Set<Long> householdRecipeIdSet = Set.copyOf(householdRecipeIds);
        List<DinnerRecipeEntity> discoveredLineageReferences =
                householdRecipeIds.isEmpty()
                        ? List.of()
                        : requireList(recipeMapper.selectList(
                                Wrappers.<DinnerRecipeEntity>lambdaQuery()
                                        .and(query -> query
                                                .in(DinnerRecipeEntity::getSourceRecipeId,
                                                        householdRecipeIds)
                                                .or()
                                                .in(DinnerRecipeEntity::getRevisionOfRecipeId,
                                                        householdRecipeIds))
                                        .orderByAsc(DinnerRecipeEntity::getId)));
        List<Long> lineageReferenceIds = sortedRecipeIds(
                discoveredLineageReferences, "lineage reference");
        List<Long> externalSourceIds = discoveredHouseholdRecipes.stream()
                .map(DinnerRecipeEntity::getSourceRecipeId)
                .filter(Objects::nonNull)
                .filter(id -> !householdRecipeIdSet.contains(id))
                .distinct()
                .sorted()
                .toList();

        TreeSet<Long> relatedRecipeIdSet = new TreeSet<>();
        relatedRecipeIdSet.addAll(householdRecipeIds);
        relatedRecipeIdSet.addAll(lineageReferenceIds);
        relatedRecipeIdSet.addAll(externalSourceIds);
        List<Long> relatedRecipeIds = List.copyOf(relatedRecipeIdSet);
        List<DinnerRecipeEntity> lockedRows = relatedRecipeIds.isEmpty()
                ? List.of()
                : requireList(recipeMapper.selectByIdsForUpdate(relatedRecipeIds));
        Map<Long, DinnerRecipeEntity> lockedById = indexLockedRecipes(
                lockedRows, relatedRecipeIdSet);

        List<DinnerRecipeEntity> householdRecipes = householdRecipeIds.stream()
                .map(recipeId -> requireLockedRecipe(lockedById, recipeId, "household recipe"))
                .toList();
        List<DinnerRecipeEntity> lineageReferences = lineageReferenceIds.stream()
                .map(lockedById::get)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, DinnerRecipeEntity> externalSources = new HashMap<>();
        for (DinnerRecipeEntity recipe : householdRecipes) {
            Long sourceId = recipe.getSourceRecipeId();
            if (sourceId == null || householdRecipeIdSet.contains(sourceId)) {
                continue;
            }
            if (!relatedRecipeIdSet.contains(sourceId)) {
                throw new IllegalStateException(
                        "Household recipe source changed during aggregate lock");
            }
            DinnerRecipeEntity source = lockedById.get(sourceId);
            if (source != null) {
                externalSources.put(sourceId, source);
            }
        }

        return new LockedRecipeRows(
                householdRecipes,
                lineageReferences,
                Map.copyOf(externalSources));
    }

    private Map<Long, DinnerRecipeEntity> indexLockedRecipes(
            List<DinnerRecipeEntity> lockedRows,
            Set<Long> requestedIds
    ) {
        Map<Long, DinnerRecipeEntity> lockedById = new HashMap<>();
        for (DinnerRecipeEntity recipe : lockedRows) {
            if (recipe == null
                    || recipe.getId() == null
                    || !requestedIds.contains(recipe.getId())
                    || lockedById.put(recipe.getId(), recipe) != null) {
                throw new IllegalStateException("Related recipe lock set is invalid");
            }
        }
        return lockedById;
    }

    private DinnerRecipeEntity requireLockedRecipe(
            Map<Long, DinnerRecipeEntity> lockedById,
            Long recipeId,
            String label
    ) {
        DinnerRecipeEntity recipe = lockedById.get(recipeId);
        if (recipe == null) {
            throw new IllegalStateException("Locked " + label + " row is missing");
        }
        return recipe;
    }

    private List<Long> sortedRecipeIds(
            List<DinnerRecipeEntity> recipes,
            String label
    ) {
        if (recipes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Discovered " + label + " rows are invalid");
        }
        return ids(
                recipes.stream().map(DinnerRecipeEntity::getId).toList(), label)
                .stream()
                .sorted()
                .toList();
    }

    private void deleteMenuAndRecordData(
            Long householdId,
            List<Long> menuIds,
            List<Long> recordIds
    ) {
        if (!recordIds.isEmpty()) {
            snapshotMapper.delete(Wrappers.<DinnerRecordDishSnapshotEntity>lambdaQuery()
                    .in(DinnerRecordDishSnapshotEntity::getRecordId, recordIds));
        }
        recordMapper.delete(Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                .eq(DinnerCookingRecordEntity::getHouseholdId, householdId));
        if (!menuIds.isEmpty()) {
            if (cookingDishMapper != null) {
                cookingDishMapper.delete(Wrappers.<DinnerMenuCookingDishEntity>lambdaQuery()
                        .in(DinnerMenuCookingDishEntity::getMenuId, menuIds));
            }
            actionMapper.delete(Wrappers.<DinnerMenuActionEntity>lambdaQuery()
                    .in(DinnerMenuActionEntity::getMenuId, menuIds));
            selectionMapper.delete(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                    .in(DinnerMenuSelectionEntity::getMenuId, menuIds));
        }
        menuMapper.delete(Wrappers.<DinnerMenuEntity>lambdaQuery()
                .eq(DinnerMenuEntity::getHouseholdId, householdId));
    }

    private void deleteRecipeData(
            List<Long> deletingRecipeIds,
            List<DinnerRecipeMethodEntity> methods
    ) {
        if (deletingRecipeIds.isEmpty()) {
            return;
        }
        List<Long> deletingMethodIds = methods.stream()
                .filter(method -> deletingRecipeIds.contains(method.getRecipeId()))
                .map(DinnerRecipeMethodEntity::getId)
                .sorted()
                .toList();
        if (!deletingMethodIds.isEmpty()) {
            stepMapper.delete(Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                    .in(DinnerRecipeMethodStepEntity::getMethodId, deletingMethodIds));
        }
        methodMapper.delete(Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                .in(DinnerRecipeMethodEntity::getRecipeId, deletingRecipeIds));
        recipeIngredientMapper.delete(Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                .in(DinnerRecipeIngredientEntity::getRecipeId, deletingRecipeIds));
    }

    private void clearLineageReferences(List<Long> deletingRecipeIds) {
        if (deletingRecipeIds.isEmpty()) {
            return;
        }
        recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                .in(DinnerRecipeEntity::getSourceRecipeId, deletingRecipeIds)
                .set(DinnerRecipeEntity::getSourceRecipeId, null));
        recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                .in(DinnerRecipeEntity::getRevisionOfRecipeId, deletingRecipeIds)
                .set(DinnerRecipeEntity::getRevisionOfRecipeId, null)
                .set(DinnerRecipeEntity::getBasePublishedVersion, null));
    }

    private void detachRetainedDrafts(
            Long householdId,
            List<DinnerRecipeEntity> retainedDrafts,
            Map<Long, DinnerRecipeEntity> externalSources,
            Set<Long> deletingRecipeIds
    ) {
        for (DinnerRecipeEntity draft : retainedDrafts) {
            Long retainedSourceId = draft.getSourceRecipeId();
            DinnerRecipeEntity source = retainedSourceId == null
                    ? null
                    : externalSources.get(retainedSourceId);
            if (retainedSourceId == null
                    || deletingRecipeIds.contains(retainedSourceId)
                    || source == null
                    || !"SYSTEM".equals(source.getScope())) {
                retainedSourceId = null;
            }
            if (recipeMapper.detachOwnedDraft(
                    draft.getId(), householdId, draft.getCreatorId(), draft.getVersion(),
                    retainedSourceId) != 1) {
                throw new IllegalStateException("Personal draft changed during household purge");
            }
        }
    }

    private boolean isRetainedDraft(
            DinnerRecipeEntity recipe,
            Set<Long> deletedDraftCreators
    ) {
        return recipe != null
                && "HOUSEHOLD".equals(recipe.getScope())
                && "DRAFT".equals(recipe.getStatus())
                && recipe.getCreatorId() != null
                && !deletedDraftCreators.contains(recipe.getCreatorId());
    }

    private void validateLockedMemberships(
            Long householdId,
            List<DinnerHouseholdMemberEntity> memberships
    ) {
        List<DinnerHouseholdMemberEntity> safe = requireList(memberships);
        Long previousId = null;
        for (DinnerHouseholdMemberEntity member : safe) {
            if (member == null || member.getId() == null
                    || !Objects.equals(householdId, member.getHouseholdId())
                    || previousId != null && member.getId() <= previousId) {
                throw new IllegalStateException("Locked household membership set is invalid");
            }
            previousId = member.getId();
        }
    }

    private void validateAggregateRows(
            Long householdId,
            List<DinnerMenuEntity> menus,
            List<DinnerMenuSelectionEntity> selections,
            List<DinnerMenuCookingDishEntity> cookingDishes,
            List<DinnerMenuActionEntity> actions,
            List<DinnerCookingRecordEntity> records,
            List<DinnerRecordDishSnapshotEntity> snapshots,
            List<DinnerRecipeEntity> recipes,
            List<DinnerRecipeEntity> lineageReferences,
            List<DinnerRecipeMethodEntity> methods,
            List<DinnerRecipeMethodStepEntity> steps,
            List<DinnerRecipeIngredientEntity> recipeIngredients,
            List<DinnerHouseholdInventoryEntity> inventory,
            List<DinnerIngredientEntity> ingredients,
            List<?> operations,
            List<?> invites
    ) {
        if (menus.stream().anyMatch(row -> row == null
                || !Objects.equals(householdId, row.getHouseholdId()))
                || records.stream().anyMatch(row -> row == null
                || !Objects.equals(householdId, row.getHouseholdId()))
                || recipes.stream().anyMatch(row -> row == null
                || !Objects.equals(householdId, row.getHouseholdId()))
                || inventory.stream().anyMatch(row -> row == null
                || !Objects.equals(householdId, row.getHouseholdId()))
                || ingredients.stream().anyMatch(row -> row == null
                || !Objects.equals(householdId, row.getHouseholdId()))
                || selections.stream().anyMatch(Objects::isNull)
                || cookingDishes.stream().anyMatch(row -> row == null
                        || row.getMenuId() == null
                        || menus.stream().noneMatch(menu ->
                                Objects.equals(menu.getId(), row.getMenuId())))
                || actions.stream().anyMatch(Objects::isNull)
                || snapshots.stream().anyMatch(Objects::isNull)
                || lineageReferences.stream().anyMatch(Objects::isNull)
                || methods.stream().anyMatch(Objects::isNull)
                || steps.stream().anyMatch(Objects::isNull)
                || recipeIngredients.stream().anyMatch(Objects::isNull)
                || operations.stream().anyMatch(Objects::isNull)
                || invites.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Locked household aggregate is invalid");
        }
    }

    private void validateRecipePreferences(
            Long householdId,
            List<DinnerRecipePreferenceEntity> preferences
    ) {
        Long previousId = null;
        for (DinnerRecipePreferenceEntity preference : preferences) {
            if (preference == null
                    || preference.getId() == null
                    || !Objects.equals(householdId, preference.getHouseholdId())
                    || previousId != null && preference.getId() <= previousId) {
                throw new IllegalStateException(
                        "Locked household recipe preference set is invalid");
            }
            previousId = preference.getId();
        }
    }

    private void deleteRecipePreferences(
            List<DinnerRecipePreferenceEntity> preferences,
            java.util.function.IntSupplier delete
    ) {
        if (recipePreferenceMapper != null && delete.getAsInt() != preferences.size()) {
            throw new IllegalStateException(
                    "Household recipe preferences changed during household purge");
        }
    }

    private <T> List<T> requireList(List<T> values) {
        if (values == null) {
            throw new IllegalStateException("Household aggregate lock returned null");
        }
        return List.copyOf(values);
    }

    private List<Long> ids(List<Long> values, String label) {
        if (values.stream().anyMatch(Objects::isNull)
                || new HashSet<>(values).size() != values.size()) {
            throw new IllegalStateException("Locked " + label + " ids are invalid");
        }
        return List.copyOf(values);
    }

    private void requirePositive(Long value, String label) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private record LockedRecipeRows(
            List<DinnerRecipeEntity> householdRecipes,
            List<DinnerRecipeEntity> lineageReferences,
            Map<Long, DinnerRecipeEntity> externalSources
    ) {
    }
}
