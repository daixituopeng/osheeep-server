package com.osheeep.server.dinner.shopping;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.ingredient.dto.InventoryItemResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingItemRequest;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingResponse;
import com.osheeep.server.dinner.shopping.dto.ShoppingItemResponse;
import com.osheeep.server.dinner.shopping.entity.DinnerHouseholdShoppingItemEntity;
import com.osheeep.server.dinner.shopping.mapper.DinnerHouseholdShoppingItemMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerShoppingService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MAX_INVENTORY_QUANTITY =
            new BigDecimal("999999999.999");

    private final DinnerHouseholdShoppingItemMapper shoppingItemMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerHouseholdAccessService accessService;

    public DinnerShoppingService(
            DinnerHouseholdShoppingItemMapper shoppingItemMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper,
            DinnerHouseholdAccessService accessService
    ) {
        this.shoppingItemMapper = shoppingItemMapper;
        this.inventoryMapper = inventoryMapper;
        this.ingredientMapper = ingredientMapper;
        this.accessService = accessService;
    }

    public List<ShoppingItemResponse> listItems(Long userId) {
        ActiveHouseholdAccess access = accessService.requireActiveHousehold(userId);
        return listItemsByHousehold(access.householdId());
    }

    @Transactional
    public ShoppingItemResponse addItem(Long userId, Long ingredientId) {
        try {
            ActiveHouseholdAccess access =
                    accessService.lockActiveHouseholdContext(userId).access();
            requireActiveIngredient(ingredientId, access.householdId());
            DinnerHouseholdShoppingItemEntity existing =
                    shoppingItemMapper.selectByHouseholdAndIngredientForUpdate(
                            access.householdId(), ingredientId);
            if (existing != null) {
                return toResponse(existing);
            }

            DinnerHouseholdShoppingItemEntity item =
                    new DinnerHouseholdShoppingItemEntity();
            item.setHouseholdId(access.householdId());
            item.setIngredientId(ingredientId);
            item.setAddedBy(userId);
            if (shoppingItemMapper.insert(item) != 1) {
                throw conflict();
            }
            DinnerHouseholdShoppingItemEntity persisted =
                    shoppingItemMapper.selectById(item.getId());
            if (!validShoppingItem(persisted, access.householdId())) {
                throw conflict();
            }
            return toResponse(persisted);
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    @Transactional
    public void removeItem(Long userId, Long ingredientId) {
        try {
            ActiveHouseholdAccess access =
                    accessService.lockActiveHouseholdContext(userId).access();
            if (ingredientId == null || ingredientId <= 0) {
                throw invalid();
            }
            shoppingItemMapper.delete(
                    Wrappers.<DinnerHouseholdShoppingItemEntity>lambdaQuery()
                            .eq(DinnerHouseholdShoppingItemEntity::getHouseholdId,
                                    access.householdId())
                            .eq(DinnerHouseholdShoppingItemEntity::getIngredientId,
                                    ingredientId));
        } catch (PessimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    @Transactional
    public CompleteShoppingResponse complete(
            Long userId,
            List<CompleteShoppingItemRequest> requestedItems
    ) {
        List<CompleteShoppingItemRequest> requests = normalizeRequests(requestedItems);
        try {
            ActiveHouseholdAccess access =
                    accessService.lockActiveHouseholdContext(userId).access();
            Long householdId = access.householdId();

            List<DinnerHouseholdShoppingItemEntity> shoppingItems =
                    shoppingItemMapper.selectAllByHouseholdIdForUpdate(householdId);
            Map<Long, DinnerHouseholdShoppingItemEntity> shoppingByIngredient =
                    shoppingItems.stream()
                            .filter(item -> validShoppingItem(item, householdId))
                            .collect(Collectors.toMap(
                                    DinnerHouseholdShoppingItemEntity::getIngredientId,
                                    Function.identity()));
            if (shoppingByIngredient.size() != shoppingItems.size()
                    || requests.stream().anyMatch(request ->
                    !shoppingByIngredient.containsKey(request.ingredientId()))) {
                throw invalid();
            }

            List<Long> ingredientIds = requests.stream()
                    .map(CompleteShoppingItemRequest::ingredientId)
                    .toList();
            Map<Long, DinnerIngredientEntity> ingredientsById =
                    ingredientMapper.selectByIds(ingredientIds).stream()
                            .collect(Collectors.toMap(
                                    DinnerIngredientEntity::getId, Function.identity()));
            for (Long ingredientId : ingredientIds) {
                requireActiveIngredient(
                        ingredientsById.get(ingredientId), ingredientId, householdId);
            }

            Map<Long, DinnerHouseholdInventoryEntity> inventoryByIngredient =
                    inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                                    householdId, ingredientIds).stream()
                            .collect(Collectors.toMap(
                                    DinnerHouseholdInventoryEntity::getIngredientId,
                                    Function.identity()));
            for (CompleteShoppingItemRequest request : requests) {
                DinnerIngredientEntity ingredient =
                        ingredientsById.get(request.ingredientId());
                DinnerHouseholdInventoryEntity inventory =
                        inventoryByIngredient.get(request.ingredientId());
                mergeIntoInventory(userId, householdId, request, ingredient, inventory);
            }

            List<Long> completedIds = requests.stream()
                    .map(request -> shoppingByIngredient.get(request.ingredientId()).getId())
                    .toList();
            if (shoppingItemMapper.deleteBatchIds(completedIds) != completedIds.size()) {
                throw conflict();
            }
            return new CompleteShoppingResponse(
                    listInventoryByHousehold(householdId),
                    listItemsByHousehold(householdId));
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    private List<CompleteShoppingItemRequest> normalizeRequests(
            List<CompleteShoppingItemRequest> requestedItems
    ) {
        if (requestedItems == null || requestedItems.isEmpty()
                || requestedItems.size() > 100) {
            throw invalid();
        }
        Map<Long, CompleteShoppingItemRequest> byIngredient = new LinkedHashMap<>();
        for (CompleteShoppingItemRequest request : requestedItems) {
            if (request == null || request.ingredientId() == null
                    || request.ingredientId() <= 0
                    || request.quantity() == null
                    || request.quantity().signum() <= 0
                    || request.quantity().scale() > 3
                    || request.quantity().compareTo(MAX_INVENTORY_QUANTITY) > 0
                    || !StringUtils.hasText(request.unit())
                    || request.unit().strip().length() > 16
                    || byIngredient.put(request.ingredientId(), request) != null) {
                throw invalid();
            }
        }
        return byIngredient.values().stream()
                .sorted(Comparator.comparing(CompleteShoppingItemRequest::ingredientId))
                .map(request -> new CompleteShoppingItemRequest(
                        request.ingredientId(), request.quantity(), request.unit().strip()))
                .toList();
    }

    private void mergeIntoInventory(
            Long userId,
            Long householdId,
            CompleteShoppingItemRequest request,
            DinnerIngredientEntity ingredient,
            DinnerHouseholdInventoryEntity inventory
    ) {
        if (inventory == null) {
            String unit = requireUnit(ingredient.getDefaultUnit());
            if (!unit.equals(request.unit())) {
                throw invalid();
            }
            DinnerHouseholdInventoryEntity created =
                    new DinnerHouseholdInventoryEntity();
            created.setHouseholdId(householdId);
            created.setIngredientId(request.ingredientId());
            created.setQuantity(request.quantity());
            created.setUnit(unit);
            created.setVersion(1L);
            created.setUpdatedBy(userId);
            if (inventoryMapper.insert(created) != 1) {
                throw conflict();
            }
            return;
        }

        if (!validInventory(inventory, householdId, request.ingredientId())) {
            throw conflict();
        }
        String unit = requireUnit(inventory.getUnit());
        if (!unit.equals(request.unit())) {
            throw invalid();
        }
        BigDecimal nextQuantity = inventory.getQuantity() == null
                ? request.quantity()
                : inventory.getQuantity().add(request.quantity());
        if (nextQuantity.signum() < 0
                || nextQuantity.compareTo(MAX_INVENTORY_QUANTITY) > 0
                || nextQuantity.scale() > 3) {
            throw invalid();
        }
        inventory.setQuantity(nextQuantity);
        inventory.setUnit(unit);
        inventory.setUpdatedBy(userId);
        inventory.setVersion(Math.addExact(inventory.getVersion(), 1L));
        if (inventoryMapper.updateById(inventory) != 1) {
            throw conflict();
        }
    }

    private List<InventoryItemResponse> listInventoryByHousehold(Long householdId) {
        List<DinnerHouseholdInventoryEntity> inventory = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId, householdId)
                        .orderByAsc(DinnerHouseholdInventoryEntity::getId));
        if (inventory.isEmpty()) {
            return List.of();
        }
        Map<Long, DinnerIngredientEntity> ingredientsById = ingredientMapper.selectByIds(
                        inventory.stream()
                                .map(DinnerHouseholdInventoryEntity::getIngredientId)
                                .distinct()
                                .toList()).stream()
                .collect(Collectors.toMap(
                        DinnerIngredientEntity::getId, Function.identity()));
        List<InventoryItemResponse> responses = new ArrayList<>();
        for (DinnerHouseholdInventoryEntity item : inventory) {
            DinnerIngredientEntity ingredient = ingredientsById.get(item.getIngredientId());
            if (!validInventory(item, householdId, item.getIngredientId())
                    || ingredient == null) {
                throw conflict();
            }
            responses.add(new InventoryItemResponse(
                    item.getIngredientId(), ingredient.getName(), ingredient.getCategory(),
                    item.getQuantity(), item.getUnit(), item.getVersion(), item.getUpdatedBy(),
                    instant(item.getUpdatedAt())));
        }
        return List.copyOf(responses);
    }

    private List<ShoppingItemResponse> listItemsByHousehold(Long householdId) {
        List<DinnerHouseholdShoppingItemEntity> items =
                shoppingItemMapper.selectByHouseholdId(householdId);
        if (items.stream().anyMatch(item -> !validShoppingItem(item, householdId))) {
            throw conflict();
        }
        return items.stream().map(this::toResponse).toList();
    }

    private DinnerIngredientEntity requireActiveIngredient(
            Long ingredientId,
            Long householdId
    ) {
        if (ingredientId == null || ingredientId <= 0) {
            throw invalid();
        }
        DinnerIngredientEntity ingredient = ingredientMapper.selectById(ingredientId);
        requireActiveIngredient(ingredient, ingredientId, householdId);
        return ingredient;
    }

    private void requireActiveIngredient(
            DinnerIngredientEntity ingredient,
            Long ingredientId,
            Long householdId
    ) {
        boolean valid = ingredient != null
                && Objects.equals(ingredientId, ingredient.getId())
                && "ACTIVE".equals(ingredient.getStatus())
                && ("SYSTEM".equals(ingredient.getScope())
                || ("HOUSEHOLD".equals(ingredient.getScope())
                && Objects.equals(householdId, ingredient.getHouseholdId())));
        if (!valid) {
            throw new BusinessException(ErrorCode.DINNER_INGREDIENT_INVALID);
        }
    }

    private boolean validShoppingItem(
            DinnerHouseholdShoppingItemEntity item,
            Long householdId
    ) {
        return item != null
                && item.getId() != null
                && Objects.equals(householdId, item.getHouseholdId())
                && item.getIngredientId() != null
                && item.getIngredientId() > 0
                && item.getCreatedAt() != null;
    }

    private boolean validInventory(
            DinnerHouseholdInventoryEntity item,
            Long householdId,
            Long ingredientId
    ) {
        return item != null
                && item.getId() != null
                && Objects.equals(householdId, item.getHouseholdId())
                && Objects.equals(ingredientId, item.getIngredientId())
                && item.getVersion() != null
                && item.getVersion() >= 1
                && StringUtils.hasText(item.getUnit())
                && (item.getQuantity() == null
                || (item.getQuantity().signum() >= 0 && item.getQuantity().scale() <= 3));
    }

    private String requireUnit(String unit) {
        if (!StringUtils.hasText(unit) || unit.strip().length() > 16) {
            throw invalid();
        }
        return unit.strip();
    }

    private ShoppingItemResponse toResponse(DinnerHouseholdShoppingItemEntity item) {
        return new ShoppingItemResponse(
                item.getIngredientId(), item.getAddedBy(), instant(item.getCreatedAt()));
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.DINNER_SHOPPING_INVALID);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.DINNER_SHOPPING_CONFLICT);
    }
}
