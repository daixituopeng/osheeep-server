package com.osheeep.server.dinner.cooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_menu_cooking_dishes")
public class DinnerMenuCookingDishEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("menu_id") private Long menuId;
    @TableField("recipe_id") private Long recipeId;
    @TableField("recipe_scope") private String recipeScope;
    @TableField("recipe_version") private Long recipeVersion;
    private String name;
    @TableField("image_path") private String imagePath;
    private String category;
    private String flavor;
    @TableField("estimated_minutes") private Integer estimatedMinutes;
    private Integer servings;
    @TableField("method_id") private Long methodId;
    @TableField("method_name") private String methodName;
    @TableField("cooking_style") private String cookingStyle;
    @TableField("method_estimated_minutes") private Integer methodEstimatedMinutes;
    @TableField("method_steps") private String methodStepsJson;
    private String ingredients;
    @TableField("selected_by_user_ids") private String selectedByUserIds;
    private String origin;
    @TableField("added_by") private Long addedBy;
    @TableField("add_idempotency_key") private String addIdempotencyKey;
    @TableField("completed_by") private Long completedBy;
    @TableField("completed_at") private LocalDateTime completedAt;
    @TableField("sort_order") private Integer sortOrder;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public String getRecipeScope() { return recipeScope; }
    public void setRecipeScope(String recipeScope) { this.recipeScope = recipeScope; }
    public Long getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(Long recipeVersion) { this.recipeVersion = recipeVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFlavor() { return flavor; }
    public void setFlavor(String flavor) { this.flavor = flavor; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) {
        this.estimatedMinutes = estimatedMinutes;
    }
    public Integer getServings() { return servings; }
    public void setServings(Integer servings) { this.servings = servings; }
    public Long getMethodId() { return methodId; }
    public void setMethodId(Long methodId) { this.methodId = methodId; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getCookingStyle() { return cookingStyle; }
    public void setCookingStyle(String cookingStyle) { this.cookingStyle = cookingStyle; }
    public Integer getMethodEstimatedMinutes() { return methodEstimatedMinutes; }
    public void setMethodEstimatedMinutes(Integer methodEstimatedMinutes) {
        this.methodEstimatedMinutes = methodEstimatedMinutes;
    }
    public String getMethodStepsJson() { return methodStepsJson; }
    public void setMethodStepsJson(String methodStepsJson) {
        this.methodStepsJson = methodStepsJson;
    }
    public String getIngredients() { return ingredients; }
    public void setIngredients(String ingredients) { this.ingredients = ingredients; }
    public String getSelectedByUserIds() { return selectedByUserIds; }
    public void setSelectedByUserIds(String selectedByUserIds) {
        this.selectedByUserIds = selectedByUserIds;
    }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public Long getAddedBy() { return addedBy; }
    public void setAddedBy(Long addedBy) { this.addedBy = addedBy; }
    public String getAddIdempotencyKey() { return addIdempotencyKey; }
    public void setAddIdempotencyKey(String addIdempotencyKey) {
        this.addIdempotencyKey = addIdempotencyKey;
    }
    public Long getCompletedBy() { return completedBy; }
    public void setCompletedBy(Long completedBy) { this.completedBy = completedBy; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
