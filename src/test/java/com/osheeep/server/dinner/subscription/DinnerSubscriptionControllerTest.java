package com.osheeep.server.dinner.subscription;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionActionResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionConfigResponse;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultItemRequest;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionResultRequest;
import com.osheeep.server.dinner.subscription.dto.DinnerSubscriptionTemplateResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerSubscriptionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerSubscriptionService service;

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/subscriptions/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void exposesRuntimeTemplateIdsAndRecordsOnlyControlledResults() throws Exception {
        when(service.config(7L)).thenReturn(new DinnerSubscriptionConfigResponse(
                List.of(new DinnerSubscriptionActionResponse(
                        "HOUSEHOLD_INVITE_READY",
                        List.of(new DinnerSubscriptionTemplateResponse(
                                "PARTNER_JOINED", "runtime-template"))))));

        mockMvc.perform(authenticated(get("/api/dinner/subscriptions/config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].action")
                        .value("HOUSEHOLD_INVITE_READY"))
                .andExpect(jsonPath("$.data.actions[0].templates[0].templateId")
                        .value("runtime-template"));

        UUID requestId =
                UUID.fromString("00000000-0000-4000-8000-000000000016");
        mockMvc.perform(authenticated(post("/api/dinner/subscriptions/results"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"00000000-0000-4000-8000-000000000016",
                                  "action":"HOUSEHOLD_INVITE_READY",
                                  "results":[
                                    {
                                      "scenario":"PARTNER_JOINED",
                                      "outcome":"ACCEPT"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).recordResults(
                7L,
                new DinnerSubscriptionResultRequest(
                        requestId,
                        "HOUSEHOLD_INVITE_READY",
                        List.of(new DinnerSubscriptionResultItemRequest(
                                "PARTNER_JOINED", "ACCEPT"))));
    }

    @Test
    void rejectsEmptyOrOversizedNativeResultsBeforeTheService() throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/subscriptions/results"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"00000000-0000-4000-8000-000000000016",
                                  "action":"MENU_CONFIRMED",
                                  "results":[]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
