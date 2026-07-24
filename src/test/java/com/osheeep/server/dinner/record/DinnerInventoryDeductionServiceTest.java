package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.DinnerHouseholdActorLabelService;
import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.record.dto.HandleInventoryDeductionRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionItemRequest;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

@ExtendWith(MockitoExtension.class)
class DinnerInventoryDeductionServiceTest {

    @Mock private DinnerHouseholdAccessService householdAccessService;
    @Mock private DinnerHouseholdActorLabelService actorLabelService;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordDishSnapshotMapper snapshotMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerNotificationPublisher notificationPublisher;

    private DinnerInventoryDeductionJsonCodec deductionJsonCodec;
    private DinnerInventoryDeductionService service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "record"),
                DinnerCookingRecordEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "snapshot"),
                DinnerRecordDishSnapshotEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "inventory"),
                DinnerHouseholdInventoryEntity.class);
        ObjectMapper objectMapper = new ObjectMapper();
        deductionJsonCodec = new DinnerInventoryDeductionJsonCodec(objectMapper);
        service = new DinnerInventoryDeductionService(
                householdAccessService,
                actorLabelService,
                recordMapper,
                snapshotMapper,
                inventoryMapper,
                new DinnerRecordSnapshotJsonCodec(objectMapper),
                deductionJsonCodec,
                Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC));
        service.setNotificationPublisher(notificationPublisher);
    }

    @Test
    void pendingProposalAggregatesSnapshotsAndExplainsEveryRecoveryState() {
        DinnerCookingRecordEntity record = record("PENDING");
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        when(recordMapper.selectById(91L)).thenReturn(record);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot("""
                        [
                          {"ingredientId":1,"name":"番茄","quantity":2,"unit":"个","required":true,"sortOrder":0},
                          {"ingredientId":2,"name":"鸡蛋","quantity":3,"unit":"枚","required":true,"sortOrder":1},
                          {"ingredientId":3,"name":"盐","quantity":null,"unit":"克","required":false,"sortOrder":2},
                          {"ingredientId":4,"name":"食用油","quantity":10,"unit":"毫升","required":false,"sortOrder":3},
                          {"ingredientId":5,"name":"葱","quantity":1,"unit":"根","required":false,"sortOrder":4}
                        ]
                        """, 0),
                snapshot("""
                        [
                          {"ingredientId":1,"name":"番茄","quantity":1,"unit":"个","required":true,"sortOrder":0}
                        ]
                        """, 1)));
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                inventory(1L, "2.500", "个", 3L),
                inventory(2L, null, "枚", 1L),
                inventory(3L, "5.000", "勺", 2L),
                inventory(4L, "30.000", "毫升", 4L)));

        var result = service.get(7L, 91L);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.proposalItems()).satisfiesExactly(
                tomato -> {
                    assertThat(tomato.ingredientId()).isEqualTo(1L);
                    assertThat(tomato.recipeQuantity())
                            .isEqualByComparingTo("3.000");
                    assertThat(tomato.recipeUnit()).isEqualTo("个");
                    assertThat(tomato.inventoryUnit()).isEqualTo("个");
                    assertThat(tomato.suggestedQuantity())
                            .isEqualByComparingTo("2.500");
                    assertThat(tomato.selectedByDefault()).isTrue();
                    assertThat(tomato.eligibility()).isEqualTo("INSUFFICIENT");
                },
                egg -> assertThat(egg.eligibility())
                        .isEqualTo("INVENTORY_QUANTITY_UNKNOWN"),
                salt -> {
                    assertThat(salt.recipeUnit()).isEqualTo("克");
                    assertThat(salt.inventoryUnit()).isEqualTo("勺");
                    assertThat(salt.eligibility()).isEqualTo("UNIT_MISMATCH");
                },
                oil -> {
                    assertThat(oil.eligibility()).isEqualTo("READY");
                    assertThat(oil.suggestedQuantity()).isEqualByComparingTo("10");
                    assertThat(oil.selectedByDefault()).isFalse();
                },
                scallion -> {
                    assertThat(scallion.recipeUnit()).isEqualTo("根");
                    assertThat(scallion.inventoryUnit()).isNull();
                    assertThat(scallion.eligibility())
                            .isEqualTo("NOT_IN_INVENTORY");
                });
        assertThat(result.appliedItems()).isEmpty();
    }

    @Test
    void applyLocksInOrderUpdatesOnceAndReplaysThePersistedTerminalResult() {
        DinnerCookingRecordEntity record = record("PENDING");
        stubLockedRecord(record);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot("""
                [
                  {"ingredientId":1,"name":"番茄","quantity":2,"unit":"个","required":true,"sortOrder":0},
                  {"ingredientId":4,"name":"食用油","quantity":10,"unit":"毫升","required":false,"sortOrder":1}
                ]
                """, 0)));
        DinnerHouseholdInventoryEntity tomato = inventory(1L, "3.000", "个", 3L);
        DinnerHouseholdInventoryEntity oil = inventory(4L, "30.000", "毫升", 4L);
        when(inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                11L, List.of(1L, 4L))).thenReturn(List.of(tomato, oil));
        when(inventoryMapper.updateById(
                any(DinnerHouseholdInventoryEntity.class))).thenReturn(1);
        when(recordMapper.updateById(record)).thenReturn(1);
        when(actorLabelService.resolve(11L, 7L, List.of(7L)))
                .thenReturn(Map.of(7L, new HouseholdActorResponse("ME")));
        HandleInventoryDeductionRequest request = applyRequest(List.of(
                new InventoryDeductionItemRequest(
                        4L, new BigDecimal("5.000"), 4L),
                new InventoryDeductionItemRequest(
                        1L, new BigDecimal("2.000"), 3L)));

        var first = service.handle(7L, 91L, request);
        var replay = service.handle(7L, 91L, request);

        assertThat(first.status()).isEqualTo("APPLIED");
        assertThat(first.handledBy()).isEqualTo(new HouseholdActorResponse("ME"));
        assertThat(first.handledAt())
                .isEqualTo(Instant.parse("2026-07-24T12:00:00Z"));
        assertThat(first.appliedItems()).satisfiesExactly(
                item -> {
                    assertThat(item.ingredientId()).isEqualTo(1L);
                    assertThat(item.quantityAfter()).isEqualByComparingTo("1.000");
                    assertThat(item.resultingVersion()).isEqualTo(4L);
                },
                item -> {
                    assertThat(item.ingredientId()).isEqualTo(4L);
                    assertThat(item.quantityAfter()).isEqualByComparingTo("25.000");
                    assertThat(item.resultingVersion()).isEqualTo(5L);
                });
        assertThat(replay).isEqualTo(first);
        assertThat(record.getInventoryDeductionStatus()).isEqualTo("APPLIED");
        assertThat(record.getInventoryDeductionItems()).contains("番茄", "食用油");
        verify(inventoryMapper, times(2)).updateById(
                any(DinnerHouseholdInventoryEntity.class));
        verify(recordMapper, times(1)).updateById(record);
        verify(notificationPublisher, times(2))
                .toPartner(any(), any(), any(), any(), any(), any(), any());

        InOrder order = inOrder(
                householdAccessService, recordMapper, snapshotMapper, inventoryMapper);
        order.verify(householdAccessService).lockActiveHouseholdContext(7L);
        order.verify(recordMapper).selectByHouseholdAndIdForUpdate(11L, 91L);
        order.verify(snapshotMapper).selectList(any());
        order.verify(inventoryMapper)
                .selectByHouseholdAndIngredientIdsForUpdate(11L, List.of(1L, 4L));
    }

    @Test
    void skipIsTerminalAndNeverTouchesInventoryOrSnapshots() {
        DinnerCookingRecordEntity record = record("PENDING");
        stubLockedRecord(record);
        when(recordMapper.updateById(record)).thenReturn(1);
        when(actorLabelService.resolve(11L, 7L, List.of(7L)))
                .thenReturn(Map.of(7L, new HouseholdActorResponse("ME")));
        HandleInventoryDeductionRequest request = new HandleInventoryDeductionRequest(
                "SKIP",
                "00000000-0000-4000-8000-000000000020",
                List.of());

        var result = service.handle(7L, 91L, request);
        var replay = service.handle(7L, 91L, request);

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.appliedItems()).isEmpty();
        assertThat(replay).isEqualTo(result);
        verifyNoInteractions(snapshotMapper, inventoryMapper, notificationPublisher);
        verify(recordMapper, times(1)).updateById(record);
    }

    @Test
    void staleInventoryVersionRollsBackBeforeAnyWrite() {
        DinnerCookingRecordEntity record = record("PENDING");
        stubLockedRecord(record);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot("""
                [
                  {"ingredientId":1,"name":"番茄","quantity":2,"unit":"个","required":true,"sortOrder":0}
                ]
                """, 0)));
        when(inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                11L, List.of(1L))).thenReturn(List.of(
                        inventory(1L, "3.000", "个", 4L)));

        assertThatThrownBy(() -> service.handle(
                7L,
                91L,
                applyRequest(List.of(new InventoryDeductionItemRequest(
                        1L, new BigDecimal("2.000"), 3L)))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_CONFLICT));

        verify(inventoryMapper, never()).updateById(
                any(DinnerHouseholdInventoryEntity.class));
        verify(recordMapper, never()).updateById(
                any(DinnerCookingRecordEntity.class));
        verifyNoInteractions(notificationPublisher);
    }

    @Test
    void rejectsEmptyApplyDuplicateItemsAndIngredientsOutsideTheSnapshot() {
        DinnerCookingRecordEntity record = record("PENDING");
        stubLockedRecord(record);

        assertThatThrownBy(() -> service.handle(
                7L, 91L, applyRequest(List.of())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_INVALID));

        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot("""
                [
                  {"ingredientId":1,"name":"番茄","quantity":2,"unit":"个","required":true,"sortOrder":0}
                ]
                """, 0)));
        assertThatThrownBy(() -> service.handle(
                7L,
                91L,
                applyRequest(List.of(
                        new InventoryDeductionItemRequest(1L, BigDecimal.ONE, 1L),
                        new InventoryDeductionItemRequest(1L, BigDecimal.ONE, 1L)))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_INVALID));
        assertThatThrownBy(() -> service.handle(
                7L,
                91L,
                applyRequest(List.of(
                        new InventoryDeductionItemRequest(99L, BigDecimal.ONE, 1L)))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_INVALID));

        verify(inventoryMapper, never())
                .selectByHouseholdAndIngredientIdsForUpdate(any(), any());
        verify(recordMapper, never()).updateById(
                any(DinnerCookingRecordEntity.class));
    }

    @Test
    void inaccessibleOrLegacyRecordsCannotBeHandled() {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        DinnerCookingRecordEntity foreign = record("PENDING");
        foreign.setHouseholdId(12L);
        when(recordMapper.selectById(91L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.get(7L, 91L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        DinnerCookingRecordEntity legacy = record("NOT_APPLICABLE");
        stubLockedRecord(legacy);
        assertThatThrownBy(() -> service.handle(
                7L,
                91L,
                applyRequest(List.of(new InventoryDeductionItemRequest(
                        1L, BigDecimal.ONE, 1L)))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_INVALID));
    }

    @Test
    void recordLockFailureMapsToDeductionConflict() {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access());
        when(householdAccessService.lockActiveHouseholdContext(7L)).thenReturn(context);
        when(recordMapper.selectByHouseholdAndIdForUpdate(11L, 91L))
                .thenThrow(new CannotAcquireLockException("timeout"));

        assertThatThrownBy(() -> service.handle(
                7L,
                91L,
                new HandleInventoryDeductionRequest(
                        "SKIP",
                        "00000000-0000-4000-8000-000000000020",
                        List.of())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_DEDUCTION_CONFLICT));

        verifyNoInteractions(snapshotMapper, inventoryMapper, notificationPublisher);
    }

    private HandleInventoryDeductionRequest applyRequest(
            List<InventoryDeductionItemRequest> items
    ) {
        return new HandleInventoryDeductionRequest(
                "APPLY",
                "00000000-0000-4000-8000-000000000019",
                items);
    }

    private void stubLockedRecord(DinnerCookingRecordEntity record) {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access());
        when(householdAccessService.lockActiveHouseholdContext(7L)).thenReturn(context);
        when(recordMapper.selectByHouseholdAndIdForUpdate(11L, 91L))
                .thenReturn(record);
    }

    private ActiveHouseholdAccess access() {
        return new ActiveHouseholdAccess(
                7L,
                11L,
                41L,
                4L,
                "OWNER",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                8L,
                "Asia/Shanghai");
    }

    private DinnerCookingRecordEntity record(String status) {
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setId(91L);
        record.setHouseholdId(11L);
        record.setMenuId(31L);
        record.setRecordDate(LocalDate.of(2026, 7, 24));
        record.setCompletedBy(7L);
        record.setCompletedAt(LocalDateTime.of(2026, 7, 24, 11, 30));
        record.setInventoryDeductionStatus(status);
        return record;
    }

    private DinnerRecordDishSnapshotEntity snapshot(String ingredientsJson, int sortOrder) {
        DinnerRecordDishSnapshotEntity snapshot = new DinnerRecordDishSnapshotEntity();
        snapshot.setRecordId(91L);
        snapshot.setRecipeId((long) sortOrder + 1L);
        snapshot.setIngredientsJson(ingredientsJson);
        snapshot.setSortOrder(sortOrder);
        return snapshot;
    }

    private DinnerHouseholdInventoryEntity inventory(
            Long ingredientId,
            String quantity,
            String unit,
            Long version
    ) {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setId(ingredientId + 100L);
        item.setHouseholdId(11L);
        item.setIngredientId(ingredientId);
        item.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        item.setUnit(unit);
        item.setVersion(version);
        item.setUpdatedBy(7L);
        return item;
    }
}
