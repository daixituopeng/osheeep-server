package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.user.AccountDeletionTransaction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class DinnerMembershipTerminationMapperContractTest {

    @Test
    void everyTerminationMapperAnnotationParsesIntoAMappedStatement() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        List.of(
                        DinnerHouseholdOperationMapper.class,
                        DinnerHouseholdMapper.class,
                        DinnerHouseholdMemberMapper.class,
                        DinnerMenuMapper.class,
                        DinnerMenuSelectionMapper.class,
                        DinnerRecipeMapper.class,
                        DinnerRecipeIngredientMapper.class,
                        DinnerHouseholdInventoryMapper.class,
                        DinnerIngredientMapper.class)
                .forEach(configuration::addMapper);

        assertStatement(configuration, DinnerHouseholdOperationMapper.class,
                "selectByActorAndIdempotencyKeyForUpdate");
        assertStatement(configuration, DinnerHouseholdOperationMapper.class,
                "deleteExpiredByActorAndIdempotencyKey");
        assertStatement(configuration, DinnerHouseholdMapper.class,
                "advanceOwnership");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "endActiveMember");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "demoteActiveOwner");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "promoteActiveMember");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "selectIdsByHouseholdId");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "selectIdsByUserId");
        assertStatement(configuration, DinnerHouseholdMemberMapper.class,
                "selectByIdsForUpdate");
        assertStatement(configuration, DinnerMenuMapper.class,
                "selectUncompletedByHouseholdIdForUpdate");
        assertStatement(configuration, DinnerMenuMapper.class,
                "resetUncompletedMenus");
        assertStatement(configuration, DinnerMenuSelectionMapper.class,
                "selectByMenuIdsForUpdate");
        assertStatement(configuration, DinnerMenuSelectionMapper.class,
                "deleteByMenuIdsAndUserId");
        assertStatement(configuration, DinnerRecipeMapper.class,
                "selectByHouseholdId");
        assertStatement(configuration, DinnerRecipeMapper.class,
                "selectByIdsForUpdate");
        assertStatement(configuration, DinnerRecipeMapper.class,
                "detachOwnedDraft");
        assertStatement(configuration, DinnerRecipeIngredientMapper.class,
                "selectByRecipeIdsForUpdate");
        assertStatement(configuration, DinnerHouseholdInventoryMapper.class,
                "selectAllByHouseholdIdForUpdate");
        assertStatement(configuration, DinnerIngredientMapper.class,
                "selectAllHouseholdIngredientsForUpdate");
    }

    @Test
    void destructiveDiscoveryTransactionsUseReadCommitted() throws NoSuchMethodException {
        Transactional deletion = AccountDeletionTransaction.class
                .getMethod("deleteVerified", Long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional dissolution = DinnerHouseholdDissolutionTransaction.class
                .getMethod(
                        "dissolve",
                        DinnerHouseholdOperationService.HouseholdOperationCommand.class,
                        String.class,
                        String.class)
                .getAnnotation(Transactional.class);

        assertThat(deletion).isNotNull();
        assertThat(deletion.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(dissolution).isNotNull();
        assertThat(dissolution.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    private void assertStatement(
            MybatisConfiguration configuration,
            Class<?> mapperType,
            String methodName
    ) {
        assertThat(configuration.hasStatement(
                mapperType.getName() + "." + methodName)).isTrue();
    }
}
