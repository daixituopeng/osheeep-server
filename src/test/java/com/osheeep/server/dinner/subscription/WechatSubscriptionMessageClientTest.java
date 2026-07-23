package com.osheeep.server.dinner.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatAccessTokenProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class WechatSubscriptionMessageClientTest {

    private MockRestServiceServer server;
    private WechatAccessTokenProvider tokenProvider;
    private WechatSubscriptionMessageClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenProvider = mock(WechatAccessTokenProvider.class);
        client = new WechatSubscriptionMessageClient(
                builder, new ObjectMapper(), tokenProvider);
    }

    @Test
    void sendsTheOfficialServerPayloadWithoutInventingAMessageId() {
        when(tokenProvider.currentToken()).thenReturn("token-1");
        server.expect(once(), requestTo(
                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send"
                                + "?access_token=token-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "touser":"openid-8",
                          "template_id":"template-runtime",
                          "page":"pages/tonight/index",
                          "data":{
                            "thing1":{"value":"今晚菜单需要重新确认"},
                            "time2":{"value":"2026-07-23 18:00"},
                            "thing3":{"value":"TA 修改了选择"}
                          },
                          "miniprogram_state":"formal",
                          "lang":"zh_CN"
                        }
                        """, true))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        WechatSubscriptionSendResult result = client.send(message());

        assertThat(result.errorCode()).isZero();
        server.verify();
    }

    @Test
    void refreshesAnInvalidAccessTokenOnlyOnce() {
        when(tokenProvider.currentToken()).thenReturn("token-1", "token-2");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send"
                                + "?access_token=token-1"))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send"
                                + "?access_token=token-2"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.send(message()).errorCode()).isZero();
        verify(tokenProvider).invalidate("token-1");
        server.verify();
    }

    @Test
    void transportFailureIsRedactedAndRecoverable(CapturedOutput output) {
        when(tokenProvider.currentToken()).thenReturn("secret-token");
        server.expect(requestTo(
                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send"
                                + "?access_token=secret-token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.send(message()))
                .isInstanceOf(WechatSubscriptionTransportException.class)
                .hasMessage("WeChat subscription delivery is temporarily unavailable");
        assertThat(output).doesNotContain(
                "secret-token", "openid-8", "template-runtime");
        server.verify();
    }

    private WechatSubscriptionMessage message() {
        return new WechatSubscriptionMessage(
                "openid-8",
                "template-runtime",
                "pages/tonight/index",
                Map.of(
                        "thing1", "今晚菜单需要重新确认",
                        "time2", "2026-07-23 18:00",
                        "thing3", "TA 修改了选择"),
                "formal");
    }
}
