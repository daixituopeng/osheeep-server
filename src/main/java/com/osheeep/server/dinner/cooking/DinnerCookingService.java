package com.osheeep.server.dinner.cooking;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.cooking.dto.AddCookingDishRequest;
import com.osheeep.server.dinner.cooking.dto.CookingDishResponse;
import com.osheeep.server.dinner.cooking.dto.CookingMethodSnapshotResponse;
import com.osheeep.server.dinner.cooking.dto.CookingSessionResponse;
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
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.record.DinnerRecordSnapshotAssembler;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerCookingService {

    private static final int MAX_DISHES = 20;

    private final DinnerHouseholdAccessService householdAccessService;
    private final DinnerHouseholdActorLabelService actorLabelService;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerMenuCookingDishMapper cookingDishMapper;
    private final DinnerCookingRecordMapper recordMapper;
    private final DinnerRecordSnapshotAssembler snapshotAssembler;
    private final DinnerCookingSnapshotCodec snapshotCodec;
    private final BusinessDateResolver businessDateResolver;
    private final Clock clock;

    @Autowired
    public DinnerCookingService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerHouseholdActorLabelService actorLabelService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerMenuCookingDishMapper cookingDishMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordSnapshotAssembler snapshotAssembler,
            DinnerCookingSnapshotCodec snapshotCodec,
            BusinessDateResolver businessDateResolver
    ) {
        this(householdAccessService, actorLabelService, menuMapper, selectionMapper,
                actionMapper, cookingDishMapper, recordMapper, snapshotAssembler, snapshotCodec,
                businessDateResolver, Clock.systemUTC());
    }

    DinnerCookingService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerHouseholdActorLabelService actorLabelService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerMenuCookingDishMapper cookingDishMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordSnapshotAssembler snapshotAssembler,
            DinnerCookingSnapshotCodec snapshotCodec,
            BusinessDateResolver businessDateResolver,
            Clock clock
    ) {
        this.householdAccessService = householdAccessService;
        this.actorLabelService = actorLabelService;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.cookingDishMapper = cookingDishMapper;
        this.recordMapper = recordMapper;
        this.snapshotAssembler = snapshotAssembler;
        this.snapshotCodec = snapshotCodec;
        this.businessDateResolver = businessDateResolver;
        this.clock = clock;
    }

    @Transactional
    public CookingSessionResponse start(Long userId, StartCookingRequest request) {
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        DinnerMenuActionEntity previous = actionByKey(request.idempotencyKey());
        if (previous != null) {
            requireMatchingStartReplay(previous, menu);
            return response(userId, context.access(), menu, cookingDishes(menu.getId()));
        }
        requireVersion(menu, request.version());
        if (!"CONFIRMED".equals(menu.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_CONFIRMED);
        }

        List<DinnerMenuSelectionEntity> selections = selectionMapper.selectList(
                Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                        .eq(DinnerMenuSelectionEntity::getMenuId, menu.getId()));
        List<DinnerRecordSnapshotAssembler.SnapshotDraft> drafts =
                snapshotAssembler.assemble(context.access().householdId(), selections);
        if (drafts.isEmpty() || drafts.size() > MAX_DISHES) {
            throw new BusinessException(ErrorCode.DINNER_COOKING_DISH_INVALID);
        }

        List<DinnerMenuCookingDishEntity> staleRows =
                cookingDishMapper.selectByMenuIdForUpdate(menu.getId());
        if (!staleRows.isEmpty()) {
            cookingDishMapper.delete(
                    Wrappers.<DinnerMenuCookingDishEntity>lambdaQuery()
                            .eq(DinnerMenuCookingDishEntity::getMenuId, menu.getId()));
        }
        int sortOrder = 0;
        for (DinnerRecordSnapshotAssembler.SnapshotDraft draft : drafts) {
            DinnerMenuCookingDishEntity row = snapshotCodec.encode(
                    menu.getId(), draft, "PLANNED", null, null, sortOrder++);
            if (cookingDishMapper.insert(row) != 1) {
                throw cookingConflict();
            }
        }

        menu.setStatus("COOKING");
        menu.setVersion(menu.getVersion() + 1L);
        if (menuMapper.updateById(menu) != 1) {
            throw cookingConflict();
        }
        DinnerMenuActionEntity action = new DinnerMenuActionEntity();
        action.setMenuId(menu.getId());
        action.setActorId(userId);
        action.setActionType("START_COOKING");
        action.setIdempotencyKey(request.idempotencyKey());
        try {
            if (actionMapper.insert(action) != 1) {
                throw cookingConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw cookingConflict();
        }
        return response(userId, context.access(), menu, cookingDishes(menu.getId()));
    }

    @Transactional
    public CookingSessionResponse get(Long userId) {
        ActiveHouseholdAccess access =
                householdAccessService.lockActiveHouseholdContext(userId).access();
        LocalDate menuDate = businessDateResolver.resolve(access.timezone(), clock.instant());
        DinnerMenuEntity menu = menuMapper.selectByHouseholdAndDateForUpdate(
                access.householdId(), menuDate);
        requireReadableCookingMenu(access, menu);
        return response(
                userId, access, menu,
                List.copyOf(cookingDishMapper.selectByMenuIdForUpdate(menu.getId())));
    }

    @Transactional
    public CookingSessionResponse addDish(
            Long userId,
            AddCookingDishRequest request
    ) {
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        DinnerMenuCookingDishEntity replay = cookingDishMapper.selectOne(
                Wrappers.<DinnerMenuCookingDishEntity>lambdaQuery()
                        .eq(DinnerMenuCookingDishEntity::getAddIdempotencyKey,
                                request.idempotencyKey())
                        .last("LIMIT 1"));
        if (replay != null) {
            if (!Objects.equals(replay.getMenuId(), menu.getId())
                    || !Objects.equals(replay.getRecipeId(), request.recipeId())
                    || (request.methodId() != null
                            && !Objects.equals(
                                    replay.getMethodId(), request.methodId()))) {
                throw cookingConflict();
            }
            return response(userId, context.access(), menu, cookingDishes(menu.getId()));
        }
        requireCooking(menu);
        requireVersion(menu, request.version());
        List<DinnerMenuCookingDishEntity> current =
                cookingDishMapper.selectByMenuIdForUpdate(menu.getId());
        if (current.size() >= MAX_DISHES
                || current.stream().anyMatch(row ->
                        Objects.equals(row.getRecipeId(), request.recipeId()))) {
            throw new BusinessException(ErrorCode.DINNER_COOKING_DISH_INVALID);
        }

        DinnerRecordSnapshotAssembler.SnapshotDraft draft =
                snapshotAssembler.assembleCurrentRecipe(
                        context.access().householdId(), userId,
                        request.recipeId(), request.methodId());
        int sortOrder = current.stream()
                .map(DinnerMenuCookingDishEntity::getSortOrder)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
        DinnerMenuCookingDishEntity row = snapshotCodec.encode(
                menu.getId(), draft, "TEMPORARY", userId,
                request.idempotencyKey(), sortOrder);
        try {
            if (cookingDishMapper.insert(row) != 1) {
                throw cookingConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw cookingConflict();
        }
        menu.setVersion(menu.getVersion() + 1L);
        if (menuMapper.updateById(menu) != 1) {
            throw cookingConflict();
        }
        return response(userId, context.access(), menu, cookingDishes(menu.getId()));
    }

    @Transactional
    public CookingSessionResponse setCompleted(
            Long userId,
            Long dishId,
            UpdateCookingDishCompletionRequest request
    ) {
        if (dishId == null || dishId <= 0) {
            throw new BusinessException(ErrorCode.DINNER_COOKING_DISH_NOT_FOUND);
        }
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        requireCooking(menu);
        List<DinnerMenuCookingDishEntity> current =
                cookingDishMapper.selectByMenuIdForUpdate(menu.getId());
        DinnerMenuCookingDishEntity dish = current.stream()
                .filter(row -> Objects.equals(row.getId(), dishId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DINNER_COOKING_DISH_NOT_FOUND));
        boolean alreadyCompleted = dish.getCompletedBy() != null;
        requireVersion(menu, request.version());
        if (alreadyCompleted == request.completed()) {
            return response(userId, context.access(), menu, current);
        }
        if (request.completed()) {
            LocalDateTime completedAt = now();
            if (cookingDishMapper.markCompleted(
                    dish.getId(), menu.getId(), userId, completedAt) != 1) {
                throw cookingConflict();
            }
            dish.setCompletedBy(userId);
            dish.setCompletedAt(completedAt);
        } else {
            if (cookingDishMapper.clearCompletion(dish.getId(), menu.getId()) != 1) {
                throw cookingConflict();
            }
            dish.setCompletedBy(null);
            dish.setCompletedAt(null);
        }
        menu.setVersion(menu.getVersion() + 1L);
        if (menuMapper.updateById(menu) != 1) {
            throw cookingConflict();
        }
        return response(userId, context.access(), menu, cookingDishes(menu.getId()));
    }

    private CookingSessionResponse response(
            Long userId,
            ActiveHouseholdAccess access,
            DinnerMenuEntity menu,
            List<DinnerMenuCookingDishEntity> rows
    ) {
        requireReadableCookingMenu(access, menu);
        if (rows == null || rows.isEmpty() || rows.size() > MAX_DISHES) {
            throw new IllegalStateException("Invalid dinner cooking state");
        }
        Set<Long> actorIds = new LinkedHashSet<>();
        for (DinnerMenuCookingDishEntity row : rows) {
            actorIds.addAll(snapshotCodec.selectedUserIds(row));
            actorIds.add(row.getAddedBy());
            actorIds.add(row.getCompletedBy());
        }
        Map<Long, HouseholdActorResponse> actors = actorLabelService.resolve(
                access.householdId(), userId, actorIds);
        List<CookingDishResponse> dishes = rows.stream()
                .map(row -> cookingDish(row, actors))
                .toList();
        return new CookingSessionResponse(
                menu.getId(), menu.getMenuDate(), menu.getStatus(), menu.getVersion(),
                completedRecordId(menu), dishes);
    }

    private Long completedRecordId(DinnerMenuEntity menu) {
        if (!"COMPLETED".equals(menu.getStatus())) {
            return null;
        }
        DinnerCookingRecordEntity record = recordMapper.selectOne(
                Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                        .eq(DinnerCookingRecordEntity::getMenuId, menu.getId())
                        .last("LIMIT 1"));
        if (record == null || record.getId() == null) {
            throw new IllegalStateException("Completed dinner cooking record is missing");
        }
        return record.getId();
    }

    private CookingDishResponse cookingDish(
            DinnerMenuCookingDishEntity row,
            Map<Long, HouseholdActorResponse> actors
    ) {
        DinnerRecordSnapshotAssembler.SnapshotDraft draft = snapshotCodec.decode(row);
        boolean completed = row.getCompletedBy() != null && row.getCompletedAt() != null;
        if ((row.getCompletedBy() == null) != (row.getCompletedAt() == null)
                || row.getId() == null
                || row.getSortOrder() == null
                || row.getSortOrder() < 0
                || !("PLANNED".equals(row.getOrigin())
                        || "TEMPORARY".equals(row.getOrigin()))) {
            throw new IllegalStateException("Invalid dinner cooking state");
        }
        CookingMethodSnapshotResponse method = draft.methodId() == null
                ? null
                : new CookingMethodSnapshotResponse(
                        draft.methodId(), draft.methodName(), draft.cookingStyle(),
                        draft.methodEstimatedMinutes(), draft.steps());
        return new CookingDishResponse(
                row.getId(), draft.recipeId(), draft.name(), draft.imagePath(),
                draft.category(), draft.flavor(), draft.estimatedMinutes(), draft.scope(),
                draft.recipeVersion(), draft.servings(), method, draft.ingredients(),
                row.getOrigin(),
                actorLabelService.ordered(draft.selectedByUserIds(), actors),
                actor(row.getAddedBy(), actors), completed,
                actor(row.getCompletedBy(), actors), instant(row.getCompletedAt()),
                row.getSortOrder());
    }

    private MenuContext lockToday(Long userId) {
        LockedHouseholdContext locked =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = locked.access();
        LocalDate menuDate = businessDateResolver.resolve(access.timezone(), clock.instant());
        DinnerMenuEntity menu;
        try {
            menu = menuMapper.selectByHouseholdAndDateForUpdate(
                    access.householdId(), menuDate);
        } catch (PessimisticLockingFailureException exception) {
            throw cookingConflict();
        }
        if (menu == null) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_CONFIRMED);
        }
        return new MenuContext(access, menu);
    }

    private DinnerMenuActionEntity actionByKey(String idempotencyKey) {
        return actionMapper.selectOne(
                Wrappers.<DinnerMenuActionEntity>lambdaQuery()
                        .eq(DinnerMenuActionEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
    }

    private void requireMatchingStartReplay(
            DinnerMenuActionEntity action,
            DinnerMenuEntity menu
    ) {
        if (!Objects.equals(action.getMenuId(), menu.getId())
                || !"START_COOKING".equals(action.getActionType())
                || !("COOKING".equals(menu.getStatus())
                        || "COMPLETED".equals(menu.getStatus()))) {
            throw cookingConflict();
        }
    }

    private void requireReadableCookingMenu(
            ActiveHouseholdAccess access,
            DinnerMenuEntity menu
    ) {
        if (menu == null
                || !Objects.equals(menu.getHouseholdId(), access.householdId())
                || !("COOKING".equals(menu.getStatus())
                        || "COMPLETED".equals(menu.getStatus()))) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_COOKING);
        }
        if ("COMPLETED".equals(menu.getStatus())
                && (menu.getCompletedAt() == null
                        || menu.getCompletedAt().isBefore(access.historyVisibleFrom()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireCooking(DinnerMenuEntity menu) {
        if (!"COOKING".equals(menu.getStatus())) {
            if ("COMPLETED".equals(menu.getStatus())) {
                throw new BusinessException(ErrorCode.DINNER_MENU_COMPLETED);
            }
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_COOKING);
        }
    }

    private void requireVersion(DinnerMenuEntity menu, Long expectedVersion) {
        if (!Objects.equals(menu.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private List<DinnerMenuCookingDishEntity> cookingDishes(Long menuId) {
        return List.copyOf(cookingDishMapper.selectByMenuId(menuId));
    }

    private HouseholdActorResponse actor(
            Long userId,
            Map<Long, HouseholdActorResponse> actors
    ) {
        if (userId == null) {
            return null;
        }
        HouseholdActorResponse actor = actors.get(userId);
        if (actor == null) {
            throw new IllegalStateException("Unresolved household actor");
        }
        return actor;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private BusinessException cookingConflict() {
        return new BusinessException(ErrorCode.DINNER_COOKING_CONFLICT);
    }

    private record MenuContext(
            ActiveHouseholdAccess access,
            DinnerMenuEntity menu
    ) {
    }
}
