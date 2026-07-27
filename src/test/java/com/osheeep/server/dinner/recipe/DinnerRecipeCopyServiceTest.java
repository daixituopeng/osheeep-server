package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeCopyServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerImageAssetService imageAssetService;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;

    @Test
    void copiesThePublishedSystemAggregateIntoAnIndependentPrivateDraft() {
        DinnerRecipeCopyService service = service();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity source = publishedSystemRecipe();
        DinnerRecipeIngredientEntity ingredient = new DinnerRecipeIngredientEntity();
        ingredient.setRecipeId(101L);
        ingredient.setIngredientId(31L);
        ingredient.setQuantity(BigDecimal.valueOf(2));
        ingredient.setUnit("个");
        ingredient.setIsRequired(true);
        ingredient.setSortOrder(0);
        DinnerRecipeMethodEntity activeMethod = method(301L, "ACTIVE");
        DinnerRecipeMethodEntity archivedMethod = method(302L, "ARCHIVED");
        DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
        step.setMethodId(301L);
        step.setInstruction("炒熟");
        step.setSortOrder(0);

        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(source);
        when(imageAssetService.findApprovedByIds(List.of(9L)))
                .thenReturn(Map.of(9L, approvedImage()));
        when(recipeMapper.insert(any(DinnerRecipeEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeEntity>getArgument(0).setId(201L);
            return 1;
        });
        when(ingredientMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(ingredient));
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeMethod, archivedMethod));
        when(stepMapper.selectByMethodIdsForUpdate(List.of(301L))).thenReturn(List.of(step));
        when(methodMapper.insert(any(DinnerRecipeMethodEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeMethodEntity>getArgument(0).setId(401L);
            return 1;
        });
        when(queryService.detail(access, 201L)).thenReturn(copiedDraftResponse());

        RecipeDraftResponse result = service.copy(7L, 101L);

        assertThat(result.id()).isEqualTo(201L);
        ArgumentCaptor<DinnerRecipeEntity> recipeCaptor =
                ArgumentCaptor.forClass(DinnerRecipeEntity.class);
        verify(recipeMapper).insert(recipeCaptor.capture());
        DinnerRecipeEntity draft = recipeCaptor.getValue();
        assertThat(draft.getScope()).isEqualTo("HOUSEHOLD");
        assertThat(draft.getHouseholdId()).isEqualTo(70L);
        assertThat(draft.getName()).isEqualTo("番茄炒蛋");
        assertThat(draft.getImagePath()).isNull();
        assertThat(draft.getImageAssetId()).isEqualTo(9L);
        assertThat(draft.getCreatorId()).isEqualTo(7L);
        assertThat(draft.getLastModifiedBy()).isEqualTo(7L);
        assertThat(draft.getSourceRecipeId()).isEqualTo(101L);
        assertThat(draft.getRevisionOfRecipeId()).isNull();
        assertThat(draft.getBasePublishedVersion()).isNull();
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getVersion()).isEqualTo(1L);

        ArgumentCaptor<DinnerRecipeIngredientEntity> ingredientCaptor =
                ArgumentCaptor.forClass(DinnerRecipeIngredientEntity.class);
        verify(ingredientMapper).insert(ingredientCaptor.capture());
        assertThat(ingredientCaptor.getValue().getRecipeId()).isEqualTo(201L);
        assertThat(ingredientCaptor.getValue().getIngredientId()).isEqualTo(31L);

        ArgumentCaptor<DinnerRecipeMethodEntity> methodCaptor =
                ArgumentCaptor.forClass(DinnerRecipeMethodEntity.class);
        verify(methodMapper).insert(methodCaptor.capture());
        assertThat(methodCaptor.getValue().getRecipeId()).isEqualTo(201L);
        assertThat(methodCaptor.getValue().getName()).isEqualTo("家常炒");

        ArgumentCaptor<DinnerRecipeMethodStepEntity> stepCaptor =
                ArgumentCaptor.forClass(DinnerRecipeMethodStepEntity.class);
        verify(stepMapper).insert(stepCaptor.capture());
        assertThat(stepCaptor.getValue().getMethodId()).isEqualTo(401L);
        assertThat(stepCaptor.getValue().getInstruction()).isEqualTo("炒熟");
    }

    @Test
    void dropsALegacyOrUnapprovedSystemImageInsteadOfBypassingTheLibraryGate() {
        DinnerRecipeCopyService service = service();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity source = publishedSystemRecipe();
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(source);
        when(imageAssetService.findApprovedByIds(List.of(9L))).thenReturn(Map.of());
        when(recipeMapper.insert(any(DinnerRecipeEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeEntity>getArgument(0).setId(201L);
            return 1;
        });
        when(ingredientMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of());
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L))).thenReturn(List.of());
        when(queryService.detail(access, 201L)).thenReturn(copiedDraftResponse());

        service.copy(7L, 101L);

        ArgumentCaptor<DinnerRecipeEntity> captor =
                ArgumentCaptor.forClass(DinnerRecipeEntity.class);
        verify(recipeMapper).insert(captor.capture());
        assertThat(captor.getValue().getImagePath()).isNull();
        assertThat(captor.getValue().getImageAssetId()).isNull();
    }

    @Test
    void rejectsMissingAndNonPublishedSystemSourcesBeforeCreatingAnything() {
        DinnerRecipeCopyService service = service();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.copy(7L, 404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_NOT_FOUND));

        DinnerRecipeEntity householdRecipe = publishedSystemRecipe();
        householdRecipe.setScope("HOUSEHOLD");
        householdRecipe.setHouseholdId(70L);
        when(recipeMapper.selectByIdForUpdate(102L)).thenReturn(householdRecipe);

        assertThatThrownBy(() -> service.copy(7L, 102L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        DinnerRecipeEntity archivedSystemRecipe = publishedSystemRecipe();
        archivedSystemRecipe.setStatus("ARCHIVED");
        when(recipeMapper.selectByIdForUpdate(103L)).thenReturn(archivedSystemRecipe);

        assertThatThrownBy(() -> service.copy(7L, 103L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(recipeMapper, never()).insert(any(DinnerRecipeEntity.class));
    }

    private DinnerRecipeCopyService service() {
        return new DinnerRecipeCopyService(
                recipeMapper, ingredientMapper, methodMapper, stepMapper,
                imageAssetService, authorizer, queryService);
    }

    private DinnerRecipeEntity publishedSystemRecipe() {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(101L);
        recipe.setScope("SYSTEM");
        recipe.setStatus("PUBLISHED");
        recipe.setName("番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setServings(2);
        recipe.setEstimatedMinutes(15);
        recipe.setImagePath("/assets/recipes/tomato-eggs.jpg");
        recipe.setImageAssetId(9L);
        recipe.setVersion(1L);
        return recipe;
    }

    private DinnerRecipeMethodEntity method(Long id, String status) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(101L);
        method.setName("家常炒");
        method.setCookingStyle("炒");
        method.setEstimatedMinutes(15);
        method.setIsDefault(true);
        method.setStatus(status);
        method.setSortOrder(0);
        return method;
    }

    private ImageAssetResponse approvedImage() {
        return new ImageAssetResponse(
                9L, "番茄炒鸡蛋", "https://assets.test/list.webp",
                "https://assets.test/detail.webp", "https://source.test",
                "author", "CC0", "https://license.test",
                LocalDate.of(2026, 7, 16), 1198, 1091);
    }

    private RecipeDraftResponse copiedDraftResponse() {
        return new RecipeDraftResponse(
                201L, "DRAFT", 1L, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(), null, List.of(), null, List.of("IMAGE"), null,
                null, null);
    }
}
