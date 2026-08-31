package com.osheeep.server.dinner.record;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerRecordSnapshotAssembler {

    private static final int MAX_IMAGE_PATH_LENGTH = 255;
    private static final int MAX_STEPS = 12;
    private static final int MAX_INSTRUCTION_LENGTH = 160;

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetService imageAssetService;

    public DinnerRecordSnapshotAssembler(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetService imageAssetService
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageAssetService = imageAssetService;
    }

    public List<SnapshotDraft> assemble(
            Long householdId,
            List<DinnerMenuSelectionEntity> selections
    ) {
        return assemble(householdId, selections, null);
    }

    private List<SnapshotDraft> assemble(
            Long householdId,
            List<DinnerMenuSelectionEntity> selections,
            LockedSnapshotRows lockedRows
    ) {
        if (householdId == null || selections == null || selections.isEmpty()) {
            throw invalidRecipe();
        }

        Map<Long, SelectionIdentity> identitiesByRecipe = new LinkedHashMap<>();
        Map<Long, Set<Long>> selectorsByRecipe = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection == null
                    || selection.getRecipeId() == null
                    || selection.getUserId() == null) {
                throw invalidRecipe();
            }
            SelectionIdentity identity = new SelectionIdentity(
                    selection.getRecipeVersion(), selection.getMethodId());
            SelectionIdentity previous = identitiesByRecipe.putIfAbsent(
                    selection.getRecipeId(), identity);
            if (previous != null && !previous.equals(identity)) {
                throw invalidRecipe();
            }
            selectorsByRecipe.computeIfAbsent(
                            selection.getRecipeId(), ignored -> new TreeSet<>())
                    .add(selection.getUserId());
        }

        List<Long> recipeIds = identitiesByRecipe.keySet().stream().sorted().toList();
        List<Long> methodIds = identitiesByRecipe.values().stream()
                .map(SelectionIdentity::methodId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<DinnerRecipeEntity> recipeRows;
        List<DinnerRecipeIngredientRow> ingredientRows;
        List<DinnerRecipeMethodEntity> methodRows;
        List<DinnerRecipeMethodStepEntity> stepRows;
        Map<Long, DinnerRecipeEntity> recipesById;
        Map<Long, DinnerRecipeMethodEntity> methodsById;
        if (lockedRows == null) {
            recipeRows = recipeMapper.selectByIdsForUpdate(recipeIds);
            recipesById = mapRecipes(recipeRows, recipeIds);
            ingredientMapper.selectByRecipeIdsForUpdate(recipeIds);
            ingredientRows = ingredientMapper.selectWithIngredientNames(recipeIds);
            methodRows = methodIds.isEmpty()
                    ? List.of()
                    : methodMapper.selectByRecipeIdsForUpdate(recipeIds);
            methodsById = methodIds.isEmpty()
                    ? Map.of()
                    : mapMethods(methodRows, methodIds, recipeIds);
            stepRows = methodIds.isEmpty()
                    ? List.of()
                    : stepMapper.selectByMethodIdsForUpdate(methodIds);
        } else {
            recipeRows = lockedRows.recipes();
            ingredientRows = lockedRows.ingredients();
            methodRows = lockedRows.methods();
            stepRows = lockedRows.steps();
            recipesById = mapRecipes(recipeRows, recipeIds);
            methodsById = methodIds.isEmpty()
                    ? Map.of()
                    : mapMethods(methodRows, methodIds, recipeIds);
        }

        List<Long> imageAssetIds = recipesById.values().stream()
                .filter(recipe -> "HOUSEHOLD".equals(recipe.getScope()))
                .map(DinnerRecipeEntity::getImageAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, ImageAssetResponse> imagesById = imageAssetIds.isEmpty()
                ? Map.of()
                : imageAssetService.findApprovedByIds(imageAssetIds);

        Map<Long, List<RecordIngredientSnapshotResponse>> ingredientsByRecipe =
                mapIngredients(ingredientRows, recipesById);
        Map<Long, List<RecordMethodStepSnapshotResponse>> stepsByMethod =
                mapSteps(stepRows, new LinkedHashSet<>(methodIds));
        if (!imagesById.keySet().equals(new LinkedHashSet<>(imageAssetIds))) {
            throw invalidRecipe();
        }

        List<SnapshotDraft> drafts = new ArrayList<>();
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            SelectionIdentity identity = identitiesByRecipe.get(recipeId);
            List<RecordIngredientSnapshotResponse> ingredients =
                    ingredientsByRecipe.getOrDefault(recipeId, List.of());
            validateBasics(recipe);
            boolean missingRequiredIngredients = ingredients.stream().noneMatch(
                    RecordIngredientSnapshotResponse::required);
            if (("SYSTEM".equals(recipe.getScope()) && missingRequiredIngredients)
                    || ("HOUSEHOLD".equals(recipe.getScope())
                            && !ingredients.isEmpty()
                            && missingRequiredIngredients)) {
                throw invalidRecipe();
            }

            if ("SYSTEM".equals(recipe.getScope())) {
                validateSystem(recipe, identity);
                drafts.add(new SnapshotDraft(
                        recipeId, "SYSTEM", 1L, recipe.getName(), recipe.getImagePath(),
                        recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                        recipe.getEstimatedMinutes(), selectorsByRecipe.get(recipeId),
                        null, null, null, null, List.of(), ingredients));
                continue;
            }

            validateHousehold(recipe, identity, householdId);
            DinnerRecipeMethodEntity method = methodsById.get(identity.methodId());
            List<RecordMethodStepSnapshotResponse> steps =
                    stepsByMethod.getOrDefault(identity.methodId(), List.of());
            if (method == null
                    || !Objects.equals(method.getRecipeId(), recipeId)
                    || !"ACTIVE".equals(method.getStatus())
                    || !StringUtils.hasText(method.getName())
                    || !StringUtils.hasText(method.getCookingStyle())
                    || steps.isEmpty()
                    || steps.size() > MAX_STEPS) {
                throw invalidRecipe();
            }
            String imagePath = null;
            if (recipe.getImageAssetId() != null) {
                ImageAssetResponse image = imagesById.get(recipe.getImageAssetId());
                if (image == null
                        || !StringUtils.hasText(image.listUrl())
                        || image.listUrl().length() > MAX_IMAGE_PATH_LENGTH) {
                    throw invalidRecipe();
                }
                imagePath = image.listUrl();
            }
            drafts.add(new SnapshotDraft(
                    recipeId, "HOUSEHOLD", identity.recipeVersion(), recipe.getName(),
                    imagePath, recipe.getCategory(), recipe.getFlavor(),
                    recipe.getServings(), recipe.getEstimatedMinutes(),
                    selectorsByRecipe.get(recipeId), method.getId(), method.getName(),
                    method.getCookingStyle(), method.getEstimatedMinutes(), steps, ingredients));
        }
        return List.copyOf(drafts);
    }

    public SnapshotDraft assembleCurrentRecipe(
            Long householdId,
            Long userId,
            Long recipeId,
            Long requestedMethodId
    ) {
        if (householdId == null
                || userId == null
                || recipeId == null
                || recipeId <= 0
                || (requestedMethodId != null && requestedMethodId <= 0)) {
            throw invalidRecipe();
        }
        List<Long> recipeIds = List.of(recipeId);
        List<DinnerRecipeEntity> recipeRows =
                recipeMapper.selectByIdsForUpdate(recipeIds);
        DinnerRecipeEntity recipe = mapRecipes(recipeRows, recipeIds).get(recipeId);
        if (recipe == null || !"PUBLISHED".equals(recipe.getStatus())) {
            throw invalidRecipe();
        }
        boolean systemRecipe = "SYSTEM".equals(recipe.getScope())
                && recipe.getHouseholdId() == null
                && requestedMethodId == null;
        boolean householdRecipe = "HOUSEHOLD".equals(recipe.getScope())
                && Objects.equals(recipe.getHouseholdId(), householdId)
                && recipe.getVersion() != null
                && recipe.getVersion() > 0;
        if (!systemRecipe && !householdRecipe) {
            throw invalidRecipe();
        }
        ingredientMapper.selectByRecipeIdsForUpdate(recipeIds);
        List<DinnerRecipeIngredientRow> ingredientRows =
                ingredientMapper.selectWithIngredientNames(recipeIds);

        Long recipeVersion;
        Long methodId;
        List<DinnerRecipeMethodEntity> methodRows = List.of();
        List<DinnerRecipeMethodStepEntity> stepRows = List.of();
        if (systemRecipe) {
            recipeVersion = 1L;
            methodId = null;
        } else {
            methodRows = methodMapper.selectByRecipeIdsForUpdate(recipeIds);
            DinnerRecipeMethodEntity method = resolveCurrentMethod(
                    methodRows, recipeId, requestedMethodId);
            recipeVersion = recipe.getVersion();
            methodId = method.getId();
            stepRows = stepMapper.selectByMethodIdsForUpdate(List.of(methodId));
        }

        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        selection.setRecipeVersion(recipeVersion);
        selection.setMethodId(methodId);
        List<SnapshotDraft> drafts = assemble(
                householdId,
                List.of(selection),
                new LockedSnapshotRows(
                        recipeRows, ingredientRows, methodRows, stepRows));
        if (drafts.size() != 1) {
            throw invalidRecipe();
        }
        return drafts.getFirst();
    }

    private Map<Long, DinnerRecipeEntity> mapRecipes(
            List<DinnerRecipeEntity> rows,
            List<Long> expectedIds
    ) {
        Map<Long, DinnerRecipeEntity> byId = new HashMap<>();
        for (DinnerRecipeEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || byId.putIfAbsent(row.getId(), row) != null) {
                throw invalidRecipe();
            }
        }
        if (!byId.keySet().equals(new LinkedHashSet<>(expectedIds))) {
            throw invalidRecipe();
        }
        return byId;
    }

    private Map<Long, DinnerRecipeMethodEntity> mapMethods(
            List<DinnerRecipeMethodEntity> rows,
            List<Long> expectedIds,
            List<Long> expectedRecipeIds
    ) {
        Set<Long> recipeIds = new HashSet<>(expectedRecipeIds);
        Map<Long, DinnerRecipeMethodEntity> allById = new HashMap<>();
        for (DinnerRecipeMethodEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || row.getRecipeId() == null
                    || !recipeIds.contains(row.getRecipeId())
                    || allById.putIfAbsent(row.getId(), row) != null) {
                throw invalidRecipe();
            }
        }
        Map<Long, DinnerRecipeMethodEntity> selectedById = new HashMap<>();
        for (Long expectedId : expectedIds) {
            DinnerRecipeMethodEntity selected = allById.get(expectedId);
            if (selected == null) {
                throw invalidRecipe();
            }
            selectedById.put(expectedId, selected);
        }
        return selectedById;
    }

    private DinnerRecipeMethodEntity resolveCurrentMethod(
            List<DinnerRecipeMethodEntity> rows,
            Long recipeId,
            Long requestedMethodId
    ) {
        Map<Long, DinnerRecipeMethodEntity> methodsById =
                mapMethods(rows, rows.stream()
                        .filter(Objects::nonNull)
                        .map(DinnerRecipeMethodEntity::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(), List.of(recipeId));
        List<DinnerRecipeMethodEntity> candidates = methodsById.values().stream()
                .filter(method -> "ACTIVE".equals(method.getStatus()))
                .filter(method -> requestedMethodId == null
                        ? Boolean.TRUE.equals(method.getIsDefault())
                        : Objects.equals(method.getId(), requestedMethodId))
                .toList();
        if (candidates.size() != 1) {
            throw invalidRecipe();
        }
        return candidates.getFirst();
    }

    private Map<Long, List<RecordIngredientSnapshotResponse>> mapIngredients(
            List<DinnerRecipeIngredientRow> rows,
            Map<Long, DinnerRecipeEntity> recipesById
    ) {
        Map<Long, Set<Long>> ingredientIdsByRecipe = new HashMap<>();
        Map<Long, List<RecordIngredientSnapshotResponse>> byRecipe = new HashMap<>();
        for (DinnerRecipeIngredientRow row : rows) {
            DinnerRecipeEntity recipe = row == null || row.recipeId() == null
                    ? null : recipesById.get(row.recipeId());
            if (row == null
                    || row.recipeId() == null
                    || recipe == null
                    || row.ingredientId() == null
                    || row.ingredientId() <= 0
                    || !ingredientVisibleToRecipe(recipe, row)
                    || !StringUtils.hasText(row.name())
                    || !StringUtils.hasText(row.unit())
                    || !validQuantity(row.quantity())
                    || row.sortOrder() < 0
                    || !ingredientIdsByRecipe
                            .computeIfAbsent(row.recipeId(), ignored -> new HashSet<>())
                            .add(row.ingredientId())) {
                throw invalidRecipe();
            }
            byRecipe.computeIfAbsent(row.recipeId(), ignored -> new ArrayList<>())
                    .add(new RecordIngredientSnapshotResponse(
                            row.ingredientId(), row.name(), row.quantity(), row.unit(),
                            row.required(), row.sortOrder()));
        }
        byRecipe.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparingInt(
                                RecordIngredientSnapshotResponse::sortOrder)
                        .thenComparing(RecordIngredientSnapshotResponse::ingredientId))
                .toList());
        return byRecipe;
    }

    private boolean ingredientVisibleToRecipe(
            DinnerRecipeEntity recipe,
            DinnerRecipeIngredientRow row
    ) {
        if (!"ACTIVE".equals(row.ingredientStatus())) {
            return false;
        }
        boolean systemIngredient = "SYSTEM".equals(row.ingredientScope())
                && row.ingredientHouseholdId() == null;
        if ("SYSTEM".equals(recipe.getScope())) {
            return systemIngredient;
        }
        boolean sameHouseholdIngredient = "HOUSEHOLD".equals(row.ingredientScope())
                && recipe.getHouseholdId() != null
                && Objects.equals(
                        recipe.getHouseholdId(), row.ingredientHouseholdId());
        return "HOUSEHOLD".equals(recipe.getScope())
                && (systemIngredient || sameHouseholdIngredient);
    }

    private Map<Long, List<RecordMethodStepSnapshotResponse>> mapSteps(
            List<DinnerRecipeMethodStepEntity> rows,
            Set<Long> expectedMethodIds
    ) {
        Set<Long> stepIds = new HashSet<>();
        Map<Long, List<DinnerRecipeMethodStepEntity>> rawByMethod = new HashMap<>();
        for (DinnerRecipeMethodStepEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || !stepIds.add(row.getId())
                    || row.getMethodId() == null
                    || !expectedMethodIds.contains(row.getMethodId())
                    || !StringUtils.hasText(row.getInstruction())
                    || row.getInstruction().length() > MAX_INSTRUCTION_LENGTH
                    || row.getSortOrder() == null
                    || row.getSortOrder() < 0) {
                throw invalidRecipe();
            }
            rawByMethod.computeIfAbsent(row.getMethodId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        Map<Long, List<RecordMethodStepSnapshotResponse>> byMethod = new HashMap<>();
        rawByMethod.forEach((methodId, values) -> byMethod.put(
                methodId,
                values.stream()
                        .sorted(Comparator.comparingInt(
                                        DinnerRecipeMethodStepEntity::getSortOrder)
                                .thenComparing(DinnerRecipeMethodStepEntity::getId))
                        .map(step -> new RecordMethodStepSnapshotResponse(
                                step.getInstruction(), step.getSortOrder()))
                        .toList()));
        return byMethod;
    }

    private void validateBasics(DinnerRecipeEntity recipe) {
        if (!("PUBLISHED".equals(recipe.getStatus())
                || "ARCHIVED".equals(recipe.getStatus()))
                || !StringUtils.hasText(recipe.getName())
                || !StringUtils.hasText(recipe.getCategory())
                || !StringUtils.hasText(recipe.getFlavor())
                || recipe.getEstimatedMinutes() == null) {
            throw invalidRecipe();
        }
    }

    private void validateSystem(
            DinnerRecipeEntity recipe,
            SelectionIdentity identity
    ) {
        if (!"SYSTEM".equals(recipe.getScope())
                || !"PUBLISHED".equals(recipe.getStatus())
                || !Objects.equals(identity.recipeVersion(), 1L)
                || identity.methodId() != null
                || !StringUtils.hasText(recipe.getImagePath())
                || recipe.getImagePath().length() > MAX_IMAGE_PATH_LENGTH) {
            throw invalidRecipe();
        }
    }

    private void validateHousehold(
            DinnerRecipeEntity recipe,
            SelectionIdentity identity,
            Long householdId
    ) {
        if (!"HOUSEHOLD".equals(recipe.getScope())
                || !Objects.equals(recipe.getHouseholdId(), householdId)
                || identity.recipeVersion() == null
                || identity.recipeVersion() <= 0
                || !matchesHouseholdSelectionVersion(recipe, identity.recipeVersion())
                || identity.methodId() == null
                || recipe.getServings() == null
                || recipe.getServings() < 1
                || recipe.getServings() > 20) {
            throw invalidRecipe();
        }
    }

    private boolean matchesHouseholdSelectionVersion(
            DinnerRecipeEntity recipe,
            Long selectedVersion
    ) {
        if ("PUBLISHED".equals(recipe.getStatus())) {
            return Objects.equals(recipe.getVersion(), selectedVersion);
        }
        return "ARCHIVED".equals(recipe.getStatus())
                && recipe.getVersion() != null
                && Objects.equals(recipe.getVersion() - 1L, selectedVersion);
    }

    private boolean validQuantity(BigDecimal quantity) {
        return quantity == null
                || (quantity.signum() >= 0
                && quantity.scale() <= 3
                && Math.max(quantity.precision() - quantity.scale(), 0) <= 9);
    }

    private BusinessException invalidRecipe() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }

    public record SnapshotDraft(
            Long recipeId,
            String scope,
            Long recipeVersion,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer servings,
            Integer estimatedMinutes,
            Set<Long> selectedByUserIds,
            Long methodId,
            String methodName,
            String cookingStyle,
            Integer methodEstimatedMinutes,
            List<RecordMethodStepSnapshotResponse> steps,
            List<RecordIngredientSnapshotResponse> ingredients
    ) {
        public SnapshotDraft {
            selectedByUserIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(new TreeSet<>(selectedByUserIds)));
            steps = List.copyOf(steps);
            ingredients = List.copyOf(ingredients);
        }

        public SnapshotDraft(
                Long recipeId,
                String scope,
                Long recipeVersion,
                String name,
                String imagePath,
                String category,
                String flavor,
                Integer servings,
                Integer estimatedMinutes,
                Set<Long> selectedByUserIds,
                Long methodId,
                String methodName,
                String cookingStyle,
                List<RecordMethodStepSnapshotResponse> steps,
                List<RecordIngredientSnapshotResponse> ingredients
        ) {
            this(recipeId, scope, recipeVersion, name, imagePath, category, flavor,
                    servings, estimatedMinutes, selectedByUserIds, methodId, methodName,
                    cookingStyle, null, steps, ingredients);
        }
    }

    private record SelectionIdentity(Long recipeVersion, Long methodId) {
    }

    private record LockedSnapshotRows(
            List<DinnerRecipeEntity> recipes,
            List<DinnerRecipeIngredientRow> ingredients,
            List<DinnerRecipeMethodEntity> methods,
            List<DinnerRecipeMethodStepEntity> steps
    ) {
    }
}
