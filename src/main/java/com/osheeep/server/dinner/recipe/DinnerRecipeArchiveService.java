package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeArchiveService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    public DinnerRecipeArchiveService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService
    ) {
        this.recipeMapper = recipeMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    @Transactional
    public RecipeDraftResponse archive(Long userId, Long recipeId, long expectedVersion) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity recipe = recipeMapper.selectByIdForUpdate(recipeId);
            requireArchivable(access, recipe, expectedVersion);
            List<DinnerRecipeEntity> revisions =
                    recipeMapper.selectRevisionDraftsForUpdate(recipeId);
            for (DinnerRecipeEntity revision : revisions) {
                if (!Objects.equals(revision.getHouseholdId(), access.householdId())) {
                    throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
                }
                if (revision.getSourceRecipeId() == null) {
                    revision.setSourceRecipeId(recipeId);
                }
                revision.setRevisionOfRecipeId(null);
                revision.setBasePublishedVersion(null);
                revision.setVersion(revision.getVersion() + 1L);
                recipeMapper.updateById(revision);
            }

            recipe.setStatus("ARCHIVED");
            recipe.setArchivedAt(LocalDateTime.now());
            recipe.setLastModifiedBy(userId);
            recipe.setVersion(recipe.getVersion() + 1L);
            recipeMapper.updateById(recipe);
            notificationPublisher.toPartner(
                    access.householdId(),
                    userId,
                    DinnerNotificationType.FAMILY_RECIPE_UPDATED,
                    DinnerNotificationReferenceType.RECIPE,
                    recipeId,
                    recipe.getVersion(),
                    "recipe:" + recipeId + ":version:" + recipe.getVersion());
            return queryService.detail(access, recipeId);
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requireArchivable(
            RecipeAccess access,
            DinnerRecipeEntity recipe,
            long expectedVersion
    ) {
        if (recipe == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        if (!"HOUSEHOLD".equals(recipe.getScope())
                || !"PUBLISHED".equals(recipe.getStatus())
                || !Objects.equals(access.householdId(), recipe.getHouseholdId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Objects.equals(recipe.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }
}
