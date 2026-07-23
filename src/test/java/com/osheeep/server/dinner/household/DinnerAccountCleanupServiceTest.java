package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
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
class DinnerAccountCleanupServiceTest {

    private static final LocalDateTime DELETED_AT =
            LocalDateTime.parse("2026-07-22T08:00:00");

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper recipeIngredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerHouseholdDataPurger dataPurger;

    private DinnerAccountCleanupService service;

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        List.of(DinnerHouseholdMemberEntity.class, DinnerHouseholdOperationEntity.class,
                        DinnerInviteCodeEntity.class, DinnerRecipeEntity.class,
                        DinnerRecipeIngredientEntity.class, DinnerRecipeMethodEntity.class,
                        DinnerRecipeMethodStepEntity.class)
                .forEach(type -> TableInfoHelper.initTableInfo(assistant, type));
    }

    @BeforeEach
    void setUp() {
        service = new DinnerAccountCleanupService(
                householdMapper, memberMapper, operationMapper, inviteMapper,
                menuMapper, selectionMapper, recipeMapper, recipeIngredientMapper,
                methodMapper, stepMapper, inventoryMapper, ingredientMapper, dataPurger);
        lenient().when(recipeMapper.selectList(any())).thenReturn(List.of());
        lenient().when(memberMapper.selectIdsByUserId(7L)).thenReturn(List.of());
        lenient().when(operationMapper.selectByActorOrTargetMembershipIdsForUpdate(
                eq(7L), any()))
                .thenReturn(List.of());
    }

    @Test
    void lastActiveMemberPurgesHouseholdAndDeletesItsPrivateDrafts() {
        DinnerHouseholdMemberEntity owner = membership(31L, 7L, "OWNER");
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(owner);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household());
        when(memberMapper.selectIdsByUserId(7L)).thenReturn(List.of(31L));
        when(memberMapper.selectIdsByHouseholdId(11L)).thenReturn(List.of(31L));
        when(memberMapper.selectByIdsForUpdate(List.of(31L))).thenReturn(List.of(owner));

        service.removeUser(7L, DELETED_AT);

        verify(dataPurger).purgeHousehold(11L, List.of(owner), Set.of(7L));
        verify(householdMapper, never()).advanceMembershipAndInviteRevision(
                any(), any(), any());
    }

    @Test
    void memberDeletionPreservesSharedHouseholdAndAdvancesMembershipOnce() {
        DinnerHouseholdMemberEntity historical = membership(9L, 7L, "MEMBER");
        historical.setHouseholdId(22L);
        historical.setStatus("LEFT");
        DinnerHouseholdMemberEntity owner = membership(31L, 8L, "OWNER");
        DinnerHouseholdMemberEntity member = membership(32L, 7L, "MEMBER");
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household());
        when(memberMapper.selectIdsByUserId(7L)).thenReturn(List.of(9L, 32L));
        when(memberMapper.selectIdsByHouseholdId(11L)).thenReturn(List.of(31L, 32L));
        when(memberMapper.selectByIdsForUpdate(List.of(9L, 31L, 32L)))
                .thenReturn(List.of(historical, owner, member));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(11L))
                .thenReturn(List.of());
        when(memberMapper.deleteBatchIds(List.of(32L))).thenReturn(1);
        when(memberMapper.deleteBatchIds(List.of(9L))).thenReturn(1);
        when(householdMapper.advanceMembershipAndInviteRevision(11L, 8L, 4L))
                .thenReturn(1);

        service.removeUser(7L, DELETED_AT);

        verify(memberMapper).deleteBatchIds(List.of(32L));
        verify(memberMapper).selectByIdsForUpdate(List.of(9L, 31L, 32L));
        verify(operationMapper).selectByActorOrTargetMembershipIdsForUpdate(
                7L, List.of(9L, 32L));
        verify(memberMapper).deleteBatchIds(List.of(9L));
        verify(memberMapper, never()).promoteActiveMember(any(), any(), any(), any());
        verify(householdMapper).advanceMembershipAndInviteRevision(11L, 8L, 4L);
        verifyNoInteractions(dataPurger);
    }

    @Test
    void ownerDeletionRemovesOwnerBeforePromotingOnlySurvivor() {
        DinnerHouseholdMemberEntity owner = membership(31L, 7L, "OWNER");
        DinnerHouseholdMemberEntity survivor = membership(32L, 8L, "MEMBER");
        survivor.setVersion(3L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(owner);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household());
        when(memberMapper.selectIdsByUserId(7L)).thenReturn(List.of(31L));
        when(memberMapper.selectIdsByHouseholdId(11L)).thenReturn(List.of(31L, 32L));
        when(memberMapper.selectByIdsForUpdate(List.of(31L, 32L)))
                .thenReturn(List.of(owner, survivor));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of());
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(11L))
                .thenReturn(List.of());
        when(memberMapper.deleteBatchIds(List.of(31L))).thenReturn(1);
        when(memberMapper.promoteActiveMember(32L, 11L, 8L, 3L)).thenReturn(1);
        when(householdMapper.advanceMembershipInviteAndOwnership(
                11L, 8L, 4L, DELETED_AT))
                .thenReturn(1);
        DinnerHouseholdMemberEntity promoted = membership(32L, 8L, "OWNER");
        promoted.setVersion(4L);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(promoted));

        service.removeUser(7L, DELETED_AT);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(memberMapper, householdMapper);
        order.verify(memberMapper).deleteBatchIds(List.of(31L));
        order.verify(memberMapper).promoteActiveMember(32L, 11L, 8L, 3L);
        order.verify(householdMapper).advanceMembershipInviteAndOwnership(
                11L, 8L, 4L, DELETED_AT);
        order.verify(memberMapper).selectActiveByHouseholdIdForUpdate(11L);
    }

    @Test
    void historicalOnlyUserClearsPrivateDraftMembershipsAndRelatedOperations() {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(51L);
        draft.setScope("HOUSEHOLD");
        draft.setStatus("DRAFT");
        draft.setCreatorId(7L);
        when(recipeMapper.selectList(any()))
                .thenReturn(List.of(draft), List.of());
        when(recipeMapper.selectByIdsForUpdate(List.of(51L))).thenReturn(List.of(draft));
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(51L))).thenReturn(List.of());
        when(recipeIngredientMapper.selectByRecipeIdsForUpdate(List.of(51L)))
                .thenReturn(List.of());
        when(recipeMapper.deleteBatchIds(List.of(51L))).thenReturn(1);
        DinnerHouseholdMemberEntity left = membership(30L, 7L, "MEMBER");
        left.setStatus("LEFT");
        when(memberMapper.selectIdsByUserId(7L)).thenReturn(List.of(30L));
        when(memberMapper.selectByIdsForUpdate(List.of(30L))).thenReturn(List.of(left));
        when(memberMapper.deleteBatchIds(List.of(30L))).thenReturn(1);

        service.removeUser(7L, DELETED_AT);

        verify(recipeMapper).deleteBatchIds(List.of(51L));
        verify(operationMapper).selectByActorOrTargetMembershipIdsForUpdate(
                7L, List.of(30L));
        verify(memberMapper).deleteBatchIds(List.of(30L));
        verifyNoInteractions(dataPurger);
    }

    @Test
    void privateDraftCleanupLocksCreatorAndLineageRecipesOnceInGlobalIdOrder() {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(51L);
        draft.setScope("HOUSEHOLD");
        draft.setStatus("DRAFT");
        draft.setCreatorId(7L);
        DinnerRecipeEntity lineageReference = new DinnerRecipeEntity();
        lineageReference.setId(9L);
        lineageReference.setScope("HOUSEHOLD");
        lineageReference.setStatus("PUBLISHED");
        lineageReference.setCreatorId(8L);
        lineageReference.setSourceRecipeId(51L);
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(61L);
        method.setRecipeId(51L);
        when(recipeMapper.selectList(any()))
                .thenReturn(List.of(draft), List.of(lineageReference));
        when(recipeMapper.selectByIdsForUpdate(List.of(9L, 51L)))
                .thenReturn(List.of(lineageReference, draft));
        when(methodMapper.selectByRecipeIdsForUpdate(List.of(51L))).thenReturn(List.of(method));
        when(stepMapper.selectByMethodIdsForUpdate(List.of(61L))).thenReturn(List.of());
        when(recipeIngredientMapper.selectByRecipeIdsForUpdate(List.of(51L)))
                .thenReturn(List.of());
        when(recipeMapper.deleteBatchIds(List.of(51L))).thenReturn(1);

        service.removeUser(7L, DELETED_AT);

        org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
                recipeMapper, methodMapper, stepMapper, recipeIngredientMapper);
        lockOrder.verify(recipeMapper, org.mockito.Mockito.times(2)).selectList(any());
        lockOrder.verify(recipeMapper).selectByIdsForUpdate(List.of(9L, 51L));
        lockOrder.verify(methodMapper).selectByRecipeIdsForUpdate(List.of(51L));
        lockOrder.verify(stepMapper).selectByMethodIdsForUpdate(List.of(61L));
        lockOrder.verify(recipeIngredientMapper).selectByRecipeIdsForUpdate(List.of(51L));
        verify(recipeMapper, never()).selectAllDraftsByCreatorForUpdate(any());
        verify(recipeMapper, never()).selectLineageReferencesForUpdate(any());
        verify(recipeMapper).deleteBatchIds(List.of(51L));
    }

    @Test
    void cleanupConstructorMakesPurgerAnExplicitDependency() {
        assertThat(DinnerAccountCleanupService.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(DinnerHouseholdDataPurger.class,
                                DinnerHouseholdOperationMapper.class));
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(11L);
        household.setStatus("ACTIVE");
        household.setVersion(8L);
        household.setInviteRevision(4L);
        return household;
    }

    private DinnerHouseholdMemberEntity membership(Long id, Long userId, String role) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(11L);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setStatus("ACTIVE");
        membership.setSeatNo("OWNER".equals(role) ? 1 : 2);
        membership.setVersion(2L);
        membership.setHistoryVisibleFrom(LocalDateTime.parse("2026-07-01T00:00:00"));
        return membership;
    }
}
