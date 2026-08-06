package com.osheeep.server.dinner.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_household_shopping_items")
public class DinnerHouseholdShoppingItemEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("household_id") private Long householdId;
    @TableField("ingredient_id") private Long ingredientId;
    @TableField("added_by") private Long addedBy;
    @TableField("created_at") private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public Long getIngredientId() { return ingredientId; }
    public void setIngredientId(Long ingredientId) { this.ingredientId = ingredientId; }
    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
