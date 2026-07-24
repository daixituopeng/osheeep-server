package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeMethodsRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.moderation.RecipeModerationTextBuilder;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class DinnerRecipeMethodService {

    private final DinnerRecipeAuthorizer authorizer;
    private final RecipeMethodSetValidator validator;
    private final RecipeModerationTextBuilder textBuilder;
    private final WechatUserIdentityMapper identityMapper;
    private final DinnerTextSafetyGateway gateway;
    private final DinnerRecipeMethodTransaction transaction;

    public DinnerRecipeMethodService(
            DinnerRecipeAuthorizer authorizer,
            RecipeMethodSetValidator validator,
            RecipeModerationTextBuilder textBuilder,
            WechatUserIdentityMapper identityMapper,
            DinnerTextSafetyGateway gateway,
            DinnerRecipeMethodTransaction transaction
    ) {
        this.authorizer = authorizer;
        this.validator = validator;
        this.textBuilder = textBuilder;
        this.identityMapper = identityMapper;
        this.gateway = gateway;
        this.transaction = transaction;
    }

    public RecipeDraftResponse replaceMethods(
            Long userId,
            Long recipeId,
            ReplaceRecipeMethodsRequest request
    ) {
        var validated = validator.validate(request.methods());
        RecipeAccess access = authorizer.requireMembership(userId);
        DinnerRecipeEntity recipe = authorizer.requireVisible(access, recipeId);
        requireEditable(userId, recipe, request.version());
        if ("PUBLISHED".equals(recipe.getStatus())) {
            moderate(userId, recipe, request);
        }
        return transaction.replace(userId, recipeId, request.version(), validated);
    }

    private void requireEditable(
            Long userId,
            DinnerRecipeEntity recipe,
            long expectedVersion
    ) {
        boolean editable = "PUBLISHED".equals(recipe.getStatus())
                || ("DRAFT".equals(recipe.getStatus())
                && Objects.equals(userId, recipe.getCreatorId()));
        if (!editable) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Objects.equals(recipe.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }

    private void moderate(
            Long userId,
            DinnerRecipeEntity recipe,
            ReplaceRecipeMethodsRequest request
    ) {
        WechatUserIdentityEntity identity = identityMapper.selectOne(
                Wrappers.<WechatUserIdentityEntity>lambdaQuery()
                        .eq(WechatUserIdentityEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (identity == null || identity.getOpenid() == null
                || identity.getOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
        DinnerTextSafetyResult result;
        try {
            result = gateway.check(
                    identity.getOpenid(),
                    recipe.getName(),
                    textBuilder.buildMethods(recipe.getFlavor(), request.methods()));
        } catch (DinnerTextSafetyUnavailableException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
        if (result == DinnerTextSafetyResult.REJECT) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED);
        }
        if (result != DinnerTextSafetyResult.PASS) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
    }
}
