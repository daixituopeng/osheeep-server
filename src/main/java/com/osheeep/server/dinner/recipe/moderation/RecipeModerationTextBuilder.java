package com.osheeep.server.dinner.recipe.moderation;

import com.osheeep.server.dinner.recipe.RecipePublishSnapshot;
import com.osheeep.server.dinner.recipe.RecipeValidationException;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecipeModerationTextBuilder {

    private static final int WECHAT_CONTENT_LIMIT = 2500;

    public String build(RecipePublishSnapshot snapshot) {
        StringBuilder content = new StringBuilder()
                .append("口味：").append(trimToEmpty(snapshot.flavor()))
                .append("\n做法：").append(trimToEmpty(snapshot.defaultMethod().name()))
                .append("\n烹饪方式：")
                .append(trimToEmpty(snapshot.defaultMethod().cookingStyle()));

        List<RecipeMethodStepResponse> steps = snapshot.defaultMethod().steps().stream()
                .sorted(Comparator.comparingInt(RecipeMethodStepResponse::sortOrder))
                .toList();
        for (int index = 0; index < steps.size(); index++) {
            content.append('\n')
                    .append(index + 1)
                    .append(". ")
                    .append(trimToEmpty(steps.get(index).instruction()));
        }
        String result = content.toString();
        requireWithinLimit(result);
        return result;
    }

    public String build(
            RecipePublishSnapshot snapshot,
            List<RecipeMethodDraftResponse> methods
    ) {
        if (methods == null || methods.size() <= 1) {
            return build(snapshot);
        }
        StringBuilder content = new StringBuilder()
                .append("口味：").append(trimToEmpty(snapshot.flavor()));
        for (int index = 0; index < methods.size(); index++) {
            RecipeMethodDraftResponse method = methods.get(index);
            appendMethod(
                    content, index + 1, method.name(), method.cookingStyle(),
                    method.steps().stream()
                            .sorted(Comparator.comparingInt(
                                    RecipeMethodStepResponse::sortOrder))
                            .map(RecipeMethodStepResponse::instruction)
                            .toList());
        }
        String result = content.toString();
        requireWithinLimit(result);
        return result;
    }

    public String buildMethods(String flavor, List<RecipeMethodInput> methods) {
        StringBuilder content = new StringBuilder()
                .append("口味：").append(trimToEmpty(flavor));
        for (int index = 0; index < methods.size(); index++) {
            RecipeMethodInput method = methods.get(index);
            appendMethod(
                    content, index + 1, method.name(), method.cookingStyle(),
                    method.steps().stream()
                            .map(step -> step == null ? null : step.instruction())
                            .toList());
        }
        String result = content.toString();
        requireWithinLimit(result);
        return result;
    }

    private void appendMethod(
            StringBuilder content,
            int methodNumber,
            String name,
            String cookingStyle,
            List<String> steps
    ) {
        content.append("\n做法").append(methodNumber).append("：")
                .append(trimToEmpty(name))
                .append("\n烹饪方式：").append(trimToEmpty(cookingStyle));
        for (int index = 0; index < steps.size(); index++) {
            content.append('\n')
                    .append(methodNumber)
                    .append('.')
                    .append(index + 1)
                    .append(' ')
                    .append(trimToEmpty(steps.get(index)));
        }
    }

    public void requireWithinLimit(String content) {
        if (content.length() > WECHAT_CONTENT_LIMIT) {
            throw new RecipeValidationException(List.of(new RecipeValidationIssue(
                    "PREVIEW", "content", "菜谱内容不能超过2500字")));
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
