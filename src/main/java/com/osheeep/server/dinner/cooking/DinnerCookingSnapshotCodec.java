package com.osheeep.server.dinner.cooking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.cooking.entity.DinnerMenuCookingDishEntity;
import com.osheeep.server.dinner.record.DinnerRecordSnapshotAssembler;
import com.osheeep.server.dinner.record.DinnerRecordSnapshotJsonCodec;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerCookingSnapshotCodec {

    private static final String INVALID_SNAPSHOT = "Invalid dinner cooking snapshot";
    private static final TypeReference<List<Long>> USER_ID_LIST = new TypeReference<>() { };

    private final DinnerRecordSnapshotJsonCodec snapshotJsonCodec;
    private final ObjectMapper objectMapper;

    public DinnerCookingSnapshotCodec(
            DinnerRecordSnapshotJsonCodec snapshotJsonCodec,
            ObjectMapper objectMapper
    ) {
        this.snapshotJsonCodec = snapshotJsonCodec;
        this.objectMapper = objectMapper;
    }

    public DinnerMenuCookingDishEntity encode(
            Long menuId,
            DinnerRecordSnapshotAssembler.SnapshotDraft draft,
            String origin,
            Long addedBy,
            String addIdempotencyKey,
            int sortOrder
    ) {
        validateDraft(draft);
        if (menuId == null
                || menuId <= 0
                || sortOrder < 0
                || !("PLANNED".equals(origin) || "TEMPORARY".equals(origin))
                || ("PLANNED".equals(origin)
                        && (addedBy != null || addIdempotencyKey != null))
                || ("TEMPORARY".equals(origin)
                        && (addedBy == null
                                || addedBy <= 0
                                || !StringUtils.hasText(addIdempotencyKey)))) {
            throw invalidSnapshot();
        }

        DinnerMenuCookingDishEntity row = new DinnerMenuCookingDishEntity();
        row.setMenuId(menuId);
        row.setRecipeId(draft.recipeId());
        row.setRecipeScope(draft.scope());
        row.setRecipeVersion(draft.recipeVersion());
        row.setName(draft.name());
        row.setImagePath(draft.imagePath());
        row.setCategory(draft.category());
        row.setFlavor(draft.flavor());
        row.setEstimatedMinutes(draft.estimatedMinutes());
        row.setServings(draft.servings());
        row.setMethodId(draft.methodId());
        row.setMethodName(draft.methodName());
        row.setCookingStyle(draft.cookingStyle());
        row.setMethodEstimatedMinutes(draft.methodEstimatedMinutes());
        row.setMethodStepsJson(snapshotJsonCodec.writeSteps(draft.steps()));
        row.setIngredients(snapshotJsonCodec.writeIngredients(draft.ingredients()));
        row.setSelectedByUserIds(writeUserIds(draft.selectedByUserIds()));
        row.setOrigin(origin);
        row.setAddedBy(addedBy);
        row.setAddIdempotencyKey(addIdempotencyKey);
        row.setSortOrder(sortOrder);
        return row;
    }

    public DinnerRecordSnapshotAssembler.SnapshotDraft decode(
            DinnerMenuCookingDishEntity row
    ) {
        if (row == null) {
            throw invalidSnapshot();
        }
        DinnerRecordSnapshotAssembler.SnapshotDraft draft =
                new DinnerRecordSnapshotAssembler.SnapshotDraft(
                        row.getRecipeId(), row.getRecipeScope(), row.getRecipeVersion(),
                        row.getName(), row.getImagePath(), row.getCategory(), row.getFlavor(),
                        row.getServings(), row.getEstimatedMinutes(), selectedUserIds(row),
                        row.getMethodId(), row.getMethodName(), row.getCookingStyle(),
                        row.getMethodEstimatedMinutes(), steps(row), ingredients(row));
        validateDraft(draft);
        return draft;
    }

    public Set<Long> selectedUserIds(DinnerMenuCookingDishEntity row) {
        if (row == null || !StringUtils.hasText(row.getSelectedByUserIds())) {
            throw invalidSnapshot();
        }
        try {
            JsonNode tree = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(row.getSelectedByUserIds());
            if (tree == null || !tree.isArray() || tree.isEmpty() || tree.size() > 2) {
                throw invalidSnapshot();
            }
            for (JsonNode value : tree) {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw invalidSnapshot();
                }
            }
            List<Long> values = objectMapper.readValue(
                    row.getSelectedByUserIds(), USER_ID_LIST);
            Set<Long> result = new LinkedHashSet<>();
            for (Long value : values) {
                if (value == null || value <= 0 || !result.add(value)) {
                    throw invalidSnapshot();
                }
            }
            return Set.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_SNAPSHOT, exception);
        }
    }

    public List<RecordMethodStepSnapshotResponse> steps(
            DinnerMenuCookingDishEntity row
    ) {
        return snapshotJsonCodec.readSteps(row.getMethodStepsJson());
    }

    public List<RecordIngredientSnapshotResponse> ingredients(
            DinnerMenuCookingDishEntity row
    ) {
        return snapshotJsonCodec.readIngredients(row.getIngredients());
    }

    private String writeUserIds(Set<Long> values) {
        if (values == null || values.isEmpty() || values.size() > 2
                || values.stream().anyMatch(value -> value == null || value <= 0)) {
            throw invalidSnapshot();
        }
        try {
            return objectMapper.writeValueAsString(new TreeSet<>(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_SNAPSHOT, exception);
        }
    }

    private void validateDraft(DinnerRecordSnapshotAssembler.SnapshotDraft draft) {
        if (draft == null
                || draft.recipeId() == null
                || draft.recipeId() <= 0
                || !StringUtils.hasText(draft.name())
                || !StringUtils.hasText(draft.imagePath())
                || !StringUtils.hasText(draft.category())
                || !StringUtils.hasText(draft.flavor())
                || draft.estimatedMinutes() == null
                || draft.estimatedMinutes() <= 0
                || draft.ingredients() == null
                || draft.ingredients().isEmpty()
                || draft.ingredients().stream().noneMatch(
                        RecordIngredientSnapshotResponse::required)
                || draft.selectedByUserIds() == null
                || draft.selectedByUserIds().isEmpty()
                || draft.selectedByUserIds().size() > 2) {
            throw invalidSnapshot();
        }
        boolean system = "SYSTEM".equals(draft.scope())
                && Objects.equals(draft.recipeVersion(), 1L)
                && draft.methodId() == null
                && !StringUtils.hasText(draft.methodName())
                && !StringUtils.hasText(draft.cookingStyle())
                && draft.methodEstimatedMinutes() == null
                && draft.steps().isEmpty();
        boolean household = "HOUSEHOLD".equals(draft.scope())
                && draft.recipeVersion() != null
                && draft.recipeVersion() > 0
                && draft.servings() != null
                && draft.servings() >= 1
                && draft.servings() <= 20
                && draft.methodId() != null
                && draft.methodId() > 0
                && StringUtils.hasText(draft.methodName())
                && StringUtils.hasText(draft.cookingStyle())
                && !draft.steps().isEmpty()
                && draft.steps().size() <= 12;
        if (!system && !household) {
            throw invalidSnapshot();
        }
    }

    private IllegalStateException invalidSnapshot() {
        return new IllegalStateException(INVALID_SNAPSHOT);
    }
}
