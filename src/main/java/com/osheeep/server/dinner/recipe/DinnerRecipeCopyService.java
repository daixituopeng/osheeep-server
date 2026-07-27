package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeCopyService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetService imageAssetService;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;

    public DinnerRecipeCopyService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetService imageAssetService,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageAssetService = imageAssetService;
        this.authorizer = authorizer;
        this.queryService = queryService;
    }

    @Transactional
    public RecipeDraftResponse copy(Long userId, Long sourceRecipeId) {
        try {
            RecipeAccess access = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity source = recipeMapper.selectByIdForUpdate(sourceRecipeId);
            requirePublishedSystemRecipe(source);

            DinnerRecipeEntity draft = copyRecipe(access, source);
            recipeMapper.insert(draft);
            copyIngredients(sourceRecipeId, draft.getId());
            copyMethods(sourceRecipeId, draft.getId());
            return queryService.detail(access, draft.getId());
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void requirePublishedSystemRecipe(DinnerRecipeEntity source) {
        if (source == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        if (!"SYSTEM".equals(source.getScope())
                || !"PUBLISHED".equals(source.getStatus())
                || source.getHouseholdId() != null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private DinnerRecipeEntity copyRecipe(
            RecipeAccess access,
            DinnerRecipeEntity source
    ) {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setScope("HOUSEHOLD");
        draft.setHouseholdId(access.householdId());
        draft.setName(source.getName());
        draft.setImageAssetId(approvedImageAssetId(source.getImageAssetId()));
        draft.setCategory(source.getCategory());
        draft.setFlavor(source.getFlavor());
        draft.setServings(source.getServings());
        draft.setEstimatedMinutes(source.getEstimatedMinutes());
        draft.setCreatorId(access.userId());
        draft.setLastModifiedBy(access.userId());
        draft.setSourceRecipeId(source.getId());
        draft.setStatus("DRAFT");
        draft.setVersion(1L);
        return draft;
    }

    private Long approvedImageAssetId(Long imageAssetId) {
        if (imageAssetId == null) {
            return null;
        }
        return imageAssetService.findApprovedByIds(List.of(imageAssetId))
                .containsKey(imageAssetId) ? imageAssetId : null;
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
