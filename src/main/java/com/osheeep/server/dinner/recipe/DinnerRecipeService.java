package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.DinnerRecipeCatalogAssembler.CatalogEntry;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Requirement;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Stock;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeDetailResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMatchResponse;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipePreferenceEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipePreferenceMapper;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DinnerRecipeService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeCatalogAssembler catalogAssembler;
    private final RecipeMatchCalculator matchCalculator;
    private final DinnerRecipePreferenceMapper preferenceMapper;
    private final DinnerRecipePreferenceAggregator preferenceAggregator;

    @Autowired
    public DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeCatalogAssembler catalogAssembler,
            DinnerRecipePreferenceMapper preferenceMapper,
            DinnerRecipePreferenceAggregator preferenceAggregator
    ) {
        this(recipeMapper, inventoryMapper, authorizer, catalogAssembler,
                new RecipeMatchCalculator(), preferenceMapper, preferenceAggregator);
    }

    DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeCatalogAssembler catalogAssembler,
            RecipeMatchCalculator matchCalculator
    ) {
        this(recipeMapper, inventoryMapper, authorizer, catalogAssembler,
                matchCalculator, null, new DinnerRecipePreferenceAggregator());
    }

    DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeCatalogAssembler catalogAssembler,
            RecipeMatchCalculator matchCalculator,
            DinnerRecipePreferenceMapper preferenceMapper,
            DinnerRecipePreferenceAggregator preferenceAggregator
    ) {
        this.recipeMapper = recipeMapper;
        this.inventoryMapper = inventoryMapper;
        this.authorizer = authorizer;
        this.catalogAssembler = catalogAssembler;
        this.matchCalculator = matchCalculator;
        this.preferenceMapper = preferenceMapper;
        this.preferenceAggregator = preferenceAggregator;
    }

    public List<RecipeResponse> discover(
            Long userId,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds,
            boolean onlyCookable
    ) {
        RecipeAccess access = authorizer.requireMembership(userId);
        List<DinnerRecipeEntity> recipes = recipeMapper.selectList(
                Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getStatus, "PUBLISHED")
                        .and(visible -> visible
                                .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                                .or(household -> household
                                        .eq(DinnerRecipeEntity::getScope, "HOUSEHOLD")
                                        .eq(DinnerRecipeEntity::getHouseholdId,
                                                access.householdId())))
                        .orderByAsc(DinnerRecipeEntity::getId));
        Map<Long, CatalogEntry> catalog = catalogAssembler.assemble(recipes);
        List<Long> recipeIds = recipes.stream().map(DinnerRecipeEntity::getId).toList();
        List<DinnerRecipePreferenceEntity> preferenceRows =
                preferenceMapper == null || recipeIds.isEmpty()
                        ? List.of()
                        : preferenceMapper.selectActiveByHouseholdAndRecipeIds(
                                access.householdId(), recipeIds);
        Map<Long, RecipePreferenceResponse> preferences =
                preferenceAggregator.aggregate(recipeIds, access.userId(), preferenceRows);
        List<DinnerHouseholdInventoryEntity> inventory = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId,
                                access.householdId()));

        Map<Long, Stock> householdStock = inventory.stream()
                .collect(Collectors.toMap(
                        DinnerHouseholdInventoryEntity::getIngredientId,
                        item -> new Stock(item.getQuantity(), item.getUnit())));
        Comparator<RecipeResponse> order = hasInventoryDiscoveryIntent(
                includeIngredientIds, excludeIngredientIds, onlyCookable)
                ? inventoryDiscoveryOrder()
                : defaultDiscoveryOrder();

        return recipes.stream()
                .map(recipe -> catalog.get(recipe.getId()))
                .filter(java.util.Objects::nonNull)
                .map(entry -> response(
                        entry,
                        householdStock,
                        includeIngredientIds,
                        excludeIngredientIds,
                        preferences.get(entry.recipe().getId())))
                .filter(response -> !onlyCookable || !"MISSING".equals(response.match().status()))
                .sorted(order)
                .toList();
    }

    public RecipeDetailResponse detail(Long userId, Long recipeId) {
        RecipeAccess access = authorizer.requireMembership(userId);
        DinnerRecipeEntity recipe = recipeMapper.selectOne(
                Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getId, recipeId)
                        .eq(DinnerRecipeEntity::getStatus, "PUBLISHED")
                        .and(visible -> visible
                                .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                                .or(household -> household
                                        .eq(DinnerRecipeEntity::getScope, "HOUSEHOLD")
                                        .eq(DinnerRecipeEntity::getHouseholdId,
                                                access.householdId())))
                        .last("LIMIT 1"));
        if (recipe == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        CatalogEntry entry = catalogAssembler.assemble(List.of(recipe)).get(recipeId);
        if (entry == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
        }
        List<DinnerHouseholdInventoryEntity> inventory = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId,
                                access.householdId()));
        Map<Long, Stock> householdStock = inventory.stream()
                .collect(Collectors.toMap(
                        DinnerHouseholdInventoryEntity::getIngredientId,
                        item -> new Stock(item.getQuantity(), item.getUnit())));
        List<RecipeIngredientResponse> ingredients = orderedIngredients(entry);
        return new RecipeDetailResponse(
                recipe.getId(), recipe.getName(), entry.imagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getServings(), recipe.getEstimatedMinutes(),
                recipe.getScope(), "SYSTEM".equals(recipe.getScope()) ? 1L : recipe.getVersion(),
                ingredients, match(ingredients, householdStock, Set.of(), Set.of()),
                entry.methods());
    }

    public List<RecipeResponse> listSystemRecipes() {
        return recipeMapper.selectList(Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                        .eq(DinnerRecipeEntity::getStatus, "PUBLISHED")
                        .orderByAsc(DinnerRecipeEntity::getId))
                .stream()
                .map(RecipeResponse::from)
                .toList();
    }

    private RecipeResponse response(
            CatalogEntry entry,
            Map<Long, Stock> householdStock,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds,
            RecipePreferenceResponse preference
    ) {
        DinnerRecipeEntity recipe = entry.recipe();
        List<RecipeIngredientResponse> ingredients = orderedIngredients(entry);
        RecipeMatchResponse match = match(
                ingredients, householdStock, includeIngredientIds, excludeIngredientIds);

        return new RecipeResponse(
                recipe.getId(), recipe.getName(), entry.imagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes(), recipe.getScope(),
                "SYSTEM".equals(recipe.getScope()) ? 1L : recipe.getVersion(),
                entry.defaultMethod(), ingredients, match, preference);
    }

    private List<RecipeIngredientResponse> orderedIngredients(CatalogEntry entry) {
        return entry.ingredients().stream()
                .sorted(Comparator.comparingInt(RecipeIngredientResponse::sortOrder))
                .toList();
    }

    private RecipeMatchResponse match(
            List<RecipeIngredientResponse> ingredients,
            Map<Long, Stock> householdStock,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds
    ) {
        List<Requirement> requirements = ingredients.stream()
                .map(ingredient -> new Requirement(
                        ingredient.ingredientId(), ingredient.name(), ingredient.quantity(),
                        ingredient.unit(), ingredient.required(), ingredient.sortOrder()))
                .toList();
        Map<Long, Stock> effectiveStock = new HashMap<>(householdStock);
        for (Requirement requirement : requirements) {
            if (includeIngredientIds.contains(requirement.ingredientId())) {
                effectiveStock.putIfAbsent(
                        requirement.ingredientId(), new Stock(null, requirement.unit()));
            }
        }
        excludeIngredientIds.forEach(effectiveStock::remove);
        return matchCalculator.calculate(requirements, effectiveStock);
    }

    private Comparator<RecipeResponse> defaultDiscoveryOrder() {
        return Comparator.comparingInt(DinnerRecipeService::dishKindRank)
                .thenComparingInt(response ->
                        "HOUSEHOLD".equals(response.scope()) ? 0 : 1)
                .thenComparing(
                        RecipeResponse::name,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecipeResponse::id);
    }

    private Comparator<RecipeResponse> inventoryDiscoveryOrder() {
        return Comparator.comparingInt((RecipeResponse response) ->
                        statusRank(response.match().status()))
                .thenComparing(
                        response -> response.match().matchPercent(), Comparator.reverseOrder())
                .thenComparingInt(response -> preferenceAggregator.rank(
                        response.preference().householdPreference()))
                .thenComparing(
                        RecipeResponse::estimatedMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecipeResponse::id);
    }

    private static boolean hasInventoryDiscoveryIntent(
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds,
            boolean onlyCookable
    ) {
        return onlyCookable
                || !includeIngredientIds.isEmpty()
                || !excludeIngredientIds.isEmpty();
    }

    private static int dishKindRank(RecipeResponse response) {
        if ("荤菜".equals(response.category())) {
            return 0;
        }
        if ("素菜".equals(response.category())) {
            return 1;
        }
        String text = (response.category() == null ? "" : response.category())
                + (response.flavor() == null ? "" : response.flavor());
        if (text.contains("素") && !text.contains("荤")) {
            return 1;
        }
        if (text.contains("荤") || text.contains("肉")) {
            return 0;
        }
        return 2;
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "AVAILABLE" -> 0;
            case "UNKNOWN_QUANTITY" -> 1;
            default -> 2;
        };
    }

}
