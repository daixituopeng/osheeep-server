package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.dinner.record.dto.InventoryDeductionAppliedItemResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DinnerInventoryDeductionJsonCodecTest {

    private DinnerInventoryDeductionJsonCodec codec;

    @BeforeEach
    void setUp() {
        codec = new DinnerInventoryDeductionJsonCodec(new ObjectMapper());
    }

    @Test
    void roundTripsExactAppliedItemsAndAllowsAnEmptySkippedResult() {
        List<InventoryDeductionAppliedItemResponse> items = List.of(
                new InventoryDeductionAppliedItemResponse(
                        1L, "番茄", "个",
                        new BigDecimal("2.000"),
                        new BigDecimal("3.000"),
                        new BigDecimal("1.000"),
                        4L));

        String json = codec.write(items);

        assertThat(codec.read(json)).isEqualTo(items);
        assertThat(codec.read(codec.write(List.of()))).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsDuplicateIngredientsAndBrokenArithmetic() {
        assertThatThrownBy(() -> codec.read("""
                [{
                  "ingredientId":1,
                  "name":"番茄",
                  "unit":"个",
                  "deductedQuantity":2,
                  "quantityBefore":3,
                  "quantityAfter":1,
                  "resultingVersion":4,
                  "extra":"no"
                }]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner inventory deduction JSON");

        var duplicate = new InventoryDeductionAppliedItemResponse(
                1L, "番茄", "个",
                BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ONE, 2L);
        assertThatThrownBy(() -> codec.write(List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner inventory deduction JSON");

        assertThatThrownBy(() -> codec.write(List.of(
                new InventoryDeductionAppliedItemResponse(
                        1L, "番茄", "个",
                        BigDecimal.ONE,
                        new BigDecimal("2"),
                        new BigDecimal("2"),
                        2L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner inventory deduction JSON");
    }
}
