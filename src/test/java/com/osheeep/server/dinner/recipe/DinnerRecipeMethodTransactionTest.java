package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.RecipeMethodSetValidator.ValidatedMethod;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeMethodTransactionTest {

    private static final RecipeAccess ACCESS = new RecipeAccess(8L, 70L);

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;

    private DinnerRecipeMethodTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new DinnerRecipeMethodTransaction(
                recipeMapper, methodMapper, stepMapper, authorizer, queryService);
        when(authorizer.requireMembershipForUpdate(8L)).thenReturn(ACCESS);
    }

    @Test
    void householdPartnerCanAddReorderAndSwitchDefaultOnPublishedRecipe() {
        DinnerRecipeEntity recipe = recipe("PUBLISHED", 7L, 4L);
        DinnerRecipeMethodEntity oldDefault = method(201L, true, 0);
        DinnerRecipeMethodEntity other = method(202L, false, 1);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(recipe);
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(oldDefault, other));
        when(methodMapper.insert(any(DinnerRecipeMethodEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerRecipeMethodEntity>getArgument(0).setId(203L);
            return 1;
        });
        when(queryService.detail(ACCESS, 101L)).thenReturn(response());

        RecipeDraftResponse saved = transaction.replace(
                8L, 101L, 4L,
                List.of(
                        validated(202L, "少油焖", true, 0, 22),
                        validated(201L, "家常炒", false, 1, 15),
                        validated(null, "空气炸", false, 2, 18)));

        assertThat(saved.version()).isEqualTo(5L);
        assertThat(recipe.getVersion()).isEqualTo(5L);
        assertThat(recipe.getLastModifiedBy()).isEqualTo(8L);
        assertThat(recipe.getEstimatedMinutes()).isEqualTo(22);
        assertThat(oldDefault.getIsDefault()).isFalse();
        assertThat(other.getIsDefault()).isTrue();
        verify(methodMapper).insert(any(DinnerRecipeMethodEntity.class));
        verify(stepMapper, org.mockito.Mockito.times(3)).delete(any());
        verify(stepMapper, org.mockito.Mockito.times(3))
                .insert(any(com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity.class));
    }

    @Test
    void omittingAnExistingMethodIsRejectedWithoutRecipeAdvance() {
        DinnerRecipeEntity recipe = recipe("DRAFT", 8L, 4L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(recipe);
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(method(201L, true, 0), method(202L, false, 1)));

        assertThatThrownBy(() -> transaction.replace(
                8L, 101L, 4L,
                List.of(validated(201L, "家常炒", true, 0, 15))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_INVALID));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
        verifyNoInteractions(stepMapper, queryService);
    }

    @Test
    void archivedRecipeCannotBeEdited() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(recipe("ARCHIVED", 8L, 4L));

        assertThatThrownBy(() -> transaction.replace(
                8L, 101L, 4L,
                List.of(validated(201L, "家常炒", true, 0, 15))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(methodMapper, stepMapper, queryService);
    }

    private ValidatedMethod validated(
            Long id,
            String name,
            boolean defaultMethod,
            int sortOrder,
            int minutes
    ) {
        return new ValidatedMethod(
                id, name, "炒", minutes, defaultMethod, sortOrder, List.of("炒熟"));
    }

    private DinnerRecipeEntity recipe(
            String status,
            Long creatorId,
            Long version
    ) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(101L);
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(creatorId);
        recipe.setStatus(status);
        recipe.setVersion(version);
        return recipe;
    }

    private DinnerRecipeMethodEntity method(Long id, boolean defaultMethod, int order) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(101L);
        method.setName("做法" + id);
        method.setCookingStyle("炒");
        method.setEstimatedMinutes(15);
        method.setIsDefault(defaultMethod);
        method.setStatus("ACTIVE");
        method.setSortOrder(order);
        return method;
    }

    private RecipeDraftResponse response() {
        return new RecipeDraftResponse(
                101L, "PUBLISHED", 5L, "番茄炒蛋", "家常菜", "酸甜",
                2, 22, List.of(), null, null, List.of(), null);
    }
}
