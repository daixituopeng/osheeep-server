package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import org.junit.jupiter.api.Test;

class DinnerHouseholdIngredientMapperContractTest {

    @Test
    void accessibleNameLookupParsesIntoAMappedStatement() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(DinnerIngredientMapper.class);

        assertThat(configuration.hasStatement(
                DinnerIngredientMapper.class.getName()
                        + ".selectActiveAccessibleByName"))
                .isTrue();
    }
}
