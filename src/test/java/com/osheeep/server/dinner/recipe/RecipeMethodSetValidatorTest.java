package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeMethodSetValidatorTest {

    private final RecipeMethodSetValidator validator = new RecipeMethodSetValidator();

    @Test
    void normalizesOrderedMethodsAndRequiresExactlyOneDefault() {
        var result = validator.validate(List.of(
                method(21L, " 家常炒 ", true),
                method(null, " 少油焖 ", false)));

        assertThat(result).extracting(item -> item.name())
                .containsExactly("家常炒", "少油焖");
        assertThat(result).extracting(item -> item.sortOrder())
                .containsExactly(0, 1);
        assertThat(result.get(1).steps()).containsExactly("小火焖熟");
    }

    @Test
    void duplicateNormalizedNamesAreRejected() {
        assertInvalid(List.of(
                method(21L, "家常炒", true),
                method(null, " 家常炒 ", false)));
    }

    @Test
    void zeroOrTwoDefaultsAreRejected() {
        assertInvalid(List.of(method(21L, "家常炒", false)));
        assertInvalid(List.of(
                method(21L, "家常炒", true),
                method(22L, "少油焖", true)));
    }

    private void assertInvalid(List<RecipeMethodInput> methods) {
        assertThatThrownBy(() -> validator.validate(methods))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_INVALID));
    }

    private RecipeMethodInput method(Long id, String name, boolean defaultMethod) {
        return new RecipeMethodInput(
                id, name, " 炒 ", 18, defaultMethod,
                List.of(new RecipeMethodStepInput(" 小火焖熟 ")));
    }
}
