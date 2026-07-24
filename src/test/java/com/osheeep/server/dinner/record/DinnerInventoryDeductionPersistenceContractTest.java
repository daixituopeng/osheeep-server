package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DinnerInventoryDeductionPersistenceContractTest {

    private static final Path V11 = Path.of(
            "src/main/resources/db/migration/"
                    + "V11__add_record_inventory_deduction.sql");

    @Test
    void v11AddsARecordScopedTerminalWorkflowWithoutRewritingHistory() throws Exception {
        String sql = Files.readString(V11).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("alter table dinner_cooking_records")
                .contains("inventory_deduction_status varchar(24) not null"
                        + " default 'not_applicable'")
                .contains("inventory_deduction_key char(36)")
                .contains("inventory_deducted_by bigint")
                .contains("inventory_deducted_at datetime(3)")
                .contains("inventory_deduction_items json")
                .contains("unique key uk_dinner_records_inventory_deduction_key")
                .contains("'not_applicable', 'pending'")
                .contains("inventory_deduction_status = 'applied'")
                .contains("inventory_deduction_status = 'skipped'")
                .contains("json_length(inventory_deduction_items) > 0")
                .contains("json_length(inventory_deduction_items) = 0");
    }

    @Test
    void entityAndMappersExposeTheRecordThenSortedInventoryLockOrder() throws Exception {
        assertField("inventoryDeductionStatus", "inventory_deduction_status");
        assertField("inventoryDeductionKey", "inventory_deduction_key");
        assertField("inventoryDeductedBy", "inventory_deducted_by");
        assertField("inventoryDeductedAt", "inventory_deducted_at");
        assertField("inventoryDeductionItems", "inventory_deduction_items");

        assertThat(DinnerCookingRecordMapper.class.getMethod(
                "selectByHouseholdAndIdForUpdate", Long.class, Long.class)).isNotNull();
        assertThat(DinnerHouseholdInventoryMapper.class.getMethod(
                "selectByHouseholdAndIngredientIdsForUpdate",
                Long.class, List.class)).isNotNull();
    }

    private void assertField(String fieldName, String columnName) throws Exception {
        TableField field = DinnerCookingRecordEntity.class
                .getDeclaredField(fieldName)
                .getAnnotation(TableField.class);
        assertThat(field).isNotNull();
        assertThat(field.value()).isEqualTo(columnName);
    }
}
