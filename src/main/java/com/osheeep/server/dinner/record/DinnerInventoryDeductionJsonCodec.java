package com.osheeep.server.dinner.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.InventoryDeductionAppliedItemResponse;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerInventoryDeductionJsonCodec {

    private static final String INVALID_JSON =
            "Invalid dinner inventory deduction JSON";
    private static final TypeReference<List<InventoryDeductionAppliedItemResponse>> ITEM_LIST =
            new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public DinnerInventoryDeductionJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(List<InventoryDeductionAppliedItemResponse> items) {
        List<InventoryDeductionAppliedItemResponse> values =
                items == null ? List.of() : List.copyOf(items);
        validate(values);
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    public List<InventoryDeductionAppliedItemResponse> read(String json) {
        if (!StringUtils.hasText(json)) {
            throw invalidJson();
        }
        try {
            JsonNode tree = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            validateJson(tree);
            List<InventoryDeductionAppliedItemResponse> values =
                    objectMapper.readValue(json, ITEM_LIST);
            if (values == null) {
                throw invalidJson();
            }
            validate(values);
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(INVALID_JSON, exception);
        }
    }

    private void validateJson(JsonNode tree) {
        if (tree == null || !tree.isArray()) {
            throw invalidJson();
        }
        for (JsonNode value : tree) {
            if (value == null
                    || !value.isObject()
                    || value.size() != 7
                    || !integral(value, "ingredientId")
                    || !textual(value, "name")
                    || !textual(value, "unit")
                    || !number(value, "deductedQuantity")
                    || !number(value, "quantityBefore")
                    || !number(value, "quantityAfter")
                    || !integral(value, "resultingVersion")) {
                throw invalidJson();
            }
        }
    }

    private boolean integral(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        return fieldValue != null
                && fieldValue.isIntegralNumber()
                && fieldValue.canConvertToLong();
    }

    private boolean textual(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        return fieldValue != null && fieldValue.isTextual();
    }

    private boolean number(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        return fieldValue != null && fieldValue.isNumber();
    }

    private void validate(List<InventoryDeductionAppliedItemResponse> values) {
        Set<Long> ingredientIds = new HashSet<>();
        for (InventoryDeductionAppliedItemResponse value : values) {
            if (value == null
                    || value.ingredientId() == null
                    || value.ingredientId() <= 0
                    || !ingredientIds.add(value.ingredientId())
                    || !StringUtils.hasText(value.name())
                    || !StringUtils.hasText(value.unit())
                    || !validPositive(value.deductedQuantity())
                    || !validNonnegative(value.quantityBefore())
                    || !validNonnegative(value.quantityAfter())
                    || value.quantityBefore().subtract(value.deductedQuantity())
                            .compareTo(value.quantityAfter()) != 0
                    || value.resultingVersion() == null
                    || value.resultingVersion() <= 0) {
                throw invalidJson();
            }
        }
    }

    private boolean validPositive(BigDecimal value) {
        return validQuantity(value) && value.signum() > 0;
    }

    private boolean validNonnegative(BigDecimal value) {
        return validQuantity(value) && value.signum() >= 0;
    }

    private boolean validQuantity(BigDecimal value) {
        if (value == null) {
            return false;
        }
        int integerDigits = Math.max(value.precision() - value.scale(), 0);
        return value.scale() <= 3 && integerDigits <= 9;
    }

    private IllegalStateException invalidJson() {
        return new IllegalStateException(INVALID_JSON);
    }
}
