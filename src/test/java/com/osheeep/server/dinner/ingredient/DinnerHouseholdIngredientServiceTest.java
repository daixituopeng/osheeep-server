package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdIngredientServiceTest {

    @Mock private DinnerHouseholdAccessService accessService;
    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private DinnerTextSafetyGateway textSafetyGateway;
    @Mock private DinnerHouseholdIngredientTransaction transaction;

    private DinnerHouseholdIngredientService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdIngredientService(
                accessService, identityMapper, textSafetyGateway, transaction);
    }

    @Test
    void moderatesNormalizedNameBeforeCreatingSharedIngredient() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access());
        when(identityMapper.selectByUserId(7L)).thenReturn(identity("openid-7"));
        when(textSafetyGateway.check("openid-7", "冻豆腐", "冻豆腐"))
                .thenReturn(DinnerTextSafetyResult.PASS);
        IngredientResponse created =
                new IngredientResponse(21L, "冻豆腐", "豆制品", "块", "HOUSEHOLD");
        when(transaction.create(7L, "冻豆腐", "豆制品", "块")).thenReturn(created);

        assertThat(service.create(7L, "  冻豆腐  ", " 豆制品 ", " 块 "))
                .isEqualTo(created);

        verify(textSafetyGateway).check("openid-7", "冻豆腐", "冻豆腐");
        verify(transaction).create(7L, "冻豆腐", "豆制品", "块");
    }

    @Test
    void trimsUnicodeSpacesBeforeModerationAndPersistence() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access());
        when(identityMapper.selectByUserId(7L)).thenReturn(identity("openid-7"));
        when(textSafetyGateway.check("openid-7", "冻豆腐", "冻豆腐"))
                .thenReturn(DinnerTextSafetyResult.PASS);
        IngredientResponse created =
                new IngredientResponse(21L, "冻豆腐", "豆制品", "块", "HOUSEHOLD");
        when(transaction.create(7L, "冻豆腐", "豆制品", "块")).thenReturn(created);

        assertThat(service.create(
                        7L,
                        "\u00A0冻豆腐\u3000",
                        "\u00A0豆制品\u3000",
                        "\u00A0块\u3000"))
                .isEqualTo(created);
    }

    @Test
    void inactiveMemberIsRejectedBeforeModeration() {
        when(accessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.create(7L, "冻豆腐", "豆制品", "块"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        verifyNoInteractions(identityMapper, textSafetyGateway, transaction);
    }

    @Test
    void rejectedNameNeverStartsTheWriteTransaction() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access());
        when(identityMapper.selectByUserId(7L)).thenReturn(identity("openid-7"));
        when(textSafetyGateway.check("openid-7", "风险食材", "风险食材"))
                .thenReturn(DinnerTextSafetyResult.REJECT);

        assertThatThrownBy(() -> service.create(7L, "风险食材", "其他", "份"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_NAME_REJECTED));

        verifyNoInteractions(transaction);
    }

    @Test
    void missingIdentityOrUnavailableModerationReturnsRetryableError() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access());
        when(identityMapper.selectByUserId(7L))
                .thenReturn(null)
                .thenReturn(identity("openid-7"));

        assertThatThrownBy(() -> service.create(7L, "冻豆腐", "豆制品", "块"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_MODERATION_UNAVAILABLE));

        when(textSafetyGateway.check("openid-7", "冻豆腐", "冻豆腐"))
                .thenThrow(new DinnerTextSafetyUnavailableException());
        assertThatThrownBy(() -> service.create(7L, "冻豆腐", "豆制品", "块"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_MODERATION_UNAVAILABLE));

        verify(transaction, never()).create(any(), any(), any(), any());
    }

    @Test
    void invalidNameCategoryAndUnitAreRejectedBeforeModeration() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access());

        assertValidation(() -> service.create(7L, "   ", "蔬菜", "克"));
        assertValidation(() -> service.create(7L, "含\n换行", "蔬菜", "克"));
        assertValidation(() -> service.create(7L, "一".repeat(21), "蔬菜", "克"));
        assertValidation(() -> service.create(7L, "\uD83D", "蔬菜", "克"));
        assertValidation(() -> service.create(7L, "冻豆腐", "不存在的分类", "块"));
        assertValidation(() -> service.create(7L, "冻豆腐", "豆制品", "斤"));

        verifyNoInteractions(identityMapper, textSafetyGateway, transaction);
    }

    private void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private ActiveHouseholdAccess access() {
        return new ActiveHouseholdAccess(
                7L,
                11L,
                31L,
                1L,
                "MEMBER",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                1L,
                "Asia/Shanghai");
    }

    private WechatUserIdentityEntity identity(String openid) {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(7L);
        identity.setOpenid(openid);
        return identity;
    }
}
