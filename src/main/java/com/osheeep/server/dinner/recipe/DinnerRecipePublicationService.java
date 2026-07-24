package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DinnerRecipePublicationService {

    private final DinnerRecipePublishSnapshotLoader snapshotLoader;
    private final WechatUserIdentityMapper identityMapper;
    private final DinnerTextSafetyGateway gateway;
    private final DinnerRecipePublishTransaction transaction;
    private final DinnerRecipeRevisionTransaction revisionTransaction;

    @Autowired
    public DinnerRecipePublicationService(
            DinnerRecipePublishSnapshotLoader snapshotLoader,
            WechatUserIdentityMapper identityMapper,
            DinnerTextSafetyGateway gateway,
            DinnerRecipePublishTransaction transaction,
            DinnerRecipeRevisionTransaction revisionTransaction
    ) {
        this.snapshotLoader = snapshotLoader;
        this.identityMapper = identityMapper;
        this.gateway = gateway;
        this.transaction = transaction;
        this.revisionTransaction = revisionTransaction;
    }

    DinnerRecipePublicationService(
            DinnerRecipePublishSnapshotLoader snapshotLoader,
            WechatUserIdentityMapper identityMapper,
            DinnerTextSafetyGateway gateway,
            DinnerRecipePublishTransaction transaction
    ) {
        this(snapshotLoader, identityMapper, gateway, transaction, null);
    }

    public RecipeDraftResponse publish(Long userId, Long recipeId, long expectedVersion) {
        RecipePublishSnapshot snapshot = snapshotLoader.loadForModeration(userId, recipeId, expectedVersion);
        WechatUserIdentityEntity identity = identityMapper.selectOne(
                Wrappers.<WechatUserIdentityEntity>lambdaQuery()
                        .eq(WechatUserIdentityEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (identity == null || identity.getOpenid() == null || identity.getOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
        DinnerTextSafetyResult result;
        try {
            result = gateway.check(identity.getOpenid(), snapshot.name(), snapshot.moderationText());
        } catch (DinnerTextSafetyUnavailableException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
        if (result == DinnerTextSafetyResult.REJECT) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED);
        }
        if (result != DinnerTextSafetyResult.PASS) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
        }
        if (snapshot.revisionOfRecipeId() != null) {
            if (revisionTransaction == null) {
                throw new IllegalStateException("Revision transaction is unavailable");
            }
            return revisionTransaction.applyChecked(userId, recipeId, expectedVersion);
        }
        return transaction.publishChecked(userId, recipeId, expectedVersion);
    }
}
