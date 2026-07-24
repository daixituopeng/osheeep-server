package com.osheeep.server.dinner.ingredient;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import java.text.Normalizer;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DinnerHouseholdIngredientService {

    private static final int MAX_NAME_CODE_POINTS = 20;
    private static final Set<String> CATEGORIES = Set.of(
            "蔬菜", "蛋奶", "肉类", "主食", "干货",
            "调味料", "水果", "豆制品", "饮品", "其他");
    private static final Set<String> UNITS = Set.of(
            "克", "千克", "毫升", "升", "个", "枚", "根", "颗", "片",
            "块", "盒", "袋", "瓶", "罐", "把", "份", "只", "瓣");

    private final DinnerHouseholdAccessService accessService;
    private final WechatUserIdentityMapper identityMapper;
    private final DinnerTextSafetyGateway textSafetyGateway;
    private final DinnerHouseholdIngredientTransaction transaction;

    public DinnerHouseholdIngredientService(
            DinnerHouseholdAccessService accessService,
            WechatUserIdentityMapper identityMapper,
            DinnerTextSafetyGateway textSafetyGateway,
            DinnerHouseholdIngredientTransaction transaction
    ) {
        this.accessService = accessService;
        this.identityMapper = identityMapper;
        this.textSafetyGateway = textSafetyGateway;
        this.transaction = transaction;
    }

    public IngredientResponse create(
            Long userId,
            String rawName,
            String rawCategory,
            String rawDefaultUnit
    ) {
        accessService.requireActiveHousehold(userId);
        String name = normalizeName(rawName);
        String category = requireVocabularyValue(rawCategory, CATEGORIES);
        String defaultUnit = requireVocabularyValue(rawDefaultUnit, UNITS);
        moderate(userId, name);
        return transaction.create(userId, name, category, defaultUnit);
    }

    private void moderate(Long userId, String name) {
        WechatUserIdentityEntity identity = identityMapper.selectByUserId(userId);
        if (identity == null
                || identity.getOpenid() == null
                || identity.getOpenid().isBlank()) {
            throw moderationUnavailable();
        }

        DinnerTextSafetyResult result;
        try {
            result = textSafetyGateway.check(identity.getOpenid(), name, name);
        } catch (DinnerTextSafetyUnavailableException exception) {
            throw moderationUnavailable();
        }
        if (result == DinnerTextSafetyResult.REJECT) {
            throw new BusinessException(ErrorCode.DINNER_INGREDIENT_NAME_REJECTED);
        }
        if (result != DinnerTextSafetyResult.PASS) {
            throw moderationUnavailable();
        }
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            throw invalid();
        }
        requireWellFormedUtf16(rawName);
        String normalized = trimUnicodeWhitespace(
                Normalizer.normalize(rawName, Normalizer.Form.NFC));
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount < 1 || codePointCount > MAX_NAME_CODE_POINTS) {
            throw invalid();
        }
        normalized.codePoints().forEach(this::requireVisibleCodePoint);
        return normalized;
    }

    private String requireVocabularyValue(String rawValue, Set<String> allowed) {
        if (rawValue == null) {
            throw invalid();
        }
        String value = trimUnicodeWhitespace(
                Normalizer.normalize(rawValue, Normalizer.Form.NFC));
        if (!allowed.contains(value)) {
            throw invalid();
        }
        return value;
    }

    private void requireWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalid();
            }
        }
    }

    private String trimUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isTrimmableWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isTrimmableWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private boolean isTrimmableWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private void requireVisibleCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR, "Household ingredient is invalid");
    }

    private BusinessException moderationUnavailable() {
        return new BusinessException(
                ErrorCode.DINNER_INGREDIENT_MODERATION_UNAVAILABLE);
    }
}
