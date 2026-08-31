package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.household.DinnerHouseholdActorLabelService;
import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DinnerRecipeQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetService imageAssetService;
    private final DinnerHouseholdActorLabelService actorLabelService;
    private final DinnerRecipeAuthorizer authorizer;

    public DinnerRecipeQueryService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetService imageAssetService,
            DinnerHouseholdActorLabelService actorLabelService,
            DinnerRecipeAuthorizer authorizer
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageAssetService = imageAssetService;
        this.actorLabelService = actorLabelService;
        this.authorizer = authorizer;
    }

    public List<FamilyRecipeListItemResponse> list(Long userId, FamilyRecipeTab tab) {
        RecipeAccess access = authorizer.requireMembership(userId);
        var query = Wrappers.<DinnerRecipeEntity>lambdaQuery();
        if (tab == FamilyRecipeTab.DRAFT) {
            query.eq(DinnerRecipeEntity::getCreatorId, userId)
                    .eq(DinnerRecipeEntity::getStatus, "DRAFT");
        } else {
            query.eq(DinnerRecipeEntity::getHouseholdId, access.householdId())
                    .eq(DinnerRecipeEntity::getScope, "HOUSEHOLD")
                    .eq(DinnerRecipeEntity::getStatus, tab.name());
        }
        query.orderByDesc(DinnerRecipeEntity::getUpdatedAt)
                .orderByDesc(DinnerRecipeEntity::getId);
        List<DinnerRecipeEntity> recipes = recipeMapper.selectList(query);
        if (recipes.isEmpty()) {
            return List.of();
        }

        AggregateData aggregate = loadAggregate(recipes);
        Set<Long> actorUserIds = new LinkedHashSet<>();
        recipes.forEach(recipe -> {
            actorUserIds.add(recipe.getCreatorId());
            actorUserIds.add(recipe.getLastModifiedBy());
        });
        Map<Long, HouseholdActorResponse> actors = actorLabelService.resolve(
                access.householdId(), userId, actorUserIds);
        return recipes.stream()
                .map(recipe -> listResponse(recipe, aggregate, actors))
                .toList();
    }

    public RecipeDraftResponse detail(Long userId, Long recipeId) {
        return detail(authorizer.requireMembership(userId), recipeId);
    }

    RecipeDraftResponse detail(RecipeAccess access, Long recipeId) {
        DinnerRecipeEntity recipe = authorizer.requireVisible(access, recipeId);
        AggregateData aggregate = loadAggregate(List.of(recipe));
        return detailResponse(recipe, aggregate);
    }

    private AggregateData loadAggregate(List<DinnerRecipeEntity> recipes) {
        List<Long> recipeIds = recipes.stream().map(DinnerRecipeEntity::getId).toList();
        Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe =
                loadIngredients(recipeIds);
        Map<Long, List<RecipeMethodDraftResponse>> methodsByRecipe = loadMethods(recipeIds);
        Map<Long, ImageAssetResponse> imagesById = loadImages(recipes);
        return new AggregateData(ingredientsByRecipe, methodsByRecipe, imagesById);
    }

    private Map<Long, List<RecipeIngredientResponse>> loadIngredients(List<Long> recipeIds) {
        return ingredientMapper.selectWithIngredientNames(recipeIds).stream()
                .sorted(Comparator.comparing(DinnerRecipeIngredientRow::recipeId)
                        .thenComparingInt(DinnerRecipeIngredientRow::sortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeIngredientRow::recipeId,
                        Collectors.mapping(row -> new RecipeIngredientResponse(
                                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                                        row.required(), row.sortOrder()),
                                Collectors.toList())));
    }

    private Map<Long, List<RecipeMethodDraftResponse>> loadMethods(List<Long> recipeIds) {
        List<DinnerRecipeMethodEntity> methods = methodMapper.selectList(
                Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                        .in(DinnerRecipeMethodEntity::getRecipeId, recipeIds)
                        .eq(DinnerRecipeMethodEntity::getStatus, "ACTIVE")
                        .orderByAsc(DinnerRecipeMethodEntity::getRecipeId)
                        .orderByAsc(DinnerRecipeMethodEntity::getSortOrder)
                        .orderByAsc(DinnerRecipeMethodEntity::getId));
        if (methods.isEmpty()) {
            return Map.of();
        }
        List<Long> methodIds = methods.stream().map(DinnerRecipeMethodEntity::getId).toList();
        Map<Long, List<RecipeMethodStepResponse>> stepsByMethod = stepMapper.selectList(
                        Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                                .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getMethodId)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getSortOrder)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getId))
                .stream()
                .sorted(Comparator.comparing(DinnerRecipeMethodStepEntity::getMethodId)
                        .thenComparingInt(DinnerRecipeMethodStepEntity::getSortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeMethodStepEntity::getMethodId,
                        Collectors.mapping(step -> new RecipeMethodStepResponse(
                                        step.getInstruction(), step.getSortOrder()),
                                Collectors.toList())));
        return methods.stream().collect(Collectors.groupingBy(
                DinnerRecipeMethodEntity::getRecipeId,
                Collectors.mapping(method -> new RecipeMethodDraftResponse(
                                method.getId(), method.getName(), method.getCookingStyle(),
                                method.getEstimatedMinutes(),
                                Boolean.TRUE.equals(method.getIsDefault()),
                                method.getSortOrder(),
                                stepsByMethod.getOrDefault(method.getId(), List.of())),
                        Collectors.toList())));
    }

    private Map<Long, ImageAssetResponse> loadImages(List<DinnerRecipeEntity> recipes) {
        List<Long> imageIds = recipes.stream()
                .map(DinnerRecipeEntity::getImageAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        return imageAssetService.findApprovedByIds(imageIds);
    }

    private FamilyRecipeListItemResponse listResponse(
            DinnerRecipeEntity recipe,
            AggregateData aggregate,
            Map<Long, HouseholdActorResponse> actors
    ) {
        List<String> incomplete = incompleteSteps(recipe, aggregate);
        ImageAssetResponse image = selectedImage(recipe, aggregate);
        return new FamilyRecipeListItemResponse(
                recipe.getId(), recipe.getStatus(), recipe.getName(),
                image == null ? recipe.getImagePath() : image.listUrl(),
                recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                recipe.getEstimatedMinutes(), recipe.getVersion(),
                requireActor(recipe.getCreatorId(), actors),
                requireActor(recipe.getLastModifiedBy(), actors),
                incomplete.isEmpty() ? "PREVIEW" : incomplete.getFirst(),
                toInstant(recipe.getUpdatedAt()), recipe.getRevisionOfRecipeId());
    }

    private RecipeDraftResponse detailResponse(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        List<RecipeMethodDraftResponse> methods =
                aggregate.methodsByRecipe().getOrDefault(recipe.getId(), List.of());
        return new RecipeDraftResponse(
                recipe.getId(), recipe.getStatus(), recipe.getVersion(), recipe.getName(),
                recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                recipe.getEstimatedMinutes(),
                aggregate.ingredientsByRecipe().getOrDefault(recipe.getId(), List.of()),
                defaultMethod(methods),
                methods,
                selectedImage(recipe, aggregate),
                incompleteSteps(recipe, aggregate), toInstant(recipe.getUpdatedAt()),
                recipe.getRevisionOfRecipeId(), recipe.getBasePublishedVersion());
    }

    private List<String> incompleteSteps(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        List<String> incomplete = new ArrayList<>(4);
        if (!basicComplete(recipe)) {
            incomplete.add("BASIC");
        }
        if (!methodComplete(defaultMethod(
                aggregate.methodsByRecipe().getOrDefault(recipe.getId(), List.of())))) {
            incomplete.add("METHOD");
        }
        return List.copyOf(incomplete);
    }

    private boolean basicComplete(DinnerRecipeEntity recipe) {
        return StringUtils.hasText(recipe.getName())
                && StringUtils.hasText(recipe.getCategory())
                && StringUtils.hasText(recipe.getFlavor())
                && recipe.getServings() != null
                && recipe.getEstimatedMinutes() != null;
    }

    private ImageAssetResponse selectedImage(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        return recipe.getImageAssetId() == null
                ? null
                : aggregate.imagesById().get(recipe.getImageAssetId());
    }

    private boolean methodComplete(RecipeMethodResponse method) {
        return method != null
                && !method.steps().isEmpty()
                && method.steps().stream()
                        .allMatch(step -> StringUtils.hasText(step.instruction()));
    }

    private RecipeMethodResponse defaultMethod(List<RecipeMethodDraftResponse> methods) {
        return methods.stream()
                .filter(RecipeMethodDraftResponse::defaultMethod)
                .findFirst()
                .map(method -> new RecipeMethodResponse(
                        method.id(), method.name(), method.cookingStyle(), method.steps()))
                .orElse(null);
    }

    private HouseholdActorResponse requireActor(
            Long userId,
            Map<Long, HouseholdActorResponse> actors
    ) {
        HouseholdActorResponse actor = userId == null ? null : actors.get(userId);
        if (actor == null) {
            throw new IllegalStateException("Unresolved household actor");
        }
        return actor;
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(SHANGHAI).toInstant();
    }

    private record AggregateData(
            Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe,
            Map<Long, List<RecipeMethodDraftResponse>> methodsByRecipe,
            Map<Long, ImageAssetResponse> imagesById
    ) {
    }
}
