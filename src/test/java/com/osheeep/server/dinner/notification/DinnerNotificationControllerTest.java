package com.osheeep.server.dinner.notification;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationItemResponse;
import com.osheeep.server.dinner.notification.dto.DinnerNotificationPageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerNotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerNotificationService service;

    private String token;

    @BeforeEach
    void setUp() {
        reset(service);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void notificationEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void listsACursorPageAndUnreadCount() throws Exception {
        when(service.page(7L, 105L, 20)).thenReturn(
                new DinnerNotificationPageResponse(
                        List.of(new DinnerNotificationItemResponse(
                                104L,
                                "PARTNER_SELECTION_UPDATED",
                                "TA 更新了今晚选择",
                                "看看合并后的菜单，准备好后再确认",
                                "TONIGHT",
                                false,
                                Instant.parse("2026-07-23T10:00:00Z"))),
                        3L,
                        104L));
        when(service.unreadCount(7L)).thenReturn(3L);

        mockMvc.perform(authenticated(get("/api/dinner/notifications")
                        .queryParam("beforeId", "105")
                        .queryParam("limit", "20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(104))
                .andExpect(jsonPath("$.data.items[0].target").value("TONIGHT"))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.unreadCount").value(3))
                .andExpect(jsonPath("$.data.nextBeforeId").value(104));
        mockMvc.perform(authenticated(get("/api/dinner/notifications/unread-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));
    }

    @Test
    void rejectsUnsafeCursorAndLimitBeforeCallingTheService() throws Exception {
        mockMvc.perform(authenticated(get("/api/dinner/notifications")
                        .queryParam("beforeId", "0")
                        .queryParam("limit", "51")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void exposesIdempotentReadActions() throws Exception {
        when(service.markAllRead(7L)).thenReturn(4);

        mockMvc.perform(authenticated(put("/api/dinner/notifications/104/read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(authenticated(put("/api/dinner/notifications/read-all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(4));

        verify(service).markRead(7L, 104L);
        verify(service).markAllRead(7L);
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
