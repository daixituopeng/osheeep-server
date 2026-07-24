package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeRevisionService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;

    public DinnerRecipeRevisionService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
    }

    @Transactional
    public RecipeDraftResponse start(Long userId, Long publishedRecipeId) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity published = recipeMapper.selectByIdForUpdate(publishedRecipeId);
            requirePublishedHousehold(access, published);

            DinnerRecipeEntity existing = recipeMapper.selectRevisionDraftForUpdate(
                    publishedRecipeId, userId);
            if (existing != null) {
                return queryService.detail(access, existing.getId());
            }

            DinnerRecipeEntity revision = copyRecipe(userId, published);
            recipeMapper.insert(revision);
            copyIngredients(publishedRecipeId, revision.getId());
            copyMethods(publishedRecipeId, revision.getId());
            return queryService.detail(access, revision.getId());
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requirePublishedHousehold(
            RecipeAccess access,
            DinnerRecipeEntity published
    ) {
        if (published == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        if (!"HOUSEHOLD".equals(published.getScope())
                || !"PUBLISHED".equals(published.getStatus())
                || !Objects.equals(access.householdId(), published.getHouseholdId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private DinnerRecipeEntity copyRecipe(Long userId, DinnerRecipeEntity source) {
        DinnerRecipeEntity revision = new DinnerRecipeEntity();
        revision.setScope("HOUSEHOLD");
        revision.setHouseholdId(source.getHouseholdId());
        revision.setName(source.getName());
        revision.setImageAssetId(source.getImageAssetId());
        revision.setCategory(source.getCategory());
        revision.setFlavor(source.getFlavor());
        revision.setServings(source.getServings());
        revision.setEstimatedMinutes(source.getEstimatedMinutes());
        revision.setCreatorId(userId);
        revision.setLastModifiedBy(userId);
        revision.setSourceRecipeId(source.getSourceRecipeId());
        revision.setRevisionOfRecipeId(source.getId());
        revision.setBasePublishedVersion(source.getVersion());
        revision.setStatus("DRAFT");
        revision.setVersion(1L);
        return revision;
    }

    private void copyIngredients(Long sourceRecipeId, Long targetRecipeId) {
        List<DinnerRecipeIngredientEntity> sourceRows =
                ingredientMapper.selectByRecipeIdsForUpdate(List.of(sourceRecipeId));
        for (DinnerRecipeIngredientEntity source : sourceRows) {
            DinnerRecipeIngredientEntity copy = new DinnerRecipeIngredientEntity();
            copy.setRecipeId(targetRecipeId);
            copy.setIngredientId(source.getIngredientId());
            copy.setQuantity(source.getQuantity());
            copy.setUnit(source.getUnit());
            copy.setIsRequired(source.getIsRequired());
            copy.setSortOrder(source.getSortOrder());
            ingredientMapper.insert(copy);
        }
    }

    private void copyMethods(Long sourceRecipeId, Long targetRecipeId) {
        List<DinnerRecipeMethodEntity> sourceMethods =
                methodMapper.selectByRecipeIdsForUpdate(List.of(sourceRecipeId)).stream()
                        .filter(method -> "ACTIVE".equals(method.getStatus()))
                        .toList();
        List<Long> methodIds = sourceMethods.stream()
                .map(DinnerRecipeMethodEntity::getId)
                .toList();
        Map<Long, List<DinnerRecipeMethodStepEntity>> stepsByMethod = new HashMap<>();
        if (!methodIds.isEmpty()) {
            for (DinnerRecipeMethodStepEntity step :
                    stepMapper.selectByMethodIdsForUpdate(methodIds)) {
                stepsByMethod.computeIfAbsent(step.getMethodId(), ignored -> new ArrayList<>())
                        .add(step);
            }
        }
        for (DinnerRecipeMethodEntity source : sourceMethods) {
            DinnerRecipeMethodEntity copy = new DinnerRecipeMethodEntity();
            copy.setRecipeId(targetRecipeId);
            copy.setName(source.getName());
            copy.setCookingStyle(source.getCookingStyle());
            copy.setEstimatedMinutes(source.getEstimatedMinutes());
            copy.setIsDefault(source.getIsDefault());
            copy.setStatus("ACTIVE");
            copy.setSortOrder(source.getSortOrder());
            methodMapper.insert(copy);
            for (DinnerRecipeMethodStepEntity sourceStep :
                    stepsByMethod.getOrDefault(source.getId(), List.of())) {
                DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
                step.setMethodId(copy.getId());
                step.setInstruction(sourceStep.getInstruction());
                step.setSortOrder(sourceStep.getSortOrder());
                stepMapper.insert(step);
            }
        }
    }
}
