package com.osheeep.server.dinner.ingredient;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdIngredientTransaction {

    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerHouseholdAccessService accessService;

    public DinnerHouseholdIngredientTransaction(
            DinnerIngredientMapper ingredientMapper,
            DinnerHouseholdAccessService accessService
    ) {
        this.ingredientMapper = ingredientMapper;
        this.accessService = accessService;
    }

    @Transactional
    public IngredientResponse create(
            Long userId,
            String name,
            String category,
            String defaultUnit
    ) {
        ActiveHouseholdAccess access =
                accessService.lockActiveHouseholdContext(userId).access();
        if (!ingredientMapper
                .selectActiveAccessibleByName(access.householdId(), name)
                .isEmpty()) {
            throw alreadyExists();
        }

        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setScope("HOUSEHOLD");
        ingredient.setHouseholdId(access.householdId());
        ingredient.setName(name);
        ingredient.setCategory(category);
        ingredient.setDefaultUnit(defaultUnit);
        ingredient.setStatus("ACTIVE");
        try {
            ingredientMapper.insert(ingredient);
        } catch (DuplicateKeyException exception) {
            throw alreadyExists();
        }
        return new IngredientResponse(
                ingredient.getId(),
                ingredient.getName(),
                ingredient.getCategory(),
                ingredient.getDefaultUnit(),
                ingredient.getScope());
    }

    private BusinessException alreadyExists() {
        return new BusinessException(ErrorCode.DINNER_INGREDIENT_ALREADY_EXISTS);
    }
}

