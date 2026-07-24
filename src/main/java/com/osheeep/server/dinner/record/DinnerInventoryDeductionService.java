package com.osheeep.server.dinner.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.osheeep.server.dinner.notification.DinnerNotificationReferenceType;
import com.osheeep.server.dinner.notification.DinnerNotificationType;
import com.osheeep.server.dinner.record.dto.HandleInventoryDeductionRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionAppliedItemResponse;
import com.osheeep.server.dinner.record.dto.InventoryDeductionItemRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionProposalItemResponse;
import com.osheeep.server.dinner.record.dto.InventoryDeductionResponse;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerInventoryDeductionService {

    private static final Set<String> STATUSES =
            Set.of("NOT_APPLICABLE", "PENDING", "APPLIED", "SKIPPED");
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}"
                    + "-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final DinnerHouseholdAccessService householdAccessService;
    private final DinnerHouseholdActorLabelService actorLabelService;
    private final DinnerCookingRecordMapper recordMapper;
    private final DinnerRecordDishSnapshotMapper snapshotMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerRecordSnapshotJsonCodec snapshotJsonCodec;
    private final DinnerInventoryDeductionJsonCodec deductionJsonCodec;
    private final Clock clock;
    private DinnerNotificationPublisher notificationPublisher =
            DinnerNotificationPublisher.noop();

    @Autowired
    public DinnerInventoryDeductionService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerHouseholdActorLabelService actorLabelService,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecordSnapshotJsonCodec snapshotJsonCodec,
            DinnerInventoryDeductionJsonCodec deductionJsonCodec
    ) {
        this(householdAccessService, actorLabelService, recordMapper, snapshotMapper,
                inventoryMapper, snapshotJsonCodec, deductionJsonCodec, Clock.systemUTC());
    }

    DinnerInventoryDeductionService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerHouseholdActorLabelService actorLabelService,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecordSnapshotJsonCodec snapshotJsonCodec,
            DinnerInventoryDeductionJsonCodec deductionJsonCodec,
            Clock clock
    ) {
        this.householdAccessService = householdAccessService;
        this.actorLabelService = actorLabelService;
        this.recordMapper = recordMapper;
        this.snapshotMapper = snapshotMapper;
        this.inventoryMapper = inventoryMapper;
        this.snapshotJsonCodec = snapshotJsonCodec;
        this.deductionJsonCodec = deductionJsonCodec;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setNotificationPublisher(DinnerNotificationPublisher notificationPublisher) {
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher);
    }

    public InventoryDeductionResponse get(Long userId, Long recordId) {
        ActiveHouseholdAccess access = householdAccessService.requireActiveHousehold(userId);
        DinnerCookingRecordEntity record = recordMapper.selectById(recordId);
        requireVisibleRecord(access, record);
        return response(userId, access, record);
    }

    @Transactional
    public InventoryDeductionResponse handle(
            Long userId,
            Long recordId,
            HandleInventoryDeductionRequest request
    ) {
        if (request == null
                || !Set.of("APPLY", "SKIP").contains(request.action())
                || request.items() == null
                || request.items().size() > 100
                || !StringUtils.hasText(request.idempotencyKey())
                || !UUID_V4.matcher(request.idempotencyKey()).matches()) {
            throw invalidDeduction();
        }

        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        DinnerCookingRecordEntity record = lockRecord(access.householdId(), recordId);
        requireVisibleRecord(access, record);
        requireValidState(record);
        if ("APPLIED".equals(record.getInventoryDeductionStatus())
                || "SKIPPED".equals(record.getInventoryDeductionStatus())) {
            return response(userId, access, record);
        }
        if (!"PENDING".equals(record.getInventoryDeductionStatus())) {
            throw invalidDeduction();
        }

        if ("SKIP".equals(request.action())) {
            if (!request.items().isEmpty()) {
                throw invalidDeduction();
            }
            finish(record, userId, request.idempotencyKey(), "SKIPPED", List.of());
            return response(userId, access, record);
        }
        if (request.items().isEmpty()) {
            throw invalidDeduction();
        }

        Map<Long, SnapshotIngredient> snapshotIngredients = snapshotIngredients(record.getId());
        List<InventoryDeductionItemRequest> requestedItems = validatedRequestItems(
                request.items(), snapshotIngredients);
        List<Long> ingredientIds = requestedItems.stream()
                .map(InventoryDeductionItemRequest::ingredientId)
                .sorted()
                .toList();
        Map<Long, DinnerHouseholdInventoryEntity> inventoryByIngredient =
                lockInventory(access.householdId(), ingredientIds);

        List<InventoryDeductionAppliedItemResponse> appliedItems = new ArrayList<>();
        for (InventoryDeductionItemRequest requested : requestedItems) {
            SnapshotIngredient snapshot = snapshotIngredients.get(requested.ingredientId());
            DinnerHouseholdInventoryEntity inventory =
                    inventoryByIngredient.get(requested.ingredientId());
            validateApplyItem(requested, snapshot, inventory);
            BigDecimal before = inventory.getQuantity();
            BigDecimal after = before.subtract(requested.quantity());
            inventory.setQuantity(after);
            inventory.setUpdatedBy(userId);
            inventory.setVersion(inventory.getVersion() + 1L);
            try {
                if (inventoryMapper.updateById(inventory) != 1) {
                    throw deductionConflict();
                }
            } catch (PessimisticLockingFailureException exception) {
                throw deductionConflict();
            }
            appliedItems.add(new InventoryDeductionAppliedItemResponse(
                    requested.ingredientId(),
                    snapshot.name(),
                    inventory.getUnit(),
                    requested.quantity(),
                    before,
                    after,
                    inventory.getVersion()));
        }

        finish(record, userId, request.idempotencyKey(), "APPLIED", appliedItems);
        for (InventoryDeductionAppliedItemResponse item : appliedItems) {
            notificationPublisher.toPartner(
                    access.householdId(),
                    userId,
                    DinnerNotificationType.INVENTORY_UPDATED,
                    DinnerNotificationReferenceType.INVENTORY,
                    item.ingredientId(),
                    item.resultingVersion(),
                    "record:" + record.getId()
                            + ":inventory:" + item.ingredientId()
                            + ":version:" + item.resultingVersion());
        }
        return response(userId, access, record);
    }

    private InventoryDeductionResponse response(
            Long userId,
            ActiveHouseholdAccess access,
            DinnerCookingRecordEntity record
    ) {
        requireValidState(record);
        String status = record.getInventoryDeductionStatus();
        if ("PENDING".equals(status)) {
            return new InventoryDeductionResponse(
                    record.getId(), status, null, null,
                    proposal(record, access.householdId()), List.of());
        }
        if ("NOT_APPLICABLE".equals(status)) {
            return new InventoryDeductionResponse(
                    record.getId(), status, null, null, List.of(), List.of());
        }
        Map<Long, HouseholdActorResponse> actors = actorLabelService.resolve(
                access.householdId(), userId, List.of(record.getInventoryDeductedBy()));
        HouseholdActorResponse handledBy = actors.get(record.getInventoryDeductedBy());
        if (handledBy == null) {
            throw invalidState();
        }
        List<InventoryDeductionAppliedItemResponse> appliedItems =
                deductionJsonCodec.read(record.getInventoryDeductionItems());
        if ("APPLIED".equals(status) && appliedItems.isEmpty()) {
            throw invalidState();
        }
        if ("SKIPPED".equals(status) && !appliedItems.isEmpty()) {
            throw invalidState();
        }
        return new InventoryDeductionResponse(
                record.getId(),
                status,
                handledBy,
                instant(record.getInventoryDeductedAt()),
                List.of(),
                appliedItems);
    }

    private List<InventoryDeductionProposalItemResponse> proposal(
            DinnerCookingRecordEntity record,
            Long householdId
    ) {
        Map<Long, SnapshotIngredient> ingredients = snapshotIngredients(record.getId());
        List<Long> ingredientIds = ingredients.keySet().stream().sorted().toList();
        Map<Long, DinnerHouseholdInventoryEntity> inventoryByIngredient =
                inventoryMapper.selectList(
                                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId,
                                                householdId)
                                        .in(DinnerHouseholdInventoryEntity::getIngredientId,
                                                ingredientIds))
                        .stream()
                        .collect(Collectors.toMap(
                                DinnerHouseholdInventoryEntity::getIngredientId,
                                item -> item));
        return ingredientIds.stream()
                .map(id -> proposalItem(ingredients.get(id), inventoryByIngredient.get(id)))
                .toList();
    }

    private InventoryDeductionProposalItemResponse proposalItem(
            SnapshotIngredient ingredient,
            DinnerHouseholdInventoryEntity inventory
    ) {
        String eligibility;
        BigDecimal suggested = null;
        if (inventory == null) {
            eligibility = "NOT_IN_INVENTORY";
        } else if (!validInventory(inventory)) {
            throw invalidState();
        } else if (ingredient.units().size() != 1
                || !ingredient.units().contains(inventory.getUnit())) {
            eligibility = "UNIT_MISMATCH";
        } else if (!ingredient.quantityKnown()
                || ingredient.quantity().signum() <= 0) {
            eligibility = "RECIPE_QUANTITY_UNKNOWN";
        } else if (inventory.getQuantity() == null) {
            eligibility = "INVENTORY_QUANTITY_UNKNOWN";
        } else if (inventory.getQuantity().signum() <= 0) {
            eligibility = "INSUFFICIENT";
        } else {
            suggested = ingredient.quantity().min(inventory.getQuantity());
            eligibility = inventory.getQuantity().compareTo(ingredient.quantity()) < 0
                    ? "INSUFFICIENT" : "READY";
        }
        boolean selectable = suggested != null && suggested.signum() > 0;
        return new InventoryDeductionProposalItemResponse(
                ingredient.ingredientId(),
                ingredient.name(),
                ingredient.quantityKnown() ? ingredient.quantity() : null,
                ingredient.firstUnit(),
                ingredient.required(),
                inventory == null ? null : inventory.getQuantity(),
                inventory == null ? null : inventory.getUnit(),
                inventory == null ? null : inventory.getVersion(),
                suggested,
                selectable && ingredient.required(),
                eligibility);
    }

    private Map<Long, SnapshotIngredient> snapshotIngredients(Long recordId) {
        List<DinnerRecordDishSnapshotEntity> snapshots = snapshotMapper.selectList(
                Wrappers.<DinnerRecordDishSnapshotEntity>lambdaQuery()
                        .eq(DinnerRecordDishSnapshotEntity::getRecordId, recordId)
                        .orderByAsc(DinnerRecordDishSnapshotEntity::getSortOrder));
        if (snapshots.isEmpty()) {
            throw invalidState();
        }
        Map<Long, SnapshotIngredientBuilder> builders = new LinkedHashMap<>();
        for (DinnerRecordDishSnapshotEntity snapshot : snapshots) {
            List<RecordIngredientSnapshotResponse> ingredients =
                    snapshotJsonCodec.readIngredients(snapshot.getIngredientsJson());
            if (ingredients.isEmpty()) {
                throw invalidState();
            }
            for (RecordIngredientSnapshotResponse ingredient : ingredients) {
                SnapshotIngredientBuilder builder = builders.computeIfAbsent(
                        ingredient.ingredientId(),
                        ignored -> new SnapshotIngredientBuilder(
                                ingredient.ingredientId(), ingredient.name()));
                builder.add(ingredient);
            }
        }
        if (builders.isEmpty() || builders.size() > 100) {
            throw invalidState();
        }
        Map<Long, SnapshotIngredient> result = new LinkedHashMap<>();
        builders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().build()));
        return Map.copyOf(result);
    }

    private List<InventoryDeductionItemRequest> validatedRequestItems(
            List<InventoryDeductionItemRequest> items,
            Map<Long, SnapshotIngredient> snapshotIngredients
    ) {
        Set<Long> ids = new HashSet<>();
        for (InventoryDeductionItemRequest item : items) {
            if (item == null
                    || item.ingredientId() == null
                    || item.ingredientId() <= 0
                    || !ids.add(item.ingredientId())
                    || !snapshotIngredients.containsKey(item.ingredientId())
                    || !validPositiveQuantity(item.quantity())
                    || item.inventoryVersion() == null
                    || item.inventoryVersion() <= 0) {
                throw invalidDeduction();
            }
        }
        return items.stream()
                .sorted((left, right) ->
                        left.ingredientId().compareTo(right.ingredientId()))
                .toList();
    }

    private Map<Long, DinnerHouseholdInventoryEntity> lockInventory(
            Long householdId,
            List<Long> ingredientIds
    ) {
        try {
            List<DinnerHouseholdInventoryEntity> inventory =
                    inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                            householdId, ingredientIds);
            Map<Long, DinnerHouseholdInventoryEntity> byIngredient = new HashMap<>();
            for (DinnerHouseholdInventoryEntity item : inventory) {
                if (item == null
                        || !Objects.equals(item.getHouseholdId(), householdId)
                        || item.getIngredientId() == null
                        || byIngredient.putIfAbsent(item.getIngredientId(), item) != null) {
                    throw invalidState();
                }
            }
            if (!byIngredient.keySet().equals(new HashSet<>(ingredientIds))) {
                throw deductionConflict();
            }
            return byIngredient;
        } catch (PessimisticLockingFailureException exception) {
            throw deductionConflict();
        }
    }

    private void validateApplyItem(
            InventoryDeductionItemRequest requested,
            SnapshotIngredient snapshot,
            DinnerHouseholdInventoryEntity inventory
    ) {
        if (snapshot == null
                || inventory == null
                || !validInventory(inventory)
                || snapshot.units().size() != 1
                || !snapshot.units().contains(inventory.getUnit())
                || !snapshot.quantityKnown()
                || snapshot.quantity().signum() <= 0) {
            throw invalidDeduction();
        }
        if (inventory.getQuantity() == null
                || !Objects.equals(inventory.getVersion(), requested.inventoryVersion())
                || inventory.getQuantity().compareTo(requested.quantity()) < 0) {
            throw deductionConflict();
        }
    }

    private boolean validInventory(DinnerHouseholdInventoryEntity inventory) {
        return inventory.getId() != null
                && inventory.getHouseholdId() != null
                && inventory.getIngredientId() != null
                && StringUtils.hasText(inventory.getUnit())
                && inventory.getVersion() != null
                && inventory.getVersion() >= 1
                && (inventory.getQuantity() == null
                        || validNonnegativeQuantity(inventory.getQuantity()));
    }

    private void finish(
            DinnerCookingRecordEntity record,
            Long userId,
            String idempotencyKey,
            String status,
            List<InventoryDeductionAppliedItemResponse> appliedItems
    ) {
        String json = deductionJsonCodec.write(appliedItems);
        record.setInventoryDeductionStatus(status);
        record.setInventoryDeductionKey(idempotencyKey);
        record.setInventoryDeductedBy(userId);
        record.setInventoryDeductedAt(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        record.setInventoryDeductionItems(json);
        try {
            if (recordMapper.updateById(record) != 1) {
                throw deductionConflict();
            }
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw deductionConflict();
        }
    }

    private DinnerCookingRecordEntity lockRecord(Long householdId, Long recordId) {
        try {
            return recordMapper.selectByHouseholdAndIdForUpdate(householdId, recordId);
        } catch (PessimisticLockingFailureException exception) {
            throw deductionConflict();
        }
    }

    private void requireVisibleRecord(
            ActiveHouseholdAccess access,
            DinnerCookingRecordEntity record
    ) {
        if (record == null
                || !Objects.equals(access.householdId(), record.getHouseholdId())
                || record.getCompletedAt() == null
                || record.getCompletedAt().isBefore(access.historyVisibleFrom())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireValidState(DinnerCookingRecordEntity record) {
        String status = record.getInventoryDeductionStatus();
        if (!STATUSES.contains(status)) {
            throw invalidState();
        }
        boolean terminal = "APPLIED".equals(status) || "SKIPPED".equals(status);
        boolean hasAllMetadata = StringUtils.hasText(record.getInventoryDeductionKey())
                && record.getInventoryDeductedBy() != null
                && record.getInventoryDeductedAt() != null
                && StringUtils.hasText(record.getInventoryDeductionItems());
        boolean hasAnyMetadata = record.getInventoryDeductionKey() != null
                || record.getInventoryDeductedBy() != null
                || record.getInventoryDeductedAt() != null
                || record.getInventoryDeductionItems() != null;
        if ((terminal && !hasAllMetadata) || (!terminal && hasAnyMetadata)) {
            throw invalidState();
        }
    }

    private boolean validPositiveQuantity(BigDecimal value) {
        return validQuantity(value) && value.signum() > 0;
    }

    private boolean validNonnegativeQuantity(BigDecimal value) {
        return validQuantity(value) && value.signum() >= 0;
    }

    private boolean validQuantity(BigDecimal value) {
        if (value == null) {
            return false;
        }
        int integerDigits = Math.max(value.precision() - value.scale(), 0);
        return value.scale() <= 3 && integerDigits <= 9;
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private BusinessException invalidDeduction() {
        return new BusinessException(ErrorCode.DINNER_INVENTORY_DEDUCTION_INVALID);
    }

    private BusinessException deductionConflict() {
        return new BusinessException(ErrorCode.DINNER_INVENTORY_DEDUCTION_CONFLICT);
    }

    private IllegalStateException invalidState() {
        return new IllegalStateException("Invalid dinner inventory deduction state");
    }

    private record SnapshotIngredient(
            Long ingredientId,
            String name,
            Set<String> units,
            String firstUnit,
            boolean required,
            boolean quantityKnown,
            BigDecimal quantity
    ) {
    }

    private static final class SnapshotIngredientBuilder {
        private final Long ingredientId;
        private final String name;
        private final Set<String> units = new HashSet<>();
        private boolean required;
        private boolean quantityKnown = true;
        private BigDecimal quantity = BigDecimal.ZERO;

        private SnapshotIngredientBuilder(Long ingredientId, String name) {
            this.ingredientId = ingredientId;
            this.name = name;
        }

        private void add(RecordIngredientSnapshotResponse value) {
            if (!Objects.equals(ingredientId, value.ingredientId())
                    || !Objects.equals(name, value.name())
                    || !StringUtils.hasText(value.unit())) {
                throw new IllegalStateException(
                        "Invalid dinner inventory deduction state");
            }
            units.add(value.unit());
            required = required || value.required();
            if (value.quantity() == null) {
                quantityKnown = false;
            } else {
                quantity = quantity.add(value.quantity());
            }
        }

        private SnapshotIngredient build() {
            if (units.isEmpty()) {
                throw new IllegalStateException(
                        "Invalid dinner inventory deduction state");
            }
            String firstUnit = units.stream().sorted().findFirst().orElseThrow();
            return new SnapshotIngredient(
                    ingredientId,
                    name,
                    Set.copyOf(units),
                    firstUnit,
                    required,
                    quantityKnown,
                    quantityKnown ? quantity : BigDecimal.ZERO);
        }
    }
}
