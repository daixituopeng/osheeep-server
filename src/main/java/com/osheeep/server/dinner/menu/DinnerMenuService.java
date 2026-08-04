package com.osheeep.server.dinner.menu;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdActorLabelService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.menu.dto.MenuMethodChoiceResponse;
import com.osheeep.server.dinner.menu.dto.MenuMethodResolutionRequest;
import com.osheeep.server.dinner.menu.dto.MenuSelectionRequest;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.dto.WeekMenuResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.notification.DinnerNotificationPublisher;
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.recipe.DinnerRecipeCatalogAssembler;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.util.StringUtils;

@Service
public class DinnerMenuService {

    private final DinnerHouseholdAccessService householdAccessService;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerImageAssetService imageAssetService;
    private final DinnerRecipeCatalogAssembler catalogAssembler;
    private final DinnerHouseholdActorLabelService actorLabelService;
    private final BusinessDateResolver businessDateResolver;
    private final Clock clock;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    @Autowired
    public DinnerMenuService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerImageAssetService imageAssetService,
            DinnerRecipeCatalogAssembler catalogAssembler,
            DinnerHouseholdActorLabelService actorLabelService,
            BusinessDateResolver businessDateResolver
    ) {
        this(householdAccessService, menuMapper, selectionMapper, actionMapper,
                recipeMapper, methodMapper, imageAssetService, catalogAssembler,
                actorLabelService, businessDateResolver, Clock.systemUTC());
    }

    DinnerMenuService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerImageAssetService imageAssetService,
            DinnerRecipeCatalogAssembler catalogAssembler,
            DinnerHouseholdActorLabelService actorLabelService,
            BusinessDateResolver businessDateResolver,
            Clock clock
    ) {
        this.householdAccessService = householdAccessService;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.recipeMapper = recipeMapper;
        this.methodMapper = methodMapper;
        this.imageAssetService = imageAssetService;
        this.catalogAssembler = catalogAssembler;
        this.actorLabelService = actorLabelService;
        this.businessDateResolver = businessDateResolver;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    @Transactional
    public TodayMenuResponse today(Long userId) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate menuDate = businessDateResolver.resolve(access.timezone(), clock.instant());
        DinnerMenuEntity menu = lockMenuForUpdate(access.householdId(), menuDate);
        if (menu == null) {
            menu = createDraftLocked(access.householdId(), menuDate);
        }
        return responseForLockedContext(userId, lockedContext, menu);
    }

    @Transactional
    public TodayMenuResponse scheduled(Long userId, LocalDate menuDate) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate businessDate = businessDate(access);
        requireReadableScheduleDate(menuDate, businessDate);
        DinnerMenuEntity menu =
                lockMenuForUpdate(access.householdId(), menuDate);
        if (menu == null) {
            return TodayMenuResponse.emptyDraft(menuDate);
        }
        return responseForLockedContext(userId, lockedContext, menu);
    }

    @Transactional
    public WeekMenuResponse week(Long userId, LocalDate startDate) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate businessDate = businessDate(access);
        requireWeekStart(startDate, businessDate);
        LocalDate endDate = startDate.plusDays(6);
        Map<LocalDate, DinnerMenuEntity> menusByDate = new LinkedHashMap<>();
        for (DinnerMenuEntity menu : menuMapper.selectByHouseholdAndDateRange(
                access.householdId(), startDate, endDate)) {
            if (menu == null
                    || menu.getMenuDate() == null
                    || menusByDate.putIfAbsent(menu.getMenuDate(), menu) != null) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR);
            }
        }
        List<TodayMenuResponse> menus = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            LocalDate date = startDate.plusDays(index);
            DinnerMenuEntity menu = menusByDate.get(date);
            menus.add(menu == null
                    ? TodayMenuResponse.emptyDraft(date)
                    : responseForLockedContext(userId, lockedContext, menu));
        }
        return new WeekMenuResponse(startDate, endDate, List.copyOf(menus));
    }

    @Transactional
    public TodayMenuResponse updateSelections(
            Long userId,
            List<Long> requestedRecipeIds,
            long expectedVersion
    ) {
        List<MenuSelectionRequest> requestedSelections = requestedRecipeIds == null
                ? List.of()
                : requestedRecipeIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(recipeId -> new MenuSelectionRequest(recipeId, null))
                        .toList();
        return updateMethodSelections(userId, requestedSelections, expectedVersion);
    }

    @Transactional
    public TodayMenuResponse updateScheduledSelections(
            Long userId,
            LocalDate menuDate,
            List<Long> requestedRecipeIds,
            long expectedVersion
    ) {
        List<MenuSelectionRequest> requestedSelections = requestedRecipeIds == null
                ? List.of()
                : requestedRecipeIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(recipeId -> new MenuSelectionRequest(recipeId, null))
                        .toList();
        return updateScheduledMethodSelections(
                userId, menuDate, requestedSelections, expectedVersion);
    }

    @Transactional
    public TodayMenuResponse updateMethodSelections(
            Long userId,
            List<MenuSelectionRequest> requestedSelections,
            long expectedVersion
    ) {
        return updateMethodSelections(
                userId, lockToday(userId), requestedSelections, expectedVersion);
    }

    @Transactional
    public TodayMenuResponse updateScheduledMethodSelections(
            Long userId,
            LocalDate menuDate,
            List<MenuSelectionRequest> requestedSelections,
            long expectedVersion
    ) {
        return updateMethodSelections(
                userId,
                lockScheduled(userId, menuDate, true),
                requestedSelections,
                expectedVersion);
    }

    private TodayMenuResponse updateMethodSelections(
            Long userId,
            MenuContext context,
            List<MenuSelectionRequest> requestedSelections,
            long expectedVersion
    ) {
        DinnerMenuEntity menu = context.menu();
        requireVersion(menu, expectedVersion);
        requireMutable(menu);

        List<RequestedSelection> selectionsToSave =
                normalizeSelections(requestedSelections);
        List<Long> recipeIds = selectionsToSave.stream()
                .map(RequestedSelection::recipeId)
                .toList();
        Map<Long, ValidatedRecipe> recipesById =
                validateRecipes(selectionsToSave, context.access().householdId());

        List<DinnerMenuSelectionEntity> currentSelections = selections(menu.getId());
        List<SelectionIdentityWithRecipe> currentUserSelections = currentSelections.stream()
                .filter(selection -> userId.equals(selection.getUserId()))
                .map(selection -> new SelectionIdentityWithRecipe(
                        selection.getRecipeId(), selection.getRecipeVersion(),
                        selection.getMethodId()))
                .sorted(Comparator.comparing(SelectionIdentityWithRecipe::recipeId))
                .toList();
        List<SelectionIdentityWithRecipe> requestedIdentities = selectionsToSave.stream()
                .map(selection -> {
                    ValidatedRecipe validated = recipesById.get(selection.recipeId());
                    return new SelectionIdentityWithRecipe(
                            selection.recipeId(), validated.selectedVersion(),
                            validated.method() == null ? null : validated.method().id());
                })
                .toList();
        if (currentUserSelections.equals(requestedIdentities)) {
            return response(context, userId);
        }

        selectionMapper.delete(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                .eq(DinnerMenuSelectionEntity::getMenuId, menu.getId())
                .eq(DinnerMenuSelectionEntity::getUserId, userId));
        for (RequestedSelection requested : selectionsToSave) {
            Long recipeId = requested.recipeId();
            DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
            selection.setMenuId(menu.getId());
            selection.setUserId(userId);
            selection.setRecipeId(recipeId);
            ValidatedRecipe validated = recipesById.get(recipeId);
            selection.setRecipeVersion(validated.selectedVersion());
            selection.setMethodId(
                    validated.method() == null ? null : validated.method().id());
            selectionMapper.insert(selection);
        }
        boolean reconfirmRequired = "CONFIRMED".equals(menu.getStatus());
        if (reconfirmRequired) {
            menu.setStatus("DRAFT");
            menu.setConfirmedBy(null);
            menu.setConfirmedAt(null);
        }
        menu.setVersion(menu.getVersion() + 1);
        menuMapper.updateById(menu);
        DinnerNotificationType type = reconfirmRequired
                ? DinnerNotificationType.MENU_RECONFIRM_REQUIRED
                : DinnerNotificationType.PARTNER_SELECTION_UPDATED;
        notificationPublisher.toPartner(
                context.access().householdId(),
                userId,
                type,
                DinnerNotificationReferenceType.MENU,
                menu.getId(),
                menu.getVersion(),
                "menu:" + menu.getId() + ":version:" + menu.getVersion());
        return response(context, userId);
    }

    @Transactional
    public TodayMenuResponse confirm(Long userId, long expectedVersion, String idempotencyKey) {
        return confirm(userId, expectedVersion, idempotencyKey, List.of());
    }

    @Transactional
    public TodayMenuResponse confirm(
            Long userId,
            long expectedVersion,
            String idempotencyKey,
            List<MenuMethodResolutionRequest> requestedResolutions
    ) {
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        DinnerMenuActionEntity previousAction = actionMapper.selectOne(
                Wrappers.<DinnerMenuActionEntity>lambdaQuery()
                        .eq(DinnerMenuActionEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
        if (previousAction != null) {
            return response(context, userId);
        }
        requireVersion(menu, expectedVersion);
        requireMutable(menu);
        List<DinnerMenuSelectionEntity> currentSelections = selections(menu.getId());
        if (currentSelections.isEmpty()) {
            throw new BusinessException(ErrorCode.DINNER_MENU_EMPTY);
        }
        if ("CONFIRMED".equals(menu.getStatus())) {
            return response(context, userId);
        }
        resolveMethodConflicts(
                menu.getId(), currentSelections,
                requestedResolutions == null ? List.of() : requestedResolutions);
        menu.setStatus("CONFIRMED");
        menu.setConfirmedBy(userId);
        menu.setConfirmedAt(now());
        menu.setVersion(menu.getVersion() + 1);
        menuMapper.updateById(menu);

        DinnerMenuActionEntity action = new DinnerMenuActionEntity();
        action.setMenuId(menu.getId());
        action.setActorId(userId);
        action.setActionType("CONFIRM");
        action.setIdempotencyKey(idempotencyKey);
        actionMapper.insert(action);
        return response(context, userId);
    }

    private void resolveMethodConflicts(
            Long menuId,
            List<DinnerMenuSelectionEntity> selections,
            List<MenuMethodResolutionRequest> requestedResolutions
    ) {
        Map<Long, Set<Long>> conflicts = methodConflicts(selections);
        Map<Long, Long> resolutions = new LinkedHashMap<>();
        for (MenuMethodResolutionRequest resolution : requestedResolutions) {
            if (resolution == null
                    || resolution.recipeId() == null
                    || resolution.methodId() == null
                    || resolutions.putIfAbsent(
                            resolution.recipeId(), resolution.methodId()) != null) {
                throw new BusinessException(
                        ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
            }
        }
        if (!conflicts.keySet().containsAll(resolutions.keySet())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
        }
        if (!resolutions.keySet().containsAll(conflicts.keySet())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_METHOD_RESOLUTION_REQUIRED);
        }
        for (Map.Entry<Long, Long> resolution : resolutions.entrySet()) {
            if (!conflicts.get(resolution.getKey()).contains(resolution.getValue())) {
                throw new BusinessException(
                        ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
            }
            selectionMapper.update(
                    null,
                    Wrappers.<DinnerMenuSelectionEntity>lambdaUpdate()
                            .eq(DinnerMenuSelectionEntity::getMenuId, menuId)
                            .eq(DinnerMenuSelectionEntity::getRecipeId, resolution.getKey())
                            .set(DinnerMenuSelectionEntity::getMethodId, resolution.getValue()));
        }
    }

    private Map<Long, Set<Long>> methodConflicts(
            List<DinnerMenuSelectionEntity> selections
    ) {
        Map<Long, Long> versions = new LinkedHashMap<>();
        Map<Long, Set<Long>> methods = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection.getRecipeId() == null || selection.getRecipeVersion() == null) {
                throw invalidRecipe();
            }
            Long previousVersion = versions.putIfAbsent(
                    selection.getRecipeId(), selection.getRecipeVersion());
            if (previousVersion != null
                    && !previousVersion.equals(selection.getRecipeVersion())) {
                throw invalidRecipe();
            }
            if (selection.getMethodId() != null) {
                methods.computeIfAbsent(
                                selection.getRecipeId(), ignored -> new LinkedHashSet<>())
                        .add(selection.getMethodId());
            }
        }
        Map<Long, Set<Long>> conflicts = new LinkedHashMap<>();
        methods.forEach((recipeId, methodIds) -> {
            if (methodIds.size() > 1) {
                conflicts.put(recipeId, Set.copyOf(methodIds));
            }
        });
        return Map.copyOf(conflicts);
    }

    private DinnerMenuEntity createDraftLocked(Long householdId, LocalDate menuDate) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setHouseholdId(householdId);
        menu.setMenuDate(menuDate);
        menu.setStatus("DRAFT");
        menu.setVersion(0L);
        try {
            menuMapper.insert(menu);
            return menu;
        } catch (DuplicateKeyException exception) {
            DinnerMenuEntity winner = lockMenuForUpdate(householdId, menuDate);
            if (winner == null) {
                throw exception;
            }
            return winner;
        } catch (PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private TodayMenuResponse response(MenuContext context, Long currentUserId) {
        return responseForLockedContext(
                currentUserId, context.lockedContext(), context.menu());
    }

    public TodayMenuResponse responseForLockedContext(
            Long currentUserId,
            LockedHouseholdContext lockedContext,
            DinnerMenuEntity menu
    ) {
        ActiveHouseholdAccess access =
                lockedContext == null ? null : lockedContext.access();
        if (access == null
                || menu == null
                || !Objects.equals(currentUserId, access.userId())
                || !Objects.equals(menu.getHouseholdId(), access.householdId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return response(menu, currentUserId, access);
    }

    private TodayMenuResponse response(
            DinnerMenuEntity menu,
            Long currentUserId,
            ActiveHouseholdAccess access
    ) {
        if (isPreMembershipCompletedMenu(menu, access.historyVisibleFrom())) {
            return TodayMenuResponse.preMembership(menu.getMenuDate());
        }
        return fullResponse(menu, currentUserId);
    }

    private TodayMenuResponse fullResponse(DinnerMenuEntity menu, Long currentUserId) {
        List<DinnerMenuSelectionEntity> selections = selections(menu.getId());
        Map<Long, Set<Long>> selectorsByRecipe = new LinkedHashMap<>();
        Map<Long, Long> versionsByRecipe = new LinkedHashMap<>();
        Map<Long, Map<Long, Set<Long>>> selectorsByRecipeAndMethod =
                new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection.getRecipeId() == null
                    || selection.getUserId() == null
                    || selection.getRecipeVersion() == null) {
                throw invalidRecipe();
            }
            Long previousVersion = versionsByRecipe.putIfAbsent(
                    selection.getRecipeId(), selection.getRecipeVersion());
            if (previousVersion != null
                    && !previousVersion.equals(selection.getRecipeVersion())) {
                throw invalidRecipe();
            }
            selectorsByRecipe.computeIfAbsent(
                            selection.getRecipeId(), ignored -> new LinkedHashSet<>())
                    .add(selection.getUserId());
            selectorsByRecipeAndMethod
                    .computeIfAbsent(
                            selection.getRecipeId(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(
                            selection.getMethodId(), ignored -> new LinkedHashSet<>())
                    .add(selection.getUserId());
        }

        List<Long> recipeIds = selectorsByRecipe.keySet().stream().sorted().toList();
        Map<Long, DinnerRecipeEntity> recipesById = loadRecipes(recipeIds);
        List<Long> methodIds = new ArrayList<>();
        List<Long> imageAssetIds = new ArrayList<>();
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            Long recipeVersion = versionsByRecipe.get(recipeId);
            Set<Long> selectedMethodIds =
                    selectorsByRecipeAndMethod.get(recipeId).keySet();
            if ("SYSTEM".equals(recipe.getScope())) {
                if (!"PUBLISHED".equals(recipe.getStatus())
                        || !Objects.equals(recipeVersion, 1L)
                        || selectedMethodIds.size() != 1
                        || !selectedMethodIds.contains(null)) {
                    throw invalidRecipe();
                }
                continue;
            }
            if (!"HOUSEHOLD".equals(recipe.getScope())
                    || !isMenuReadableHouseholdStatus(recipe.getStatus())
                    || !Objects.equals(recipe.getHouseholdId(), menu.getHouseholdId())
                    || recipeVersion <= 0
                    || selectedMethodIds.contains(null)
                    || recipe.getImageAssetId() == null) {
                throw invalidRecipe();
            }
            methodIds.addAll(selectedMethodIds);
            imageAssetIds.add(recipe.getImageAssetId());
        }

        List<Long> distinctMethodIds = methodIds.stream().distinct().sorted().toList();
        Map<Long, DinnerRecipeMethodEntity> methodsById = loadMethods(distinctMethodIds);
        List<Long> distinctImageAssetIds = imageAssetIds.stream().distinct().sorted().toList();
        Map<Long, ImageAssetResponse> imagesById = distinctImageAssetIds.isEmpty()
                ? Map.of()
                : imageAssetService.findApprovedByIds(distinctImageAssetIds);
        if (!imagesById.keySet().equals(new LinkedHashSet<>(distinctImageAssetIds))) {
            throw invalidRecipe();
        }

        Set<Long> actorUserIds = new LinkedHashSet<>();
        selectorsByRecipe.values().forEach(actorUserIds::addAll);
        actorUserIds.add(menu.getConfirmedBy());
        actorUserIds.add(menu.getCompletedBy());
        Map<Long, HouseholdActorResponse> actors = actorLabelService.resolve(
                menu.getHouseholdId(), currentUserId, actorUserIds);

        List<MenuDishResponse> dishes = new ArrayList<>();
        int consensusCount = 0;
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            Set<Long> selectors = selectorsByRecipe.get(recipeId);
            List<HouseholdActorResponse> selectedBy =
                    actorLabelService.ordered(selectors, actors);
            String source = source(selectedBy);
            if (selectors.size() > 1) {
                consensusCount++;
            }
            RecipeMethodSummaryResponse method = null;
            List<MenuMethodChoiceResponse> methodChoices = List.of();
            boolean methodConflict = false;
            String imagePath = recipe.getImagePath();
            if ("HOUSEHOLD".equals(recipe.getScope())) {
                List<MenuMethodChoiceResponse> choices = new ArrayList<>();
                for (Map.Entry<Long, Set<Long>> selectedMethod :
                        selectorsByRecipeAndMethod.get(recipeId).entrySet()) {
                    DinnerRecipeMethodEntity savedMethod =
                            methodsById.get(selectedMethod.getKey());
                    if (savedMethod == null
                            || !Objects.equals(savedMethod.getRecipeId(), recipeId)
                            || !"ACTIVE".equals(savedMethod.getStatus())
                            || !StringUtils.hasText(savedMethod.getName())
                            || !StringUtils.hasText(savedMethod.getCookingStyle())) {
                        throw invalidRecipe();
                    }
                    RecipeMethodSummaryResponse summary =
                            new RecipeMethodSummaryResponse(
                                    savedMethod.getId(), savedMethod.getName(),
                                    savedMethod.getCookingStyle());
                    choices.add(new MenuMethodChoiceResponse(
                            summary,
                            actorLabelService.ordered(selectedMethod.getValue(), actors)));
                }
                choices.sort(Comparator.comparing(choice -> choice.method().id()));
                methodChoices = List.copyOf(choices);
                methodConflict = methodChoices.size() > 1;
                method = methodConflict ? null : methodChoices.getFirst().method();
                ImageAssetResponse image = imagesById.get(recipe.getImageAssetId());
                if (image == null || !StringUtils.hasText(image.listUrl())) {
                    throw invalidRecipe();
                }
                imagePath = image.listUrl();
            }
            dishes.add(new MenuDishResponse(
                    recipe.getId(), recipe.getName(), imagePath, recipe.getCategory(),
                    recipe.getFlavor(), recipe.getEstimatedMinutes(), source, selectedBy,
                    recipe.getScope(), versionsByRecipe.get(recipeId), method,
                    methodChoices, methodConflict));
        }

        List<Long> selectedRecipeIds = selections.stream()
                .filter(selection -> currentUserId.equals(selection.getUserId()))
                .map(DinnerMenuSelectionEntity::getRecipeId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        int partnerSelectionCount = Math.toIntExact(selections.stream()
                .filter(selection -> !currentUserId.equals(selection.getUserId()))
                .map(DinnerMenuSelectionEntity::getRecipeId)
                .distinct()
                .count());

        return new TodayMenuResponse(
                menu.getId(), menu.getMenuDate(), menu.getStatus(), menu.getVersion(),
                selectedRecipeIds.size(), partnerSelectionCount, consensusCount,
                selectedRecipeIds, dishes, nullableActor(menu.getConfirmedBy(), actors),
                instant(menu.getConfirmedAt()), nullableActor(menu.getCompletedBy(), actors),
                instant(menu.getCompletedAt()), null, true);
    }

    private String source(List<HouseholdActorResponse> selectedBy) {
        Set<String> kinds = selectedBy.stream()
                .map(HouseholdActorResponse::kind)
                .collect(java.util.stream.Collectors.toSet());
        if (selectedBy.size() == 2 && kinds.equals(Set.of("ME", "PARTNER"))) {
            return "BOTH";
        }
        if (selectedBy.size() == 1
                && Set.of("ME", "PARTNER").contains(selectedBy.getFirst().kind())) {
            return selectedBy.getFirst().kind();
        }
        return null;
    }

    private HouseholdActorResponse nullableActor(
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

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private MenuContext lockToday(Long userId) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate menuDate = businessDateResolver.resolve(access.timezone(), clock.instant());
        DinnerMenuEntity menu = lockMenuForUpdate(access.householdId(), menuDate);
        if (menu == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Today's dinner menu was not initialized");
        }
        return new MenuContext(lockedContext, menu);
    }

    private MenuContext lockScheduled(
            Long userId,
            LocalDate menuDate,
            boolean requireEditable
    ) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate businessDate = businessDate(access);
        requireReadableScheduleDate(menuDate, businessDate);
        if (requireEditable && menuDate.isBefore(businessDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        DinnerMenuEntity menu =
                lockMenuForUpdate(access.householdId(), menuDate);
        if (menu == null) {
            menu = createDraftLocked(access.householdId(), menuDate);
        }
        return new MenuContext(lockedContext, menu);
    }

    private LocalDate businessDate(ActiveHouseholdAccess access) {
        return businessDateResolver.resolve(access.timezone(), clock.instant());
    }

    private void requireReadableScheduleDate(
            LocalDate menuDate,
            LocalDate businessDate
    ) {
        if (menuDate == null
                || menuDate.isBefore(businessDate.minusDays(42))
                || menuDate.isAfter(businessDate.plusDays(42))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireWeekStart(LocalDate startDate, LocalDate businessDate) {
        requireReadableScheduleDate(startDate, businessDate);
        if (startDate.getDayOfWeek() != DayOfWeek.MONDAY
                || startDate.plusDays(6).isAfter(businessDate.plusDays(42))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private DinnerMenuEntity lockMenuForUpdate(Long householdId, LocalDate menuDate) {
        try {
            return menuMapper.selectByHouseholdAndDateForUpdate(householdId, menuDate);
        } catch (PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private boolean isPreMembershipCompletedMenu(
            DinnerMenuEntity menu,
            LocalDateTime historyVisibleFrom
    ) {
        return "COMPLETED".equals(menu.getStatus())
                && (menu.getCompletedAt() == null
                        || menu.getCompletedAt().isBefore(historyVisibleFrom));
    }

    private void requireVersion(DinnerMenuEntity menu, long expectedVersion) {
        if (!Objects.equals(menu.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private void requireMutable(DinnerMenuEntity menu) {
        if ("COMPLETED".equals(menu.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_COMPLETED);
        }
    }

    private List<RequestedSelection> normalizeSelections(
            List<MenuSelectionRequest> requestedSelections
    ) {
        if (requestedSelections == null || requestedSelections.isEmpty()) {
            return List.of();
        }
        Map<Long, RequestedSelection> unique = new LinkedHashMap<>();
        for (MenuSelectionRequest selection : requestedSelections) {
            if (selection == null
                    || selection.recipeId() == null
                    || selection.recipeId() <= 0
                    || (selection.methodId() != null && selection.methodId() <= 0)
                    || unique.putIfAbsent(
                            selection.recipeId(),
                            new RequestedSelection(
                                    selection.recipeId(), selection.methodId())) != null) {
                throw invalidRecipe();
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(RequestedSelection::recipeId))
                .toList();
    }

    private Map<Long, ValidatedRecipe> validateRecipes(
            List<RequestedSelection> requestedSelections,
            Long householdId
    ) {
        List<Long> recipeIds = requestedSelections.stream()
                .map(RequestedSelection::recipeId)
                .toList();
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        List<DinnerRecipeEntity> recipes = recipeMapper.selectByIds(recipeIds);
        Map<Long, DinnerRecipeEntity> recipesById = mapRecipes(recipes, recipeIds);
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            boolean system = "SYSTEM".equals(recipe.getScope())
                    && "PUBLISHED".equals(recipe.getStatus());
            boolean household = "HOUSEHOLD".equals(recipe.getScope())
                    && "PUBLISHED".equals(recipe.getStatus())
                    && Objects.equals(recipe.getHouseholdId(), householdId)
                    && recipe.getVersion() != null
                    && recipe.getVersion() > 0;
            if (!system && !household) {
                throw invalidRecipe();
            }
        }
        Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> catalog =
                catalogAssembler.assemble(recipes);
        if (!catalog.keySet().equals(new LinkedHashSet<>(recipeIds))) {
            throw invalidRecipe();
        }
        Map<Long, ValidatedRecipe> validated = new LinkedHashMap<>();
        for (RequestedSelection requested : requestedSelections) {
            Long recipeId = requested.recipeId();
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            DinnerRecipeCatalogAssembler.CatalogEntry entry = catalog.get(recipeId);
            RecipeMethodSummaryResponse method = entry.defaultMethod();
            if ("SYSTEM".equals(recipe.getScope())) {
                if (method != null || requested.methodId() != null) {
                    throw invalidRecipe();
                }
            } else if (method == null) {
                throw invalidRecipe();
            } else if (requested.methodId() != null) {
                method = entry.methods().stream()
                        .filter(option -> requested.methodId().equals(option.id()))
                        .findFirst()
                        .map(option -> new RecipeMethodSummaryResponse(
                                option.id(), option.name(), option.cookingStyle()))
                        .orElseGet(() -> requested.methodId().equals(entry.defaultMethod().id())
                                ? entry.defaultMethod()
                                : null);
                if (method == null) {
                    throw invalidRecipe();
                }
            }
            validated.put(recipeId, new ValidatedRecipe(recipe, method));
        }
        return Map.copyOf(validated);
    }

    private boolean isMenuReadableHouseholdStatus(String status) {
        return "PUBLISHED".equals(status) || "ARCHIVED".equals(status);
    }

    private Map<Long, DinnerRecipeEntity> loadRecipes(List<Long> recipeIds) {
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        return mapRecipes(recipeMapper.selectByIds(recipeIds), recipeIds);
    }

    private Map<Long, DinnerRecipeEntity> mapRecipes(
            List<DinnerRecipeEntity> recipes,
            List<Long> expectedIds
    ) {
        Map<Long, DinnerRecipeEntity> recipesById = new HashMap<>();
        for (DinnerRecipeEntity recipe : recipes) {
            if (recipe == null
                    || recipe.getId() == null
                    || recipesById.putIfAbsent(recipe.getId(), recipe) != null) {
                throw invalidRecipe();
            }
        }
        if (!recipesById.keySet().equals(new LinkedHashSet<>(expectedIds))) {
            throw invalidRecipe();
        }
        return recipesById;
    }

    private Map<Long, DinnerRecipeMethodEntity> loadMethods(List<Long> methodIds) {
        if (methodIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DinnerRecipeMethodEntity> methodsById = new HashMap<>();
        for (DinnerRecipeMethodEntity method : methodMapper.selectByIds(methodIds)) {
            if (method == null
                    || method.getId() == null
                    || methodsById.putIfAbsent(method.getId(), method) != null) {
                throw invalidRecipe();
            }
        }
        if (!methodsById.keySet().equals(new LinkedHashSet<>(methodIds))) {
            throw invalidRecipe();
        }
        return methodsById;
    }

    private BusinessException invalidRecipe() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }

    private List<DinnerMenuSelectionEntity> selections(Long menuId) {
        return selectionMapper.selectList(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                .eq(DinnerMenuSelectionEntity::getMenuId, menuId));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record MenuContext(
            LockedHouseholdContext lockedContext,
            DinnerMenuEntity menu
    ) {
        private ActiveHouseholdAccess access() {
            return lockedContext.access();
        }
    }

    private record ValidatedRecipe(
            DinnerRecipeEntity recipe,
            RecipeMethodSummaryResponse method
    ) {
        long selectedVersion() {
            return "SYSTEM".equals(recipe.getScope()) ? 1L : recipe.getVersion();
        }
    }

    private record RequestedSelection(Long recipeId, Long methodId) {
    }

    private record SelectionIdentityWithRecipe(
            Long recipeId,
            Long recipeVersion,
            Long methodId
    ) {
    }
}
