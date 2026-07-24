package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeMethodsRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.moderation.RecipeModerationTextBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeMethodServiceTest {

    private static final RecipeAccess ACCESS = new RecipeAccess(8L, 70L);

    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private DinnerTextSafetyGateway gateway;
    @Mock private DinnerRecipeMethodTransaction transaction;

    private DinnerRecipeMethodService service;

    @BeforeEach
    void setUp() {
        service = new DinnerRecipeMethodService(
                authorizer, new RecipeMethodSetValidator(),
                new RecipeModerationTextBuilder(), identityMapper, gateway, transaction);
        when(authorizer.requireMembership(8L)).thenReturn(ACCESS);
    }

    @Test
    void partnerEditOfPublishedRecipeIsModeratedBeforeTheTransaction() {
        DinnerRecipeEntity published = recipe("PUBLISHED", 7L, 4L);
        ReplaceRecipeMethodsRequest request = request(4L);
        when(authorizer.requireVisible(ACCESS, 101L)).thenReturn(published);
        when(identityMapper.selectOne(any())).thenReturn(identity());
        when(gateway.check("openid-8", "番茄炒蛋", 
                "口味：酸甜\n做法1：家常炒\n烹饪方式：炒\n1.1 炒熟"))
                .thenReturn(DinnerTextSafetyResult.PASS);
        when(transaction.replace(any(), any(), any(Long.class), any()))
                .thenReturn(response());

        assertThat(service.replaceMethods(8L, 101L, request).version()).isEqualTo(5L);

        verify(transaction).replace(8L, 101L, 4L, new RecipeMethodSetValidator()
                .validate(request.methods()));
    }

    @Test
    void aCreatorsDraftSkipsModeration() {
        DinnerRecipeEntity draft = recipe("DRAFT", 8L, 4L);
        ReplaceRecipeMethodsRequest request = request(4L);
        when(authorizer.requireVisible(ACCESS, 101L)).thenReturn(draft);
        when(transaction.replace(any(), any(), any(Long.class), any()))
                .thenReturn(response());

        service.replaceMethods(8L, 101L, request);

        verifyNoInteractions(identityMapper, gateway);
    }

    @Test
    void rejectedPublishedEditNeverStartsTheTransaction() {
        when(authorizer.requireVisible(ACCESS, 101L))
                .thenReturn(recipe("PUBLISHED", 7L, 4L));
        when(identityMapper.selectOne(any())).thenReturn(identity());
        when(gateway.check(any(), any(), any()))
                .thenReturn(DinnerTextSafetyResult.REJECT);

        assertThatThrownBy(() -> service.replaceMethods(8L, 101L, request(4L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED));

        verifyNoInteractions(transaction);
    }

    private ReplaceRecipeMethodsRequest request(long version) {
        return new ReplaceRecipeMethodsRequest(version, List.of(new RecipeMethodInput(
                201L, "家常炒", "炒", 15, true,
                List.of(new RecipeMethodStepInput("炒熟")))));
    }

    private DinnerRecipeEntity recipe(String status, Long creatorId, Long version) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(101L);
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(creatorId);
        recipe.setName("番茄炒蛋");
        recipe.setFlavor("酸甜");
        recipe.setStatus(status);
        recipe.setVersion(version);
        return recipe;
    }

    private WechatUserIdentityEntity identity() {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setOpenid("openid-8");
        return identity;
    }

    private RecipeDraftResponse response() {
        return new RecipeDraftResponse(
                101L, "PUBLISHED", 5L, "番茄炒蛋", "家常菜", "酸甜",
                2, 15, List.of(), null, null, List.of(), null);
    }
}
