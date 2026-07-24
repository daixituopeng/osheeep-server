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
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeRevisionTransactionTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;
    @Mock private DinnerImageAssetService imageAssetService;

    @BeforeEach
    void initializeTableMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeIngredientEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeMethodEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeMethodStepEntity.class);
    }

    @Test
    void appliesOnlyRevisionContentAndDeletesThePrivateDraft() {
        DinnerRecipeRevisionTransaction transaction = transaction();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity published = published(8L);
        DinnerRecipeEntity draft = revision(4L, 8L);
        DinnerRecipeIngredientEntity oldIngredient = ingredient(101L, 30L, "克");
        DinnerRecipeIngredientEntity revisedIngredient = ingredient(201L, 31L, "个");
        DinnerRecipeMethodEntity publishedDefault = method(301L, 101L);
        DinnerRecipeMethodEntity draftDefault = method(401L, 201L);
        DinnerRecipeMethodStepEntity draftStep = new DinnerRecipeMethodStepEntity();
        draftStep.setId(501L);
        draftStep.setMethodId(401L);
        draftStep.setInstruction("炒熟");
        draftStep.setSortOrder(0);

        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectById(201L)).thenReturn(draft);
        when(recipeMapper.selectByIdsForUpdate(List.of(101L, 201L)))
                .thenReturn(List.of(published, draft));
        when(queryService.detail(access, 201L)).thenReturn(revisionResponse());
        when(queryService.detail(access, 101L)).thenReturn(publishedResponse());
        when(ingredientMapper.selectByRecipeIdsForUpdate(List.of(101L, 201L)))
                .thenReturn(List.of(oldIngredient, revisedIngredient));
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L, 201L)))
                .thenReturn(List.of(publishedDefault, draftDefault));
        when(stepMapper.selectByMethodIdsForUpdate(List.of(401L)))
                .thenReturn(List.of(draftStep));

        RecipeDraftResponse result = transaction.applyChecked(7L, 201L, 4L);

        assertThat(result.id()).isEqualTo(101L);
        assertThat(published.getName()).isEqualTo("新版番茄炒蛋");
        assertThat(published.getImageAssetId()).isEqualTo(10L);
        assertThat(published.getVersion()).isEqualTo(9L);
        assertThat(publishedDefault.getEstimatedMinutes()).isEqualTo(20);
        verify(ingredientMapper).insert(any(DinnerRecipeIngredientEntity.class));
        verify(recipeMapper).deleteById(201L);
        verify(queryService).detail(access, 101L);
    }

    @Test
    void changedPublishedBasePreservesRevisionAndReturnsConflict() {
        DinnerRecipeRevisionTransaction transaction = transaction();
        RecipeAccess access = new RecipeAccess(7L, 70L);
        DinnerRecipeEntity draft = revision(4L, 8L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectById(201L)).thenReturn(draft);
        when(recipeMapper.selectByIdsForUpdate(List.of(101L, 201L)))
                .thenReturn(List.of(published(9L), draft));

        assertThatThrownBy(() -> transaction.applyChecked(7L, 201L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
        verify(recipeMapper, never()).deleteById(any(Long.class));
        verify(imageAssetService, never()).requireApproved(any(Long.class));
    }

    private DinnerRecipeRevisionTransaction transaction() {
        return new DinnerRecipeRevisionTransaction(
                recipeMapper, ingredientMapper, methodMapper, stepMapper,
                authorizer, queryService, imageAssetService, new RecipeDraftValidator());
    }

    private DinnerRecipeEntity published(long version) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(101L);
        recipe.setScope("HOUSEHOLD");
        recipe.setStatus("PUBLISHED");
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(8L);
        recipe.setLastModifiedBy(8L);
        recipe.setVersion(version);
        recipe.setName("番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setServings(2);
        recipe.setEstimatedMinutes(15);
        recipe.setImageAssetId(9L);
        return recipe;
    }

    private DinnerRecipeEntity revision(long version, long baseVersion) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(201L);
        recipe.setScope("HOUSEHOLD");
        recipe.setStatus("DRAFT");
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(7L);
        recipe.setLastModifiedBy(7L);
        recipe.setVersion(version);
        recipe.setRevisionOfRecipeId(101L);
        recipe.setBasePublishedVersion(baseVersion);
        recipe.setName("新版番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setServings(3);
        recipe.setEstimatedMinutes(20);
        recipe.setImageAssetId(10L);
        return recipe;
    }

    private DinnerRecipeIngredientEntity ingredient(
            Long recipeId,
            Long ingredientId,
            String unit
    ) {
        DinnerRecipeIngredientEntity row = new DinnerRecipeIngredientEntity();
        row.setRecipeId(recipeId);
        row.setIngredientId(ingredientId);
        row.setQuantity(BigDecimal.ONE);
        row.setUnit(unit);
        row.setIsRequired(true);
        row.setSortOrder(0);
        return row;
    }

    private DinnerRecipeMethodEntity method(Long id, Long recipeId) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(recipeId);
        method.setName("家常做法");
        method.setCookingStyle("炒");
        method.setEstimatedMinutes(15);
        method.setIsDefault(true);
        method.setStatus("ACTIVE");
        method.setSortOrder(0);
        return method;
    }

    private RecipeDraftResponse revisionResponse() {
        return new RecipeDraftResponse(
                201L, "DRAFT", 4L, "新版番茄炒蛋", "家常菜", "酸甜", 3, 20,
                List.of(new RecipeIngredientResponse(
                        31L, "番茄", BigDecimal.ONE, "个", true, 0)),
                new RecipeMethodResponse(
                        401L, "家常做法", "炒",
                        List.of(new RecipeMethodStepResponse("炒熟", 0))),
                null, List.of(), null);
    }

    private RecipeDraftResponse publishedResponse() {
        return new RecipeDraftResponse(
                101L, "PUBLISHED", 9L, "新版番茄炒蛋", "家常菜", "酸甜", 3, 20,
                List.of(), null, null, List.of(), null);
    }
}
