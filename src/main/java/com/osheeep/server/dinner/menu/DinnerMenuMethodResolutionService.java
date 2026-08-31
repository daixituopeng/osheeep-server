package com.osheeep.server.dinner.menu;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.menu.dto.MenuMethodResolutionRequest;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DinnerMenuMethodResolutionService {

    private final DinnerMenuSelectionMapper selectionMapper;

    public DinnerMenuMethodResolutionService(DinnerMenuSelectionMapper selectionMapper) {
        this.selectionMapper = selectionMapper;
    }

    public void resolveRequested(
            Long menuId,
            List<DinnerMenuSelectionEntity> selections,
            List<MenuMethodResolutionRequest> requestedResolutions
    ) {
        Map<Long, Set<Long>> conflicts = methodConflicts(selections);
        Map<Long, Long> resolutions = new LinkedHashMap<>();
        for (MenuMethodResolutionRequest resolution : requestedResolutions) {
            if (resolution == null
                    || resolution.recipeId() == null
                    || resolution.methodId() == null
                    || resolutions.putIfAbsent(
                            resolution.recipeId(), resolution.methodId()) != null) {
                throw new BusinessException(
                        ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
            }
        }
        if (!conflicts.keySet().containsAll(resolutions.keySet())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
        }
        if (!resolutions.keySet().containsAll(conflicts.keySet())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_METHOD_RESOLUTION_REQUIRED);
        }
        for (Map.Entry<Long, Long> resolution : resolutions.entrySet()) {
            if (!conflicts.get(resolution.getKey()).contains(resolution.getValue())) {
                throw new BusinessException(
                        ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID);
            }
            apply(menuId, selections, resolution.getKey(), resolution.getValue());
        }
    }

    public void resolveAutomatically(
            Long menuId,
            List<DinnerMenuSelectionEntity> selections,
            Long actorUserId
    ) {
        methodConflicts(selections).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(conflict -> {
                    Long selectedMethodId = selections.stream()
                            .filter(selection -> Objects.equals(
                                    selection.getRecipeId(), conflict.getKey()))
                            .filter(selection -> Objects.equals(
                                    selection.getUserId(), actorUserId))
                            .map(DinnerMenuSelectionEntity::getMethodId)
                            .filter(conflict.getValue()::contains)
                            .findFirst()
                            .orElseGet(() -> conflict.getValue().stream()
                                    .filter(Objects::nonNull)
                                    .sorted()
                                    .findFirst()
                                    .orElseThrow(() -> new BusinessException(
                                            ErrorCode.DINNER_MENU_METHOD_RESOLUTION_INVALID)));
                    apply(menuId, selections, conflict.getKey(), selectedMethodId);
                });
    }

    private void apply(
            Long menuId,
            List<DinnerMenuSelectionEntity> selections,
            Long recipeId,
            Long methodId
    ) {
        selectionMapper.update(
                null,
                Wrappers.<DinnerMenuSelectionEntity>lambdaUpdate()
                        .eq(DinnerMenuSelectionEntity::getMenuId, menuId)
                        .eq(DinnerMenuSelectionEntity::getRecipeId, recipeId)
                        .set(DinnerMenuSelectionEntity::getMethodId, methodId));
        selections.stream()
                .filter(selection -> Objects.equals(selection.getRecipeId(), recipeId))
                .forEach(selection -> selection.setMethodId(methodId));
    }

    private Map<Long, Set<Long>> methodConflicts(
            List<DinnerMenuSelectionEntity> selections
    ) {
        Map<Long, Long> versions = new LinkedHashMap<>();
        Map<Long, Set<Long>> methods = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection.getRecipeId() == null || selection.getRecipeVersion() == null) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
            }
            Long previousVersion = versions.putIfAbsent(
                    selection.getRecipeId(), selection.getRecipeVersion());
            if (previousVersion != null
                    && !previousVersion.equals(selection.getRecipeVersion())) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
            }
            if (selection.getMethodId() != null) {
                methods.computeIfAbsent(
                                selection.getRecipeId(), ignored -> new LinkedHashSet<>())
                        .add(selection.getMethodId());
            }
        }
        Map<Long, Set<Long>> conflicts = new LinkedHashMap<>();
        methods.forEach((recipeId, methodIds) -> {
            if (methodIds.size() > 1) {
                conflicts.put(recipeId, Set.copyOf(methodIds));
            }
        });
        return Map.copyOf(conflicts);
    }
}
