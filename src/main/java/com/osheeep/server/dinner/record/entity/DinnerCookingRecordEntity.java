package com.osheeep.server.dinner.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("dinner_cooking_records")
public class DinnerCookingRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("household_id") private Long householdId;
    @TableField("menu_id") private Long menuId;
    @TableField("record_date") private LocalDate recordDate;
    @TableField("completed_by") private Long completedBy;
    @TableField("completed_at") private LocalDateTime completedAt;
    @TableField("inventory_deduction_status") private String inventoryDeductionStatus;
    @TableField("inventory_deduction_key") private String inventoryDeductionKey;
    @TableField("inventory_deducted_by") private Long inventoryDeductedBy;
    @TableField("inventory_deducted_at") private LocalDateTime inventoryDeductedAt;
    @TableField("inventory_deduction_items") private String inventoryDeductionItems;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public Long getCompletedBy() { return completedBy; }
    public void setCompletedBy(Long completedBy) { this.completedBy = completedBy; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getInventoryDeductionStatus() { return inventoryDeductionStatus; }
    public void setInventoryDeductionStatus(String inventoryDeductionStatus) {
        this.inventoryDeductionStatus = inventoryDeductionStatus;
    }
    public String getInventoryDeductionKey() { return inventoryDeductionKey; }
    public void setInventoryDeductionKey(String inventoryDeductionKey) {
        this.inventoryDeductionKey = inventoryDeductionKey;
    }
    public Long getInventoryDeductedBy() { return inventoryDeductedBy; }
    public void setInventoryDeductedBy(Long inventoryDeductedBy) {
        this.inventoryDeductedBy = inventoryDeductedBy;
    }
    public LocalDateTime getInventoryDeductedAt() { return inventoryDeductedAt; }
    public void setInventoryDeductedAt(LocalDateTime inventoryDeductedAt) {
        this.inventoryDeductedAt = inventoryDeductedAt;
    }
    public String getInventoryDeductionItems() { return inventoryDeductionItems; }
    public void setInventoryDeductionItems(String inventoryDeductionItems) {
        this.inventoryDeductionItems = inventoryDeductionItems;
    }
}
