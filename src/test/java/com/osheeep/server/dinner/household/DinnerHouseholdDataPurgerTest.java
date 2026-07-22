package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.image.mapper.DinnerImageAssetMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdDataPurgerTest {

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordDishSnapshotMapper snapshotMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerRecipeIngredientMapper recipeIngredientMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerIngredientMapper ingredientMapper;

    private DinnerHouseholdDataPurger purger;

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        List.of(DinnerHouseholdEntity.class, DinnerHouseholdMemberEntity.class,
                        DinnerHouseholdOperationEntity.class, DinnerInviteCodeEntity.class,
                        DinnerMenuEntity.class, DinnerCookingRecordEntity.class,
                        DinnerRecipeEntity.class, DinnerHouseholdInventoryEntity.class,
                        DinnerIngredientEntity.class)
                .forEach(type -> TableInfoHelper.initTableInfo(assistant, type));
    }

    @BeforeEach
    void setUp() {
        purger = new DinnerHouseholdDataPurger(
                householdMapper, memberMapper, operationMapper, inviteMapper, menuMapper,
                selectionMapper, actionMapper, recordMapper, snapshotMapper, recipeMapper,
                methodMapper, stepMapper, recipeIngredientMapper, inventoryMapper,
                ingredientMapper);
    }

    @Test
    void dissolutionDetachesPersonalDraftAndNeverOwnsImageAssetDeletion() {
        DinnerHouseholdMemberEntity owner = new DinnerHouseholdMemberEntity();
        owner.setId(31L);
        owner.setHouseholdId(11L);
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(51L);
        draft.setHouseholdId(11L);
        draft.setCreatorId(7L);
        draft.setScope("HOUSEHOLD");
        draft.setStatus("DRAFT");
        draft.setVersion(3L);

        when(operationMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(inviteMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(menuMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(recordMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(recipeMapper.selectByHouseholdIdForUpdate(11L)).thenReturn(List.of(draft));
        when(recipeMapper.selectLineageReferencesForUpdate(List.of(51L)))
                .thenReturn(List.of());
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(51L))).thenReturn(List.of());
        when(recipeIngredientMapper.selectByRecipeIdsForUpdate(List.of(51L)))
                .thenReturn(List.of());
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(11L))
                .thenReturn(List.of());
        when(recipeMapper.detachOwnedDraft(51L, 11L, 7L, 3L, null)).thenReturn(1);
        when(householdMapper.deleteById(11L)).thenReturn(1);

        purger.purgeHousehold(11L, List.of(owner), Set.of());

        verify(recipeMapper).detachOwnedDraft(51L, 11L, 7L, 3L, null);
        verify(recipeMapper, never()).deleteBatchIds(any());
        assertThat(DinnerHouseholdDataPurger.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .doesNotContain(DinnerImageAssetMapper.class));
    }
}
