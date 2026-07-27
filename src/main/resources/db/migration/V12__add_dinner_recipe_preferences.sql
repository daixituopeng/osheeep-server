CREATE TABLE dinner_recipe_preferences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recipe_id BIGINT NOT NULL,
    preference VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_recipe_preferences_membership_recipe
        (membership_id, recipe_id),
    KEY idx_dinner_recipe_preferences_household_recipe
        (household_id, recipe_id, membership_id),
    KEY idx_dinner_recipe_preferences_user (user_id, id),
    CONSTRAINT fk_dinner_recipe_preferences_household
        FOREIGN KEY (household_id) REFERENCES dinner_households (id),
    CONSTRAINT fk_dinner_recipe_preferences_membership
        FOREIGN KEY (membership_id) REFERENCES dinner_household_members (id),
    CONSTRAINT fk_dinner_recipe_preferences_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_dinner_recipe_preferences_recipe
        FOREIGN KEY (recipe_id) REFERENCES dinner_recipes (id),
    CONSTRAINT ck_dinner_recipe_preferences_value
        CHECK (preference IN ('LIKE', 'NEUTRAL', 'DISLIKE')),
    CONSTRAINT ck_dinner_recipe_preferences_version
        CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
