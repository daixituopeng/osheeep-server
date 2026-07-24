package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RecipeMethodSetValidator {

    public List<ValidatedMethod> validate(List<RecipeMethodInput> methods) {
        if (methods == null || methods.isEmpty() || methods.size() > 8) {
            throw invalid();
        }
        List<ValidatedMethod> result = new ArrayList<>(methods.size());
        Set<Long> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        int defaultCount = 0;
        for (int index = 0; index < methods.size(); index++) {
            RecipeMethodInput method = methods.get(index);
            if (method == null
                    || !StringUtils.hasText(method.name())
                    || method.name().strip().length() > 40
                    || !StringUtils.hasText(method.cookingStyle())
                    || method.cookingStyle().strip().length() > 32
                    || method.estimatedMinutes() == null
                    || method.estimatedMinutes() < 1
                    || method.estimatedMinutes() > 1440
                    || method.steps() == null
                    || method.steps().isEmpty()
                    || method.steps().size() > 12) {
                throw invalid();
            }
            if (method.id() != null && (!ids.add(method.id()) || method.id() <= 0)) {
                throw invalid();
            }
            String name = method.name().strip();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid();
            }
            if (method.defaultMethod()) {
                defaultCount++;
            }
            List<String> steps = method.steps().stream()
                    .map(step -> step == null || step.instruction() == null
                            ? "" : step.instruction().strip())
                    .toList();
            if (steps.stream().anyMatch(step -> step.isBlank() || step.length() > 160)) {
                throw invalid();
            }
            result.add(new ValidatedMethod(
                    method.id(), name, method.cookingStyle().strip(),
                    method.estimatedMinutes(), method.defaultMethod(), index, steps));
        }
        if (defaultCount != 1) {
            throw invalid();
        }
        return List.copyOf(result);
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }

    public record ValidatedMethod(
            Long id,
            String name,
            String cookingStyle,
            Integer estimatedMinutes,
            boolean defaultMethod,
            int sortOrder,
            List<String> steps
    ) {
        public ValidatedMethod {
            steps = List.copyOf(steps);
        }
    }
}
