package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeRevisionTransaction {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;
    private final DinnerImageAssetService imageAssetService;
    private final RecipeDraftValidator validator;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    public DinnerRecipeRevisionTransaction(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService,
            DinnerImageAssetService imageAssetService,
            RecipeDraftValidator validator
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
        this.imageAssetService = imageAssetService;
        this.validator = validator;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    @Transactional
    public RecipeDraftResponse applyChecked(
            Long userId,
            Long revisionDraftId,
            long expectedVersion
    ) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity unlockedDraft = recipeMapper.selectById(revisionDraftId);
            if (unlockedDraft == null || unlockedDraft.getRevisionOfRecipeId() == null) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
            }
            List<Long> lockIds = List.of(
                    unlockedDraft.getRevisionOfRecipeId(), revisionDraftId).stream()
                    .sorted()
                    .toList();
            Map<Long, DinnerRecipeEntity> lockedById = new LinkedHashMap<>();
            for (DinnerRecipeEntity row : recipeMapper.selectByIdsForUpdate(lockIds)) {
                lockedById.put(row.getId(), row);
            }
            DinnerRecipeEntity draft = lockedById.get(revisionDraftId);
            DinnerRecipeEntity published = lockedById.get(unlockedDraft.getRevisionOfRecipeId());
            requireApplicable(access, userId, draft, published, expectedVersion);

            RecipeDraftResponse detail = queryService.detail(access, revisionDraftId);
            RecipePublishSnapshot snapshot = new RecipePublishSnapshot(
                    draft.getId(), draft.getCreatorId(), draft.getHouseholdId(), draft.getVersion(),
                    detail.name(), detail.category(), detail.flavor(), detail.servings(),
                    detail.estimatedMinutes(), draft.getImageAssetId(), detail.ingredients(),
                    detail.defaultMethod(), null);
            var issues = validator.validate(snapshot);
            if (!issues.isEmpty()) {
                throw new RecipeValidationException(issues);
            }
            if (draft.getImageAssetId() != null) {
                imageAssetService.requireApproved(draft.getImageAssetId());
            }

            List<DinnerRecipeIngredientEntity> lockedIngredients =
                    ingredientMapper.selectByRecipeIdsForUpdate(lockIds);
            List<DinnerRecipeMethodEntity> lockedMethods =
                    methodMapper.selectByRecipeIdsForUpdate(lockIds);
            applyContent(userId, published, draft, lockedIngredients, lockedMethods);
            deleteRevisionDraft(draft, lockedIngredients, lockedMethods);

            notificationPublisher.toPartner(
                    access.householdId(),
                    userId,
                    DinnerNotificationType.FAMILY_RECIPE_UPDATED,
                    DinnerNotificationReferenceType.RECIPE,
                    published.getId(),
                    published.getVersion(),
                    "recipe:" + published.getId() + ":version:" + published.getVersion());
            return queryService.detail(access, published.getId());
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requireApplicable(
            RecipeAccess access,
            Long userId,
            DinnerRecipeEntity draft,
            DinnerRecipeEntity published,
            long expectedVersion
    ) {
        if (draft == null || published == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        boolean ownsRevision = "DRAFT".equals(draft.getStatus())
                && Objects.equals(userId, draft.getCreatorId())
                && Objects.equals(access.householdId(), draft.getHouseholdId())
                && Objects.equals(published.getId(), draft.getRevisionOfRecipeId());
        boolean currentBase = "HOUSEHOLD".equals(published.getScope())
                && "PUBLISHED".equals(published.getStatus())
                && Objects.equals(access.householdId(), published.getHouseholdId())
                && Objects.equals(published.getVersion(), draft.getBasePublishedVersion());
        if (!ownsRevision || !currentBase) {
            if (ownsRevision && published != null) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
            }
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Objects.equals(draft.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void applyContent(
            Long userId,
            DinnerRecipeEntity published,
            DinnerRecipeEntity draft,
            List<DinnerRecipeIngredientEntity> lockedIngredients,
            List<DinnerRecipeMethodEntity> lockedMethods
    ) {
        published.setName(draft.getName());
        published.setCategory(draft.getCategory());
        published.setFlavor(draft.getFlavor());
        published.setServings(draft.getServings());
        published.setEstimatedMinutes(draft.getEstimatedMinutes());
        published.setImageAssetId(draft.getImageAssetId());
        published.setLastModifiedBy(userId);
        published.setVersion(published.getVersion() + 1L);
        recipeMapper.updateById(published);

        ingredientMapper.delete(Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                .eq(DinnerRecipeIngredientEntity::getRecipeId, published.getId()));
        lockedIngredients.stream()
                .filter(row -> Objects.equals(row.getRecipeId(), draft.getId()))
                .forEach(row -> {
                    DinnerRecipeIngredientEntity copy = new DinnerRecipeIngredientEntity();
                    copy.setRecipeId(published.getId());
                    copy.setIngredientId(row.getIngredientId());
                    copy.setQuantity(row.getQuantity());
                    copy.setUnit(row.getUnit());
                    copy.setIsRequired(row.getIsRequired());
                    copy.setSortOrder(row.getSortOrder());
                    ingredientMapper.insert(copy);
                });

        lockedMethods.stream()
                .filter(method -> Objects.equals(method.getRecipeId(), published.getId()))
                .filter(method -> "ACTIVE".equals(method.getStatus()))
                .filter(method -> Boolean.TRUE.equals(method.getIsDefault()))
                .findFirst()
                .ifPresent(method -> {
                    method.setEstimatedMinutes(draft.getEstimatedMinutes());
                    methodMapper.updateById(method);
                });
    }

    private void deleteRevisionDraft(
            DinnerRecipeEntity draft,
            List<DinnerRecipeIngredientEntity> lockedIngredients,
            List<DinnerRecipeMethodEntity> lockedMethods
    ) {
        List<Long> draftMethodIds = lockedMethods.stream()
                .filter(method -> Objects.equals(method.getRecipeId(), draft.getId()))
                .map(DinnerRecipeMethodEntity::getId)
                .toList();
        if (!draftMethodIds.isEmpty()) {
            stepMapper.selectByMethodIdsForUpdate(draftMethodIds);
            stepMapper.delete(Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                    .in(DinnerRecipeMethodStepEntity::getMethodId, draftMethodIds));
            methodMapper.delete(Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                    .eq(DinnerRecipeMethodEntity::getRecipeId, draft.getId()));
        }
        if (lockedIngredients.stream()
                .anyMatch(row -> Objects.equals(row.getRecipeId(), draft.getId()))) {
            ingredientMapper.delete(Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                    .eq(DinnerRecipeIngredientEntity::getRecipeId, draft.getId()));
        }
        recipeMapper.deleteById(draft.getId());
    }
}
