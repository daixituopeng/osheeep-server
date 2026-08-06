package com.osheeep.server.dinner.shopping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingItemRequest;
import com.osheeep.server.dinner.shopping.dto.CompleteShoppingResponse;
import com.osheeep.server.dinner.shopping.entity.DinnerHouseholdShoppingItemEntity;
import com.osheeep.server.dinner.shopping.mapper.DinnerHouseholdShoppingItemMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerShoppingServiceTest {

    @Mock private DinnerHouseholdShoppingItemMapper shoppingItemMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerHouseholdAccessService accessService;

    private DinnerShoppingService service;

    @BeforeEach
    void setUp() {
        service = new DinnerShoppingService(
                shoppingItemMapper, inventoryMapper, ingredientMapper, accessService);
    }

    @Test
    void listItemsIsScopedToTheCurrentHousehold() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access(7L, 11L));
        when(shoppingItemMapper.selectByHouseholdId(11L)).thenReturn(List.of(
                shoppingItem(31L, 11L, 3L, 7L,
                        LocalDateTime.of(2026, 8, 4, 12, 30))));

        assertThat(service.listItems(7L)).singleElement().satisfies(item -> {
            assertThat(item.ingredientId()).isEqualTo(3L);
            assertThat(item.addedBy()).isEqualTo(7L);
            assertThat(item.createdAt()).isEqualTo(Instant.parse("2026-08-04T04:30:00Z"));
        });
        verify(shoppingItemMapper).selectByHouseholdId(11L);
    }

    @Test
    void addingAnExistingItemIsIdempotentAndLocksHouseholdFirst() {
        DinnerHouseholdShoppingItemEntity existing = shoppingItem(
                31L, 11L, 3L, 8L, LocalDateTime.of(2026, 8, 4, 12, 30));
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚"));
        when(shoppingItemMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(existing);

        assertThat(service.addItem(7L, 3L).ingredientId()).isEqualTo(3L);

        InOrder order = inOrder(accessService, ingredientMapper, shoppingItemMapper);
        order.verify(accessService).lockActiveHouseholdContext(7L);
        order.verify(ingredientMapper).selectById(3L);
        order.verify(shoppingItemMapper)
                .selectByHouseholdAndIngredientForUpdate(11L, 3L);
        verify(shoppingItemMapper, never()).insert(
                any(DinnerHouseholdShoppingItemEntity.class));
    }

    @Test
    void removeIsIdempotentAndHouseholdScoped() {
        stubLockedAccess(7L, 11L);

        service.removeItem(7L, 3L);

        verify(shoppingItemMapper).delete(any());
    }

    @Test
    void completeAddsKnownQuantityCreatesNewInventoryAndClearsCompletedItems() {
        stubLockedAccess(7L, 11L);
        DinnerHouseholdShoppingItemEntity eggShopping = shoppingItem(
                31L, 11L, 3L, 7L, LocalDateTime.of(2026, 8, 4, 12, 30));
        DinnerHouseholdShoppingItemEntity tomatoShopping = shoppingItem(
                32L, 11L, 4L, 7L, LocalDateTime.of(2026, 8, 4, 12, 31));
        when(shoppingItemMapper.selectAllByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(eggShopping, tomatoShopping));
        DinnerIngredientEntity egg = ingredient(
                3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚");
        DinnerIngredientEntity tomato = ingredient(
                4L, "SYSTEM", null, "番茄", "蔬菜", "个");
        when(ingredientMapper.selectByIds(List.of(3L, 4L)))
                .thenReturn(List.of(egg, tomato));
        DinnerHouseholdInventoryEntity eggInventory = inventory(
                21L, 11L, 3L, "6.000", "枚", 2L, 8L);
        when(inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                11L, List.of(3L, 4L))).thenReturn(List.of(eggInventory));
        when(inventoryMapper.updateById(eggInventory)).thenReturn(1);
        AtomicReference<DinnerHouseholdInventoryEntity> created = new AtomicReference<>();
        when(inventoryMapper.insert(any(DinnerHouseholdInventoryEntity.class)))
                .thenAnswer(invocation -> {
                    DinnerHouseholdInventoryEntity item = invocation.getArgument(0);
                    item.setId(22L);
                    item.setUpdatedAt(LocalDateTime.of(2026, 8, 4, 12, 35));
                    created.set(item);
                    return 1;
                });
        when(shoppingItemMapper.deleteBatchIds(List.of(31L, 32L))).thenReturn(2);
        when(inventoryMapper.selectList(any())).thenAnswer(invocation ->
                List.of(eggInventory, created.get()));
        when(shoppingItemMapper.selectByHouseholdId(11L)).thenReturn(List.of());

        CompleteShoppingResponse response = service.complete(7L, List.of(
                new CompleteShoppingItemRequest(3L, new BigDecimal("2.000"), "枚"),
                new CompleteShoppingItemRequest(4L, new BigDecimal("3.000"), "个")));

        assertThat(response.inventory()).hasSize(2);
        assertThat(response.inventory().get(0).quantity()).isEqualByComparingTo("8.000");
        assertThat(response.inventory().get(0).version()).isEqualTo(3L);
        assertThat(response.inventory().get(1).quantity()).isEqualByComparingTo("3.000");
        assertThat(response.remainingItems()).isEmpty();
        verify(shoppingItemMapper).deleteBatchIds(List.of(31L, 32L));
    }

    @Test
    void completeRejectsAUnitThatDoesNotMatchExistingInventoryBeforeMutation() {
        stubLockedAccess(7L, 11L);
        when(shoppingItemMapper.selectAllByHouseholdIdForUpdate(11L)).thenReturn(List.of(
                shoppingItem(31L, 11L, 3L, 7L,
                        LocalDateTime.of(2026, 8, 4, 12, 30))));
        when(ingredientMapper.selectByIds(List.of(3L))).thenReturn(List.of(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚")));
        DinnerHouseholdInventoryEntity inventory = inventory(
                21L, 11L, 3L, "6.000", "枚", 2L, 8L);
        when(inventoryMapper.selectByHouseholdAndIngredientIdsForUpdate(
                11L, List.of(3L))).thenReturn(List.of(inventory));

        assertThatThrownBy(() -> service.complete(7L, List.of(
                new CompleteShoppingItemRequest(3L, BigDecimal.ONE, "盒"))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_SHOPPING_INVALID));

        assertThat(inventory.getQuantity()).isEqualByComparingTo("6.000");
        assertThat(inventory.getVersion()).isEqualTo(2L);
        verify(inventoryMapper, never()).updateById(
                any(DinnerHouseholdInventoryEntity.class));
        verify(shoppingItemMapper, never()).deleteBatchIds(any());
    }

    private void stubLockedAccess(Long userId, Long householdId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(householdId);
        when(accessService.lockActiveHouseholdContext(userId)).thenReturn(
                new LockedHouseholdContext(
                        user, household, List.of(), access(userId, householdId)));
    }

    private ActiveHouseholdAccess access(Long userId, Long householdId) {
        return new ActiveHouseholdAccess(
                userId, householdId, 13L, 1L, "OWNER",
                LocalDateTime.of(2026, 1, 1, 0, 0), 1L, "Asia/Shanghai");
    }

    private DinnerHouseholdShoppingItemEntity shoppingItem(
            Long id,
            Long householdId,
            Long ingredientId,
            Long addedBy,
            LocalDateTime createdAt
    ) {
        DinnerHouseholdShoppingItemEntity item = new DinnerHouseholdShoppingItemEntity();
        item.setId(id);
        item.setHouseholdId(householdId);
        item.setIngredientId(ingredientId);
        item.setAddedBy(addedBy);
        item.setCreatedAt(createdAt);
        return item;
    }

    private DinnerIngredientEntity ingredient(
            Long id,
            String scope,
            Long householdId,
            String name,
            String category,
            String unit
    ) {
        DinnerIngredientEntity item = new DinnerIngredientEntity();
        item.setId(id);
        item.setScope(scope);
        item.setHouseholdId(householdId);
        item.setName(name);
        item.setCategory(category);
        item.setDefaultUnit(unit);
        item.setStatus("ACTIVE");
        return item;
    }

    private DinnerHouseholdInventoryEntity inventory(
            Long id,
            Long householdId,
            Long ingredientId,
            String quantity,
            String unit,
            Long version,
            Long updatedBy
    ) {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setId(id);
        item.setHouseholdId(householdId);
        item.setIngredientId(ingredientId);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        item.setVersion(version);
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(LocalDateTime.of(2026, 8, 4, 12, 35));
        return item;
    }
}
