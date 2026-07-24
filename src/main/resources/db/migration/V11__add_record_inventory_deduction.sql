ALTER TABLE dinner_cooking_records
    ADD COLUMN inventory_deduction_status VARCHAR(24) NOT NULL DEFAULT 'NOT_APPLICABLE'
        AFTER completed_at,
    ADD COLUMN inventory_deduction_key CHAR(36) NULL
        AFTER inventory_deduction_status,
    ADD COLUMN inventory_deducted_by BIGINT NULL
        AFTER inventory_deduction_key,
    ADD COLUMN inventory_deducted_at DATETIME(3) NULL
        AFTER inventory_deducted_by,
    ADD COLUMN inventory_deduction_items JSON NULL
        AFTER inventory_deducted_at,
    ADD UNIQUE KEY uk_dinner_records_inventory_deduction_key (inventory_deduction_key),
    ADD KEY idx_dinner_records_inventory_deduction_status
        (household_id, inventory_deduction_status, id),
    ADD CONSTRAINT fk_dinner_records_inventory_deducted_by
        FOREIGN KEY (inventory_deducted_by) REFERENCES users (id),
    ADD CONSTRAINT ck_dinner_records_inventory_deduction_state CHECK (
        (
            inventory_deduction_status IN ('NOT_APPLICABLE', 'PENDING')
            AND inventory_deduction_key IS NULL
            AND inventory_deducted_by IS NULL
            AND inventory_deducted_at IS NULL
            AND inventory_deduction_items IS NULL
        )
        OR
        (
            inventory_deduction_status = 'APPLIED'
            AND inventory_deduction_key IS NOT NULL
            AND inventory_deducted_by IS NOT NULL
            AND inventory_deducted_at IS NOT NULL
            AND inventory_deduction_items IS NOT NULL
            AND JSON_LENGTH(inventory_deduction_items) > 0
        )
        OR
        (
            inventory_deduction_status = 'SKIPPED'
            AND inventory_deduction_key IS NOT NULL
            AND inventory_deducted_by IS NOT NULL
            AND inventory_deducted_at IS NOT NULL
            AND inventory_deduction_items IS NOT NULL
            AND JSON_LENGTH(inventory_deduction_items) = 0
        )
    );
