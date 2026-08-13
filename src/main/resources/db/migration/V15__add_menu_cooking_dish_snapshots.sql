ALTER TABLE dinner_record_dish_snapshots
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'PLANNED' AFTER recipe_version,
    ADD CONSTRAINT ck_dinner_record_dish_origin
        CHECK (origin IN ('PLANNED', 'TEMPORARY'));

CREATE TABLE dinner_menu_cooking_dishes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    recipe_id BIGINT NOT NULL,
    recipe_scope VARCHAR(16) NOT NULL,
    recipe_version BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    image_path VARCHAR(255) NULL,
    category VARCHAR(32) NOT NULL,
    flavor VARCHAR(32) NOT NULL,
    estimated_minutes INT NOT NULL,
    servings INT NULL,
    method_id BIGINT NULL,
    method_name VARCHAR(64) NULL,
    cooking_style VARCHAR(32) NULL,
    method_estimated_minutes INT NULL,
    method_steps JSON NOT NULL,
    ingredients JSON NOT NULL,
    selected_by_user_ids JSON NOT NULL,
    origin VARCHAR(16) NOT NULL,
    added_by BIGINT NULL,
    add_idempotency_key CHAR(36) NULL,
    completed_by BIGINT NULL,
    completed_at DATETIME(3) NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_cooking_dish_recipe (menu_id, recipe_id),
    UNIQUE KEY uk_dinner_cooking_dish_order (menu_id, sort_order),
    UNIQUE KEY uk_dinner_cooking_dish_add_key (add_idempotency_key),
    KEY idx_dinner_cooking_dishes_menu_completed
        (menu_id, completed_at, sort_order),
    CONSTRAINT fk_dinner_cooking_dishes_menu
        FOREIGN KEY (menu_id) REFERENCES dinner_menus (id),
    CONSTRAINT ck_dinner_cooking_dishes_scope
        CHECK (recipe_scope IN ('SYSTEM', 'HOUSEHOLD')),
    CONSTRAINT ck_dinner_cooking_dishes_origin CHECK (
        (origin = 'PLANNED' AND added_by IS NULL AND add_idempotency_key IS NULL)
        OR
        (origin = 'TEMPORARY' AND added_by IS NOT NULL
            AND add_idempotency_key IS NOT NULL)
    ),
    CONSTRAINT ck_dinner_cooking_dishes_completion CHECK (
        (completed_by IS NULL AND completed_at IS NULL)
        OR
        (completed_by IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_dinner_cooking_dishes_method CHECK (
        (
            recipe_scope = 'SYSTEM'
            AND recipe_version = 1
            AND method_id IS NULL
            AND method_name IS NULL
            AND cooking_style IS NULL
            AND method_estimated_minutes IS NULL
            AND JSON_LENGTH(method_steps) = 0
        )
        OR
        (
            recipe_scope = 'HOUSEHOLD'
            AND recipe_version > 0
            AND method_id IS NOT NULL
            AND method_name IS NOT NULL
            AND cooking_style IS NOT NULL
            AND JSON_LENGTH(method_steps) > 0
        )
    ),
    CONSTRAINT ck_dinner_cooking_dishes_snapshot CHECK (
        estimated_minutes > 0
        AND sort_order >= 0
        AND JSON_LENGTH(ingredients) > 0
        AND JSON_LENGTH(selected_by_user_ids) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
