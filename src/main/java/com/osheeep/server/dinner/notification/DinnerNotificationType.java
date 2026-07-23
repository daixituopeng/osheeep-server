package com.osheeep.server.dinner.notification;

import java.util.Arrays;

public enum DinnerNotificationType {
    PARTNER_JOINED(
            "TA 已加入小家",
            "现在可以一起选今晚想吃的菜了",
            DinnerNotificationTarget.HOUSEHOLD_MANAGE),
    PARTNER_SELECTION_UPDATED(
            "TA 更新了今晚选择",
            "看看合并后的菜单，准备好后再确认",
            DinnerNotificationTarget.TONIGHT),
    MENU_RECONFIRM_REQUIRED(
            "今晚菜单需要重新确认",
            "TA 修改了选择，请查看最新内容",
            DinnerNotificationTarget.TONIGHT),
    MENU_COMPLETED(
            "今晚开饭完成啦",
            "做饭记录已经保存，可以一起回看",
            DinnerNotificationTarget.RECORDS),
    FAMILY_RECIPE_UPDATED(
            "家庭菜谱有更新",
            "TA 发布或更新了一道家庭菜",
            DinnerNotificationTarget.FAMILY_RECIPES),
    INVENTORY_UPDATED(
            "家里的食材有更新",
            "TA 修改了共享库存",
            DinnerNotificationTarget.INGREDIENTS),
    OWNERSHIP_TRANSFERRED(
            "你现在是小家管理员",
            "管理权已经转给你",
            DinnerNotificationTarget.HOUSEHOLD_MANAGE),
    MEMBER_LEFT(
            "TA 已退出小家",
            "成员席位已更新，你可以重新邀请 TA",
            DinnerNotificationTarget.HOUSEHOLD_MANAGE),
    MEMBER_REMOVED(
            "你已离开原小家",
            "对方已结束你的成员关系，你可以创建或加入新的小家",
            DinnerNotificationTarget.HOUSEHOLD_BINDING);

    private final String title;
    private final String body;
    private final DinnerNotificationTarget target;

    DinnerNotificationType(
            String title,
            String body,
            DinnerNotificationTarget target
    ) {
        this.title = title;
        this.body = body;
        this.target = target;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public DinnerNotificationTarget target() {
        return target;
    }

    public static DinnerNotificationType fromStoredValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown dinner notification type"));
    }
}
