package com.osheeep.server.dinner.subscription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatAccessTokenProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class WechatSubscriptionMessageClient
        implements WechatSubscriptionMessageGateway {

    private static final int INVALID_CREDENTIAL = 40001;
    private static final int INVALID_ACCESS_TOKEN = 40014;
    private static final Logger log =
            LoggerFactory.getLogger(WechatSubscriptionMessageClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider tokenProvider;

    public WechatSubscriptionMessageClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider tokenProvider
    ) {
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public WechatSubscriptionSendResult send(WechatSubscriptionMessage message) {
        String token = currentToken();
        SendResponse response = request(token, message);
        if (invalidToken(response)) {
            tokenProvider.invalidate(token);
            String refreshedToken = currentToken();
            response = request(refreshedToken, message);
            if (invalidToken(response)) {
                tokenProvider.invalidate(refreshedToken);
            }
        }
        if (response == null || response.errcode() == null) {
            throw new WechatSubscriptionTransportException();
        }
        return new WechatSubscriptionSendResult(response.errcode());
    }

    private String currentToken() {
        try {
            return tokenProvider.currentToken();
        } catch (RuntimeException exception) {
            log.warn(
                    "WeChat subscription token unavailable, exception={}",
                    exception.getClass().getSimpleName());
            throw new WechatSubscriptionTransportException();
        }
    }

    private SendResponse request(
            String token,
            WechatSubscriptionMessage message
    ) {
        try {
            String body = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/cgi-bin/message/subscribe/send")
                            .queryParam("access_token", token)
                            .build())
                    .body(toRequest(message))
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new WechatSubscriptionTransportException();
            }
            return objectMapper.readValue(body, SendResponse.class);
        } catch (RestClientException exception) {
            log.warn(
                    "WeChat subscription request failed, exception={}, rootCause={}, status={}",
                    exception.getClass().getSimpleName(),
                    rootCauseClass(exception),
                    responseStatus(exception));
            throw new WechatSubscriptionTransportException();
        } catch (JsonProcessingException exception) {
            log.warn(
                    "WeChat subscription response parsing failed, exception={}",
                    exception.getClass().getSimpleName());
            throw new WechatSubscriptionTransportException();
        }
    }

    private String responseStatus(RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().toString();
        }
        return "unavailable";
    }

    private String rootCauseClass(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private SendRequest toRequest(WechatSubscriptionMessage message) {
        Map<String, TemplateValue> data = new LinkedHashMap<>();
        message.data().forEach((key, value) ->
                data.put(key, new TemplateValue(value)));
        return new SendRequest(
                message.openid(),
                message.templateId(),
                message.page(),
                data,
                message.miniprogramState(),
                "zh_CN");
    }

    private boolean invalidToken(SendResponse response) {
        return response != null
                && response.errcode() != null
                && (response.errcode() == INVALID_CREDENTIAL
                || response.errcode() == INVALID_ACCESS_TOKEN);
    }

    private record TemplateValue(String value) {
    }

    private record SendRequest(
            @JsonProperty("touser") String toUser,
            @JsonProperty("template_id") String templateId,
            String page,
            Map<String, TemplateValue> data,
            @JsonProperty("miniprogram_state") String miniprogramState,
            String lang
    ) {

        @Override
        public String toString() {
            return "SendRequest[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SendResponse(Integer errcode, String errmsg) {
    }
}
