package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.HouseholdRecipePreference;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceValue;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipePreferenceRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipePreferenceMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipePreferenceServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipePreferenceMapper preferenceMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;

    private DinnerRecipePreferenceService service;

    @BeforeEach
    void setUp() {
        service = new DinnerRecipePreferenceService(
                recipeMapper,
                preferenceMapper,
                authorizer,
                new DinnerRecipePreferenceAggregator());
    }

    @Test
    void createsFirstPreferenceForTheCurrentMembershipCycle() {
        RecipeAccess access = new RecipeAccess(7L, 70L, 31L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(10L)).thenReturn(systemRecipe());
        when(preferenceMapper.selectByMembershipAndRecipeForUpdate(31L, 10L))
                .thenReturn(null);
        when(preferenceMapper.insert(any(DinnerRecipePreferenceEntity.class))).thenReturn(1);
        when(preferenceMapper.selectActiveByHouseholdAndRecipeIds(70L, List.of(10L)))
                .thenAnswer(invocation -> {
                    DinnerRecipePreferenceEntity stored = preference(
                            1L, 31L, 7L, "LIKE", 1L);
                    return List.of(stored);
                });

        var result = service.update(
                7L, 10L, new UpdateRecipePreferenceRequest(
                        RecipePreferenceValue.LIKE, 0L));

        assertThat(result.myPreference()).isEqualTo(RecipePreferenceValue.LIKE);
        assertThat(result.myVersion()).isEqualTo(1L);
        assertThat(result.householdPreference())
                .isEqualTo(HouseholdRecipePreference.SOME_LIKE);
        ArgumentCaptor<DinnerRecipePreferenceEntity> stored =
                ArgumentCaptor.forClass(DinnerRecipePreferenceEntity.class);
        verify(preferenceMapper).insert(stored.capture());
        assertThat(stored.getValue().getMembershipId()).isEqualTo(31L);
        assertThat(stored.getValue().getHouseholdId()).isEqualTo(70L);
        assertThat(stored.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    void updatesOnlyTheExactVersionAndReturnsTheRelationshipAggregate() {
        RecipeAccess access = new RecipeAccess(7L, 70L, 31L);
        DinnerRecipePreferenceEntity current =
                preference(1L, 31L, 7L, "NEUTRAL", 2L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(10L)).thenReturn(systemRecipe());
        when(preferenceMapper.selectByMembershipAndRecipeForUpdate(31L, 10L))
                .thenReturn(current);
        when(preferenceMapper.updatePreference(
                1L, 70L, 31L, 7L, 10L, 2L, "DISLIKE")).thenReturn(1);
        when(preferenceMapper.selectActiveByHouseholdAndRecipeIds(70L, List.of(10L)))
                .thenReturn(List.of(
                        preference(1L, 31L, 7L, "DISLIKE", 3L),
                        preference(2L, 32L, 8L, "DISLIKE", 1L)));

        var result = service.update(
                7L, 10L, new UpdateRecipePreferenceRequest(
                        RecipePreferenceValue.DISLIKE, 2L));

        assertThat(result.myVersion()).isEqualTo(3L);
        assertThat(result.householdPreference())
                .isEqualTo(HouseholdRecipePreference.BOTH_DISLIKE);
    }

    @Test
    void staleVersionFailsWithoutWritingOrPublishingAReplacementChoice() {
        RecipeAccess access = new RecipeAccess(7L, 70L, 31L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(10L)).thenReturn(systemRecipe());
        when(preferenceMapper.selectByMembershipAndRecipeForUpdate(31L, 10L))
                .thenReturn(preference(1L, 31L, 7L, "LIKE", 4L));

        assertThatThrownBy(() -> service.update(
                7L, 10L, new UpdateRecipePreferenceRequest(
                        RecipePreferenceValue.DISLIKE, 3L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                ErrorCode.DINNER_RECIPE_PREFERENCE_VERSION_CONFLICT));

        verify(preferenceMapper, never()).updatePreference(
                any(), any(), any(), any(), any(), any(), any());
        verify(preferenceMapper, never()).selectActiveByHouseholdAndRecipeIds(
                any(), any());
    }

    @Test
    void refusesDraftArchivedAndOtherHouseholdRecipes() {
        RecipeAccess access = new RecipeAccess(7L, 70L, 31L);
        DinnerRecipeEntity otherHouseholdRecipe = systemRecipe();
        otherHouseholdRecipe.setScope("HOUSEHOLD");
        otherHouseholdRecipe.setHouseholdId(71L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(10L)).thenReturn(otherHouseholdRecipe);

        assertThatThrownBy(() -> service.update(
                7L, 10L, new UpdateRecipePreferenceRequest(
                        RecipePreferenceValue.LIKE, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_NOT_FOUND));

        verify(preferenceMapper, never()).insert(any(DinnerRecipePreferenceEntity.class));
    }

    private DinnerRecipeEntity systemRecipe() {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(10L);
        recipe.setScope("SYSTEM");
        recipe.setStatus("PUBLISHED");
        return recipe;
    }

    private DinnerRecipePreferenceEntity preference(
            Long id,
            Long membershipId,
            Long userId,
            String value,
            Long version
    ) {
        DinnerRecipePreferenceEntity preference = new DinnerRecipePreferenceEntity();
        preference.setId(id);
        preference.setHouseholdId(70L);
        preference.setMembershipId(membershipId);
        preference.setUserId(userId);
        preference.setRecipeId(10L);
        preference.setPreference(value);
        preference.setVersion(version);
        return preference;
    }
}
