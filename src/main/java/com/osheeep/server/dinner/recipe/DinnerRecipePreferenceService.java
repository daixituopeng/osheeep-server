package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceResponse;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipePreferenceRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipePreferenceMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipePreferenceService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipePreferenceMapper preferenceMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipePreferenceAggregator aggregator;

    public DinnerRecipePreferenceService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipePreferenceMapper preferenceMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipePreferenceAggregator aggregator
    ) {
        this.recipeMapper = recipeMapper;
        this.preferenceMapper = preferenceMapper;
        this.authorizer = authorizer;
        this.aggregator = aggregator;
    }

    @Transactional
    public RecipePreferenceResponse update(
            Long userId,
            Long recipeId,
            UpdateRecipePreferenceRequest request
    ) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            if (access.membershipId() == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            DinnerRecipeEntity recipe = recipeMapper.selectByIdForUpdate(recipeId);
            requirePreferenceVisible(access, recipe);
            DinnerRecipePreferenceEntity current =
                    preferenceMapper.selectByMembershipAndRecipeForUpdate(
                            access.membershipId(), recipeId);
            if (current == null) {
                insert(access, recipeId, request);
            } else {
                update(access, recipeId, request, current);
            }
            return currentSummary(access, recipeId);
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw preferenceConflict();
        }
    }

    private void insert(
            RecipeAccess access,
            Long recipeId,
            UpdateRecipePreferenceRequest request
    ) {
        if (!Long.valueOf(0L).equals(request.version())) {
            throw preferenceConflict();
        }
        DinnerRecipePreferenceEntity preference = new DinnerRecipePreferenceEntity();
        preference.setHouseholdId(access.householdId());
        preference.setMembershipId(access.membershipId());
        preference.setUserId(access.userId());
        preference.setRecipeId(recipeId);
        preference.setPreference(request.preference().name());
        preference.setVersion(1L);
        if (preferenceMapper.insert(preference) != 1) {
            throw new IllegalStateException("Recipe preference was not stored");
        }
    }

    private void update(
            RecipeAccess access,
            Long recipeId,
            UpdateRecipePreferenceRequest request,
            DinnerRecipePreferenceEntity current
    ) {
        if (!Objects.equals(access.householdId(), current.getHouseholdId())
                || !Objects.equals(access.membershipId(), current.getMembershipId())
                || !Objects.equals(access.userId(), current.getUserId())
                || !Objects.equals(recipeId, current.getRecipeId())
                || !Objects.equals(request.version(), current.getVersion())) {
            throw preferenceConflict();
        }
        if (request.preference().name().equals(current.getPreference())) {
            return;
        }
        if (preferenceMapper.updatePreference(
                current.getId(),
                access.householdId(),
                access.membershipId(),
                access.userId(),
                recipeId,
                current.getVersion(),
                request.preference().name()) != 1) {
            throw preferenceConflict();
        }
    }

    private RecipePreferenceResponse currentSummary(
            RecipeAccess access,
            Long recipeId
    ) {
        List<DinnerRecipePreferenceEntity> rows =
                preferenceMapper.selectActiveByHouseholdAndRecipeIds(
                        access.householdId(), List.of(recipeId));
        return aggregator.aggregate(List.of(recipeId), access.userId(), rows)
                .get(recipeId);
    }

    private void requirePreferenceVisible(
            RecipeAccess access,
            DinnerRecipeEntity recipe
    ) {
        boolean visibleSystem = recipe != null
                && "SYSTEM".equals(recipe.getScope())
                && recipe.getHouseholdId() == null;
        boolean visibleHousehold = recipe != null
                && "HOUSEHOLD".equals(recipe.getScope())
                && Objects.equals(access.householdId(), recipe.getHouseholdId());
        if (recipe == null
                || !"PUBLISHED".equals(recipe.getStatus())
                || !(visibleSystem || visibleHousehold)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
    }

    private BusinessException preferenceConflict() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_PREFERENCE_VERSION_CONFLICT);
    }
}
