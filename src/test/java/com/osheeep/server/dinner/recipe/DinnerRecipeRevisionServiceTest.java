package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeRevisionServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;

    @Test
    void returnsTheUsersExistingRevisionWithoutCreatingAnother() {
        DinnerRecipeRevisionService service = service();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity published = published();
        DinnerRecipeEntity existing = new DinnerRecipeEntity();
        existing.setId(201L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(published);
        when(recipeMapper.selectRevisionDraftForUpdate(101L, 7L)).thenReturn(existing);
        when(queryService.detail(access, 201L)).thenReturn(revisionResponse());

        assertThat(service.start(7L, 101L).id()).isEqualTo(201L);

        verify(recipeMapper, never()).insert(any(DinnerRecipeEntity.class));
        verify(ingredientMapper, never()).insert(any(DinnerRecipeIngredientEntity.class));
    }

    @Test
    void clonesTheCompletePublishedAggregateIntoAPrivateRevision() {
        DinnerRecipeRevisionService service = service();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity published = published();
        DinnerRecipeIngredientEntity ingredient = new DinnerRecipeIngredientEntity();
        ingredient.setRecipeId(101L);
        ingredient.setIngredientId(31L);
        ingredient.setQuantity(BigDecimal.valueOf(2));
        ingredient.setUnit("个");
        ingredient.setIsRequired(true);
        ingredient.setSortOrder(0);
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(301L);
        method.setRecipeId(101L);
        method.setName("家常炒");
        method.setCookingStyle("炒");
        method.setEstimatedMinutes(15);
        method.setIsDefault(true);
        method.setStatus("ACTIVE");
        method.setSortOrder(0);
        DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
        step.setMethodId(301L);
        step.setInstruction("炒熟");
        step.setSortOrder(0);

        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(published);
        when(recipeMapper.selectRevisionDraftForUpdate(101L, 7L)).thenReturn(null);
        when(recipeMapper.insert(any(DinnerRecipeEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeEntity>getArgument(0).setId(201L);
            return 1;
        });
        when(ingredientMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(ingredient));
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(method));
        when(stepMapper.selectByMethodIdsForUpdate(List.of(301L))).thenReturn(List.of(step));
        when(methodMapper.insert(any(DinnerRecipeMethodEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeMethodEntity>getArgument(0).setId(401L);
            return 1;
        });
        when(queryService.detail(access, 201L)).thenReturn(revisionResponse());

        RecipeDraftResponse result = service.start(7L, 101L);

        assertThat(result.revisionOfRecipeId()).isEqualTo(101L);
        verify(recipeMapper).insert(any(DinnerRecipeEntity.class));
        verify(ingredientMapper).insert(any(DinnerRecipeIngredientEntity.class));
        verify(methodMapper).insert(any(DinnerRecipeMethodEntity.class));
        verify(stepMapper).insert(any(DinnerRecipeMethodStepEntity.class));
    }

    private DinnerRecipeRevisionService service() {
        return new DinnerRecipeRevisionService(
                recipeMapper, ingredientMapper, methodMapper, stepMapper,
                authorizer, queryService);
    }

    private DinnerRecipeEntity published() {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(101L);
        recipe.setScope("HOUSEHOLD");
        recipe.setStatus("PUBLISHED");
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(8L);
        recipe.setLastModifiedBy(8L);
        recipe.setVersion(8L);
        recipe.setName("番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setServings(2);
        recipe.setEstimatedMinutes(15);
        recipe.setImageAssetId(9L);
        return recipe;
    }

    private RecipeDraftResponse revisionResponse() {
        return new RecipeDraftResponse(
                201L, "DRAFT", 1L, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(), null, List.of(), null, List.of(), null, 101L, 8L);
    }
}
