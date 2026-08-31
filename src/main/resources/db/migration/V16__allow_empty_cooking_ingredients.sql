ALTER TABLE dinner_menu_cooking_dishes
    DROP CHECK ck_dinner_cooking_dishes_snapshot,
    ADD CONSTRAINT ck_dinner_cooking_dishes_snapshot CHECK (
        estimated_minutes > 0
        AND sort_order >= 0
        AND JSON_TYPE(ingredients) = 'ARRAY'
        AND JSON_LENGTH(selected_by_user_ids) > 0
    );
