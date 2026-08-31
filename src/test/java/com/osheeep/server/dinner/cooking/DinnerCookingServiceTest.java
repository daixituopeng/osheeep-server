package com.osheeep.server.dinner.cooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.cooking.dto.AddCookingDishRequest;
import com.osheeep.server.dinner.cooking.dto.StartCookingRequest;
import com.osheeep.server.dinner.cooking.dto.UpdateCookingDishCompletionRequest;
import com.osheeep.server.dinner.cooking.entity.DinnerMenuCookingDishEntity;
import com.osheeep.server.dinner.cooking.mapper.DinnerMenuCookingDishMapper;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.DinnerHouseholdActorLabelService;
import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.menu.BusinessDateResolver;
import com.osheeep.server.dinner.menu.DinnerMenuMethodResolutionService;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.record.DinnerRecordSnapshotAssembler;
import com.osheeep.server.dinner.record.DinnerRecordSnapshotJsonCodec;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerCookingServiceTest {

    private static final String START_KEY =
            "00000000-0000-4000-8000-000000000101";
    private static final String ADD_KEY =
            "00000000-0000-4000-8000-000000000102";

    @Mock private DinnerHouseholdAccessService householdAccessService;
    @Mock private DinnerHouseholdActorLabelService actorLabelService;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerMenuCookingDishMapper cookingDishMapper;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordSnapshotAssembler snapshotAssembler;

    private DinnerCookingSnapshotCodec snapshotCodec;
    private DinnerCookingService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, DinnerMenuSelectionEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerMenuCookingDishEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerCookingRecordEntity.class);
        ObjectMapper objectMapper = new ObjectMapper();
        snapshotCodec = new DinnerCookingSnapshotCodec(
                new DinnerRecordSnapshotJsonCodec(objectMapper), objectMapper);
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-13T11:00:00Z"), ZoneOffset.UTC);
        lenient().when(actorLabelService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> Map.of(
                        7L, new HouseholdActorResponse("ME"),
                        8L, new HouseholdActorResponse("PARTNER")));
        lenient().when(actorLabelService.ordered(any(), any()))
                .thenAnswer(invocation -> invocation.<Set<Long>>getArgument(0).stream()
                        .sorted()
                        .map(userId -> invocation.<Map<Long, HouseholdActorResponse>>
                                getArgument(1).get(userId))
                        .toList());
        service = new DinnerCookingService(
                householdAccessService, actorLabelService,
                new DinnerMenuMethodResolutionService(selectionMapper),
                menuMapper, selectionMapper,
                actionMapper, cookingDishMapper, recordMapper, snapshotAssembler,
                snapshotCodec, new BusinessDateResolver(), clock);
    }

    @Test
    void startFreezesPlannedDishesAndMovesTheMenuToCooking() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubLockedToday(menu);
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setUserId(7L);
        selection.setRecipeId(14L);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of(selection));
        when(snapshotAssembler.assemble(11L, List.of(selection)))
                .thenReturn(List.of(householdDraft(14L, Set.of(7L, 8L))));
        when(cookingDishMapper.selectByMenuIdForUpdate(31L)).thenReturn(List.of());
        when(cookingDishMapper.insert(any(DinnerMenuCookingDishEntity.class)))
                .thenAnswer(invocation -> {
            DinnerMenuCookingDishEntity row = invocation.getArgument(0);
            row.setId(101L);
            return 1;
        });
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(actionMapper.insert(any(DinnerMenuActionEntity.class))).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenAnswer(invocation -> {
            ArgumentCaptor<DinnerMenuCookingDishEntity> inserted =
                    ArgumentCaptor.forClass(DinnerMenuCookingDishEntity.class);
            verify(cookingDishMapper).insert(inserted.capture());
            return List.of(inserted.getValue());
        });

        var result = service.start(7L, new StartCookingRequest(5L, START_KEY));

        assertThat(result.status()).isEqualTo("COOKING");
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.recordId()).isNull();
        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.origin()).isEqualTo("PLANNED");
            assertThat(dish.method().steps())
                    .extracting(RecordMethodStepSnapshotResponse::instruction)
                    .containsExactly("翻炒至熟");
            assertThat(dish.ingredients())
                    .extracting(RecordIngredientSnapshotResponse::name)
                    .containsExactly("鸡蛋");
            assertThat(dish.selectedBy())
                    .extracting(HouseholdActorResponse::kind)
                    .containsExactly("ME", "PARTNER");
        });
        ArgumentCaptor<DinnerMenuActionEntity> action =
                ArgumentCaptor.forClass(DinnerMenuActionEntity.class);
        verify(actionMapper).insert(action.capture());
        assertThat(action.getValue().getActionType()).isEqualTo("START_COOKING");
        assertThat(action.getValue().getIdempotencyKey()).isEqualTo(START_KEY);
    }

    @Test
    void startAutoConfirmsDraftAndResolvesMethodConflictInOneVersionStep() {
        DinnerMenuEntity menu = menu("DRAFT", 4L);
        stubLockedToday(menu);
        DinnerMenuSelectionEntity mine = selection(7L, 14L, 8L, 22L);
        DinnerMenuSelectionEntity partner = selection(8L, 14L, 8L, 21L);
        List<DinnerMenuSelectionEntity> selections = List.of(mine, partner);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections)).thenAnswer(invocation -> {
            assertThat(selections)
                    .extracting(DinnerMenuSelectionEntity::getMethodId)
                    .containsOnly(22L);
            return List.of(householdDraft(14L, Set.of(7L, 8L)));
        });
        when(cookingDishMapper.selectByMenuIdForUpdate(31L)).thenReturn(List.of());
        when(cookingDishMapper.insert(any(DinnerMenuCookingDishEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.<DinnerMenuCookingDishEntity>getArgument(0).setId(101L);
                    return 1;
                });
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(actionMapper.insert(any(DinnerMenuActionEntity.class))).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenAnswer(invocation -> {
            ArgumentCaptor<DinnerMenuCookingDishEntity> inserted =
                    ArgumentCaptor.forClass(DinnerMenuCookingDishEntity.class);
            verify(cookingDishMapper).insert(inserted.capture());
            return List.of(inserted.getValue());
        });

        var result = service.start(7L, new StartCookingRequest(4L, START_KEY));

        assertThat(result.status()).isEqualTo("COOKING");
        assertThat(result.version()).isEqualTo(5L);
        assertThat(menu.getConfirmedBy()).isEqualTo(7L);
        assertThat(menu.getConfirmedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 11, 0));
        verify(selectionMapper, times(1)).update(any(), any());
        ArgumentCaptor<DinnerMenuActionEntity> action =
                ArgumentCaptor.forClass(DinnerMenuActionEntity.class);
        verify(actionMapper).insert(action.capture());
        assertThat(action.getValue().getActionType()).isEqualTo("START_COOKING");
    }

    @Test
    void startRejectsEmptyDraftBeforeSnapshotOrActionWrites() {
        DinnerMenuEntity menu = menu("DRAFT", 4L);
        stubLockedToday(menu);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(
                7L, new StartCookingRequest(4L, START_KEY)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_MENU_EMPTY));

        verifyNoInteractions(snapshotAssembler);
        verify(cookingDishMapper, never())
                .insert(any(DinnerMenuCookingDishEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void startFreezesMinimalHouseholdDishWithoutImageOrIngredients() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubLockedToday(menu);
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setUserId(7L);
        selection.setRecipeId(14L);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of(selection));
        DinnerRecordSnapshotAssembler.SnapshotDraft minimal =
                minimalHouseholdDraft(14L, Set.of(7L));
        when(snapshotAssembler.assemble(11L, List.of(selection)))
                .thenReturn(List.of(minimal));
        when(cookingDishMapper.selectByMenuIdForUpdate(31L)).thenReturn(List.of());
        when(cookingDishMapper.insert(any(DinnerMenuCookingDishEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.<DinnerMenuCookingDishEntity>getArgument(0).setId(101L);
                    return 1;
                });
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(actionMapper.insert(any(DinnerMenuActionEntity.class))).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenAnswer(invocation -> {
            ArgumentCaptor<DinnerMenuCookingDishEntity> inserted =
                    ArgumentCaptor.forClass(DinnerMenuCookingDishEntity.class);
            verify(cookingDishMapper).insert(inserted.capture());
            return List.of(inserted.getValue());
        });

        var result = service.start(7L, new StartCookingRequest(5L, START_KEY));

        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.imagePath()).isNull();
            assertThat(dish.ingredients()).isEmpty();
        });
    }

    @Test
    void startReplayWithTheSameKeyReturnsTheFrozenSessionWithoutWrites() {
        DinnerMenuEntity menu = menu("COOKING", 6L);
        stubLockedToday(menu);
        DinnerMenuActionEntity previous = new DinnerMenuActionEntity();
        previous.setMenuId(31L);
        previous.setActionType("START_COOKING");
        previous.setIdempotencyKey(START_KEY);
        when(actionMapper.selectOne(any())).thenReturn(previous);
        DinnerMenuCookingDishEntity frozen = cookingRow(
                101L, householdDraft(14L, Set.of(7L, 8L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectByMenuId(31L)).thenReturn(List.of(frozen));

        var result = service.start(7L, new StartCookingRequest(5L, START_KEY));

        assertThat(result.status()).isEqualTo("COOKING");
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.id()).isEqualTo(101L);
            assertThat(dish.origin()).isEqualTo("PLANNED");
            assertThat(dish.method().steps())
                    .extracting(RecordMethodStepSnapshotResponse::instruction)
                    .containsExactly("翻炒至熟");
        });
        verifyNoInteractions(selectionMapper, snapshotAssembler);
        verify(cookingDishMapper, never())
                .insert(any(DinnerMenuCookingDishEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void addDishReplayWithTheSameKeyAndBodyReturnsTheExistingDishWithoutWrites() {
        DinnerMenuEntity menu = menu("COOKING", 8L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity replay = cookingRow(
                102L, householdDraft(14L, Set.of(7L)),
                "TEMPORARY", 7L, ADD_KEY, 1);
        when(cookingDishMapper.selectOne(any())).thenReturn(replay);
        when(cookingDishMapper.selectByMenuId(31L)).thenReturn(List.of(replay));

        var result = service.addDish(
                7L, new AddCookingDishRequest(14L, 21L, 7L, ADD_KEY));

        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.id()).isEqualTo(102L);
            assertThat(dish.origin()).isEqualTo("TEMPORARY");
            assertThat(dish.addedBy().kind()).isEqualTo("ME");
            assertThat(dish.method().id()).isEqualTo(21L);
        });
        verify(cookingDishMapper, never()).selectByMenuIdForUpdate(any());
        verify(cookingDishMapper, never())
                .insert(any(DinnerMenuCookingDishEntity.class));
        verifyNoInteractions(snapshotAssembler);
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void addDishReplayWithOmittedMethodReturnsTheOriginallyFrozenDefault() {
        DinnerMenuEntity menu = menu("COOKING", 8L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity replay = cookingRow(
                102L, householdDraft(14L, Set.of(7L)),
                "TEMPORARY", 7L, ADD_KEY, 1);
        when(cookingDishMapper.selectOne(any())).thenReturn(replay);
        when(cookingDishMapper.selectByMenuId(31L)).thenReturn(List.of(replay));

        var result = service.addDish(
                7L, new AddCookingDishRequest(14L, null, 7L, ADD_KEY));

        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.recipeId()).isEqualTo(14L);
            assertThat(dish.method().id()).isEqualTo(21L);
            assertThat(dish.method().name()).isEqualTo("家常做法");
        });
        verify(cookingDishMapper, never()).selectByMenuIdForUpdate(any());
        verify(cookingDishMapper, never())
                .insert(any(DinnerMenuCookingDishEntity.class));
        verifyNoInteractions(snapshotAssembler);
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void addDishReplayRejectsTheSameKeyWithADifferentMethodBody() {
        DinnerMenuEntity menu = menu("COOKING", 7L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity replay = cookingRow(
                102L, householdDraft(14L, Set.of(7L)), "TEMPORARY", 7L, ADD_KEY, 1);
        replay.setMethodId(21L);
        when(cookingDishMapper.selectOne(any())).thenReturn(replay);

        assertThatThrownBy(() -> service.addDish(
                7L, new AddCookingDishRequest(14L, 22L, 7L, ADD_KEY)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_COOKING_CONFLICT));

        verify(cookingDishMapper, never()).selectByMenuIdForUpdate(any());
        verifyNoInteractions(snapshotAssembler);
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void addSystemDishFreezesAUserScopedTemporarySnapshotAndAdvancesVersion() {
        DinnerMenuEntity menu = menu("COOKING", 6L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity planned = cookingRow(
                101L, householdDraft(14L, Set.of(8L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectOne(any())).thenReturn(null);
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(planned));
        when(snapshotAssembler.assembleCurrentRecipe(11L, 7L, 1L, null))
                .thenReturn(systemDraft(1L, Set.of(7L)));
        when(cookingDishMapper.insert(any(DinnerMenuCookingDishEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.<DinnerMenuCookingDishEntity>getArgument(0).setId(102L);
                    return 1;
                });
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenAnswer(invocation -> {
            ArgumentCaptor<DinnerMenuCookingDishEntity> inserted =
                    ArgumentCaptor.forClass(DinnerMenuCookingDishEntity.class);
            verify(cookingDishMapper).insert(inserted.capture());
            return List.of(planned, inserted.getValue());
        });

        var result = service.addDish(
                7L, new AddCookingDishRequest(1L, null, 6L, ADD_KEY));

        assertThat(result.version()).isEqualTo(7L);
        assertThat(result.dishes()).hasSize(2);
        assertThat(result.dishes().get(1)).satisfies(dish -> {
            assertThat(dish.id()).isEqualTo(102L);
            assertThat(dish.recipeId()).isEqualTo(1L);
            assertThat(dish.scope()).isEqualTo("SYSTEM");
            assertThat(dish.origin()).isEqualTo("TEMPORARY");
            assertThat(dish.addedBy().kind()).isEqualTo("ME");
            assertThat(dish.selectedBy())
                    .extracting(HouseholdActorResponse::kind)
                    .containsExactly("ME");
            assertThat(dish.method()).isNull();
            assertThat(dish.ingredients())
                    .extracting(RecordIngredientSnapshotResponse::name)
                    .containsExactly("番茄");
        });
        verify(snapshotAssembler).assembleCurrentRecipe(11L, 7L, 1L, null);
    }

    @Test
    void addHouseholdDishFreezesTheRequestedMethodAndAdvancesVersion() {
        DinnerMenuEntity menu = menu("COOKING", 6L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity planned = cookingRow(
                101L, systemDraft(1L, Set.of(8L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectOne(any())).thenReturn(null);
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(planned));
        when(snapshotAssembler.assembleCurrentRecipe(11L, 7L, 14L, 21L))
                .thenReturn(householdDraft(14L, Set.of(7L)));
        when(cookingDishMapper.insert(any(DinnerMenuCookingDishEntity.class)))
                .thenAnswer(invocation -> {
                    invocation.<DinnerMenuCookingDishEntity>getArgument(0).setId(102L);
                    return 1;
                });
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenAnswer(invocation -> {
            ArgumentCaptor<DinnerMenuCookingDishEntity> inserted =
                    ArgumentCaptor.forClass(DinnerMenuCookingDishEntity.class);
            verify(cookingDishMapper).insert(inserted.capture());
            return List.of(planned, inserted.getValue());
        });

        var result = service.addDish(
                7L, new AddCookingDishRequest(14L, 21L, 6L, ADD_KEY));

        assertThat(result.version()).isEqualTo(7L);
        assertThat(result.dishes().get(1)).satisfies(dish -> {
            assertThat(dish.recipeId()).isEqualTo(14L);
            assertThat(dish.scope()).isEqualTo("HOUSEHOLD");
            assertThat(dish.recipeVersion()).isEqualTo(8L);
            assertThat(dish.servings()).isEqualTo(2);
            assertThat(dish.method().id()).isEqualTo(21L);
            assertThat(dish.method().estimatedMinutes()).isEqualTo(10);
            assertThat(dish.method().steps())
                    .extracting(RecordMethodStepSnapshotResponse::instruction)
                    .containsExactly("翻炒至熟");
        });
        verify(snapshotAssembler).assembleCurrentRecipe(11L, 7L, 14L, 21L);
    }

    @Test
    void invalidOrUnauthorizedAddFailsBeforeCookingOrMenuWrites() {
        DinnerMenuEntity menu = menu("COOKING", 6L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity planned = cookingRow(
                101L, systemDraft(1L, Set.of(8L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectOne(any())).thenReturn(null);
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(planned));
        when(snapshotAssembler.assembleCurrentRecipe(11L, 7L, 14L, 21L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_INVALID));

        assertThatThrownBy(() -> service.addDish(
                7L, new AddCookingDishRequest(14L, 21L, 6L, ADD_KEY)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_INVALID));

        verify(cookingDishMapper, never())
                .insert(any(DinnerMenuCookingDishEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void sameCompletionStateStillRejectsAStaleMenuVersion() {
        DinnerMenuEntity menu = menu("COOKING", 8L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity completed = cookingRow(
                101L, systemDraft(1L, Set.of(7L)), "PLANNED", null, null, 0);
        completed.setCompletedBy(7L);
        completed.setCompletedAt(LocalDateTime.of(2026, 8, 13, 11, 0));
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(completed));

        assertThatThrownBy(() -> service.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(true, 7L)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_MENU_VERSION_CONFLICT));

        verify(cookingDishMapper, never())
                .markCompleted(any(), any(), any(), any());
        verify(cookingDishMapper, never()).clearCompletion(any(), any());
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void completingADishStoresTheActorAndTimeAndAdvancesTheMenuVersion() {
        DinnerMenuEntity menu = menu("COOKING", 8L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity dish = cookingRow(
                101L, systemDraft(1L, Set.of(7L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(dish));
        when(cookingDishMapper.markCompleted(
                101L, 31L, 7L, LocalDateTime.of(2026, 8, 13, 11, 0)))
                .thenReturn(1);
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenReturn(List.of(dish));

        var result = service.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(true, 8L));

        assertThat(result.version()).isEqualTo(9L);
        assertThat(result.dishes()).singleElement().satisfies(completed -> {
            assertThat(completed.completed()).isTrue();
            assertThat(completed.completedBy().kind()).isEqualTo("ME");
            assertThat(completed.completedAt())
                    .isEqualTo(Instant.parse("2026-08-13T11:00:00Z"));
        });
        assertThat(dish.getCompletedBy()).isEqualTo(7L);
        assertThat(dish.getCompletedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 11, 0));
        verify(cookingDishMapper).markCompleted(
                101L, 31L, 7L, LocalDateTime.of(2026, 8, 13, 11, 0));
        verify(menuMapper).updateById(menu);
    }

    @Test
    void uncompletingADishClearsTheActorAndTimeAndAdvancesTheMenuVersion() {
        DinnerMenuEntity menu = menu("COOKING", 9L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity dish = cookingRow(
                101L, householdDraft(14L, Set.of(7L)),
                "PLANNED", null, null, 0);
        dish.setCompletedBy(8L);
        dish.setCompletedAt(LocalDateTime.of(2026, 8, 13, 10, 55));
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(dish));
        when(cookingDishMapper.clearCompletion(101L, 31L)).thenReturn(1);
        when(menuMapper.updateById(menu)).thenReturn(1);
        when(cookingDishMapper.selectByMenuId(31L)).thenReturn(List.of(dish));

        var result = service.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(false, 9L));

        assertThat(result.version()).isEqualTo(10L);
        assertThat(result.dishes()).singleElement().satisfies(uncompleted -> {
            assertThat(uncompleted.completed()).isFalse();
            assertThat(uncompleted.completedBy()).isNull();
            assertThat(uncompleted.completedAt()).isNull();
        });
        assertThat(dish.getCompletedBy()).isNull();
        assertThat(dish.getCompletedAt()).isNull();
        verify(cookingDishMapper).clearCompletion(101L, 31L);
        verify(menuMapper).updateById(menu);
    }

    @Test
    void completionReplayWithCurrentVersionAndSameStateDoesNotWrite() {
        DinnerMenuEntity menu = menu("COOKING", 8L);
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity dish = cookingRow(
                101L, systemDraft(1L, Set.of(7L)),
                "PLANNED", null, null, 0);
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(dish));

        var result = service.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(false, 8L));

        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.dishes()).singleElement().satisfies(uncompleted ->
                assertThat(uncompleted.completed()).isFalse());
        verify(cookingDishMapper, never())
                .markCompleted(any(), any(), any(), any());
        verify(cookingDishMapper, never()).clearCompletion(any(), any());
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void completedMenuRejectsFurtherDishCompletionMutations() {
        DinnerMenuEntity menu = menu("COMPLETED", 10L);
        menu.setCompletedBy(8L);
        menu.setCompletedAt(LocalDateTime.of(2026, 8, 13, 11, 0));
        stubLockedToday(menu);

        assertThatThrownBy(() -> service.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(false, 10L)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_MENU_COMPLETED));

        verify(cookingDishMapper, never()).selectByMenuIdForUpdate(any());
        verify(cookingDishMapper, never())
                .markCompleted(any(), any(), any(), any());
        verify(cookingDishMapper, never()).clearCompletion(any(), any());
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @Test
    void completedSessionRemainsReadableWithItsRecordIdAndFrozenRows() {
        DinnerMenuEntity menu = menu("COMPLETED", 10L);
        menu.setCompletedBy(8L);
        menu.setCompletedAt(LocalDateTime.of(2026, 8, 13, 11, 0));
        stubLockedToday(menu);
        DinnerMenuCookingDishEntity completed = cookingRow(
                101L, systemDraft(1L, Set.of(7L, 8L)), "PLANNED", null, null, 0);
        completed.setCompletedBy(8L);
        completed.setCompletedAt(LocalDateTime.of(2026, 8, 13, 10, 59));
        when(cookingDishMapper.selectByMenuIdForUpdate(31L))
                .thenReturn(List.of(completed));
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setId(91L);
        record.setMenuId(31L);
        when(recordMapper.selectOne(any())).thenReturn(record);

        var result = service.get(7L);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.recordId()).isEqualTo(91L);
        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.name()).isEqualTo("系统菜");
            assertThat(dish.completed()).isTrue();
            assertThat(dish.completedBy().kind()).isEqualTo("PARTNER");
        });
        verify(cookingDishMapper).selectByMenuIdForUpdate(31L);
        verifyNoInteractions(snapshotAssembler);
    }

    private void stubLockedToday(DinnerMenuEntity menu) {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access());
        when(householdAccessService.lockActiveHouseholdContext(7L)).thenReturn(context);
        when(menuMapper.selectByHouseholdAndDateForUpdate(
                11L, LocalDate.of(2026, 8, 13))).thenReturn(menu);
    }

    private ActiveHouseholdAccess access() {
        return new ActiveHouseholdAccess(
                7L, 11L, 41L, 4L, "OWNER",
                LocalDateTime.of(1970, 1, 1, 0, 0), 8L, "Asia/Shanghai");
    }

    private DinnerMenuEntity menu(String status, Long version) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(31L);
        menu.setHouseholdId(11L);
        menu.setMenuDate(LocalDate.of(2026, 8, 13));
        menu.setStatus(status);
        menu.setVersion(version);
        return menu;
    }

    private DinnerMenuCookingDishEntity cookingRow(
            Long id,
            DinnerRecordSnapshotAssembler.SnapshotDraft draft,
            String origin,
            Long addedBy,
            String key,
            int sortOrder
    ) {
        DinnerMenuCookingDishEntity row = snapshotCodec.encode(
                31L, draft, origin, addedBy, key, sortOrder);
        row.setId(id);
        return row;
    }

    private DinnerMenuSelectionEntity selection(
            Long userId,
            Long recipeId,
            Long recipeVersion,
            Long methodId
    ) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        selection.setRecipeVersion(recipeVersion);
        selection.setMethodId(methodId);
        return selection;
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft systemDraft(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "SYSTEM", 1L, "系统菜", "/assets/recipes/system.jpg",
                "家常菜", "鲜香", null, 10, selectors,
                null, null, null, null, List.of(),
                List.of(new RecordIngredientSnapshotResponse(
                        101L, "番茄", BigDecimal.ONE, "个", true, 0)));
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft householdDraft(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "HOUSEHOLD", 8L, "自家番茄炒蛋",
                "https://www.osheeep.com/media/recipes/family-list.webp",
                "家常菜", "鲜香", 2, 10, selectors,
                21L, "家常做法", "炒", 10,
                List.of(new RecordMethodStepSnapshotResponse("翻炒至熟", 0)),
                List.of(new RecordIngredientSnapshotResponse(
                        201L, "鸡蛋", new BigDecimal("2.000"), "枚", true, 0)));
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft minimalHouseholdDraft(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "HOUSEHOLD", 8L, "番茄炒蛋", null,
                "荤菜", "家常", 2, 15, selectors,
                21L, "默认做法", "家常", 15,
                List.of(new RecordMethodStepSnapshotResponse("按家里习惯做即可", 0)),
                List.of());
    }
}
