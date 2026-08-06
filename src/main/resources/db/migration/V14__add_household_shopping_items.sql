CREATE TABLE dinner_household_shopping_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    ingredient_id BIGINT NOT NULL,
    added_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_household_shopping_item (household_id, ingredient_id),
    KEY idx_dinner_shopping_household_created (household_id, created_at, id),
    KEY idx_dinner_shopping_ingredient (ingredient_id),
    KEY idx_dinner_shopping_added_by (added_by),
    CONSTRAINT fk_dinner_shopping_household
        FOREIGN KEY (household_id) REFERENCES dinner_households (id) ON DELETE CASCADE,
    CONSTRAINT fk_dinner_shopping_ingredient
        FOREIGN KEY (ingredient_id) REFERENCES dinner_ingredients (id) ON DELETE CASCADE,
    CONSTRAINT fk_dinner_shopping_added_by
        FOREIGN KEY (added_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
