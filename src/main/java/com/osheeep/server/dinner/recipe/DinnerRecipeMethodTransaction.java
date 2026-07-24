package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.RecipeMethodSetValidator.ValidatedMethod;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeMethodTransaction {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    public DinnerRecipeMethodTransaction(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService
    ) {
        this.recipeMapper = recipeMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    @Transactional
    public RecipeDraftResponse replace(
            Long userId,
            Long recipeId,
            long expectedVersion,
            List<ValidatedMethod> requestedMethods
    ) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity recipe = recipeMapper.selectByIdForUpdate(recipeId);
            requireEditable(access, userId, recipe, expectedVersion);

            List<DinnerRecipeMethodEntity> allMethods =
                    methodMapper.selectByRecipeIdsForUpdate(List.of(recipeId));
            Map<Long, DinnerRecipeMethodEntity> activeById = new HashMap<>();
            for (DinnerRecipeMethodEntity method : allMethods) {
                if ("ACTIVE".equals(method.getStatus())) {
                    activeById.put(method.getId(), method);
                }
            }
            requireCompleteExistingSet(activeById.keySet(), requestedMethods);

            // Drop the old generated-key default before assigning the requested one.
            activeById.values().stream()
                    .filter(method -> Boolean.TRUE.equals(method.getIsDefault()))
                    .forEach(method -> {
                        method.setIsDefault(false);
                        methodMapper.updateById(method);
                    });

            Integer defaultMinutes = null;
            for (ValidatedMethod requested : requestedMethods) {
                DinnerRecipeMethodEntity method = requested.id() == null
                        ? new DinnerRecipeMethodEntity()
                        : activeById.get(requested.id());
                if (method == null) {
                    throw invalid();
                }
                method.setRecipeId(recipeId);
                method.setName(requested.name());
                method.setCookingStyle(requested.cookingStyle());
                method.setEstimatedMinutes(requested.estimatedMinutes());
                method.setIsDefault(requested.defaultMethod());
                method.setStatus("ACTIVE");
                method.setSortOrder(requested.sortOrder());
                if (requested.id() == null) {
                    methodMapper.insert(method);
                } else {
                    methodMapper.updateById(method);
                }
                replaceSteps(method.getId(), requested.steps());
                if (requested.defaultMethod()) {
                    defaultMinutes = requested.estimatedMinutes();
                }
            }

            recipe.setEstimatedMinutes(defaultMinutes);
            recipe.setLastModifiedBy(userId);
            recipe.setVersion(recipe.getVersion() + 1L);
            recipeMapper.updateById(recipe);
            if ("PUBLISHED".equals(recipe.getStatus())) {
                notificationPublisher.toPartner(
                        access.householdId(),
                        userId,
                        DinnerNotificationType.FAMILY_RECIPE_UPDATED,
                        DinnerNotificationReferenceType.RECIPE,
                        recipeId,
                        recipe.getVersion(),
                        "recipe:" + recipeId + ":version:" + recipe.getVersion());
            }
            return queryService.detail(access, recipeId);
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requireEditable(
            RecipeAccess access,
            Long userId,
            DinnerRecipeEntity recipe,
            long expectedVersion
    ) {
        if (recipe == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        boolean editableStatus = "PUBLISHED".equals(recipe.getStatus())
                || ("DRAFT".equals(recipe.getStatus())
                && Objects.equals(userId, recipe.getCreatorId()));
        if (!editableStatus || !Objects.equals(access.householdId(), recipe.getHouseholdId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Objects.equals(recipe.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requireCompleteExistingSet(
            Set<Long> existingIds,
            List<ValidatedMethod> requestedMethods
    ) {
        Set<Long> requestedIds = new HashSet<>();
        requestedMethods.stream()
                .map(ValidatedMethod::id)
                .filter(Objects::nonNull)
                .forEach(requestedIds::add);
        if (!requestedIds.equals(existingIds)) {
            throw invalid();
        }
    }

    private void replaceSteps(Long methodId, List<String> instructions) {
        stepMapper.delete(Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                .eq(DinnerRecipeMethodStepEntity::getMethodId, methodId));
        for (int index = 0; index < instructions.size(); index++) {
            DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
            step.setMethodId(methodId);
            step.setInstruction(instructions.get(index));
            step.setSortOrder(index);
            stepMapper.insert(step);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }
}
