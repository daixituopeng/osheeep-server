package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdIngredientTransactionTest {

    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerHouseholdAccessService accessService;

    private DinnerHouseholdIngredientTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new DinnerHouseholdIngredientTransaction(ingredientMapper, accessService);
    }

    @Test
    void locksHouseholdThenCreatesHouseholdScopedIngredient() {
        stubLockedAccess();
        when(ingredientMapper.selectActiveAccessibleByName(11L, "冻豆腐"))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<DinnerIngredientEntity>getArgument(0).setId(21L);
            return 1;
        }).when(ingredientMapper).insert(any(DinnerIngredientEntity.class));

        assertThat(transaction.create(7L, "冻豆腐", "豆制品", "块"))
                .isEqualTo(new IngredientResponse(
                        21L, "冻豆腐", "豆制品", "块", "HOUSEHOLD"));

        InOrder order = inOrder(accessService, ingredientMapper);
        order.verify(accessService).lockActiveHouseholdContext(7L);
        order.verify(ingredientMapper).selectActiveAccessibleByName(11L, "冻豆腐");
        order.verify(ingredientMapper).insert(any(DinnerIngredientEntity.class));
        verify(ingredientMapper).insert(org.mockito.ArgumentMatchers.argThat(
                (DinnerIngredientEntity item) ->
                        "HOUSEHOLD".equals(item.getScope())
                                && Long.valueOf(11L).equals(item.getHouseholdId())
                                && "ACTIVE".equals(item.getStatus())));
    }

    @Test
    void systemOrCurrentHouseholdDuplicateIsRejected() {
        stubLockedAccess();
        when(ingredientMapper.selectActiveAccessibleByName(11L, "番茄"))
                .thenReturn(List.of(ingredient(1L, "SYSTEM", null, "番茄")));

        assertThatThrownBy(() -> transaction.create(7L, "番茄", "蔬菜", "个"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_ALREADY_EXISTS));

        verify(ingredientMapper, never()).insert(any(DinnerIngredientEntity.class));
    }

    @Test
    void duplicateKeyRaceUsesStableConflict() {
        stubLockedAccess();
        when(ingredientMapper.selectActiveAccessibleByName(11L, "冻豆腐"))
                .thenReturn(List.of());
        when(ingredientMapper.insert(any(DinnerIngredientEntity.class)))
                .thenThrow(new DuplicateKeyException("concurrent create"));

        assertThatThrownBy(() -> transaction.create(7L, "冻豆腐", "豆制品", "块"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_ALREADY_EXISTS));
    }

    @Test
    void staleMembershipStopsBeforeIngredientLookup() {
        when(accessService.lockActiveHouseholdContext(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> transaction.create(7L, "冻豆腐", "豆制品", "块"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        verifyNoInteractions(ingredientMapper);
    }

    private void stubLockedAccess() {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(new ActiveHouseholdAccess(
                7L,
                11L,
                31L,
                1L,
                "MEMBER",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                1L,
                "Asia/Shanghai"));
        when(accessService.lockActiveHouseholdContext(7L)).thenReturn(context);
    }

    private DinnerIngredientEntity ingredient(
            Long id,
            String scope,
            Long householdId,
            String name
    ) {
        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setId(id);
        ingredient.setScope(scope);
        ingredient.setHouseholdId(householdId);
        ingredient.setName(name);
        ingredient.setStatus("ACTIVE");
        return ingredient;
    }
}
