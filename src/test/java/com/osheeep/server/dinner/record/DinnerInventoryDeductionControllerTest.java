package com.osheeep.server.dinner.record;

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
import com.osheeep.server.dinner.record.dto.HandleInventoryDeductionRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionItemRequest;
import com.osheeep.server.dinner.record.dto.InventoryDeductionProposalItemResponse;
import com.osheeep.server.dinner.record.dto.InventoryDeductionResponse;
import java.math.BigDecimal;
import java.util.List;
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
class DinnerInventoryDeductionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerInventoryDeductionService service;

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/records/91/inventory-deduction"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/dinner/records/91/inventory-deduction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"SKIP",
                                  "idempotencyKey":"00000000-0000-4000-8000-000000000020",
                                  "items":[]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsTheProposalAndAppliesOnlyValidatedItems() throws Exception {
        when(service.get(7L, 91L)).thenReturn(new InventoryDeductionResponse(
                91L,
                "PENDING",
                null,
                null,
                List.of(new InventoryDeductionProposalItemResponse(
                        1L,
                        "番茄",
                        new BigDecimal("2.000"),
                        "个",
                        true,
                        new BigDecimal("3.000"),
                        "个",
                        4L,
                        new BigDecimal("2.000"),
                        true,
                        "READY")),
                List.of()));
        HandleInventoryDeductionRequest request =
                new HandleInventoryDeductionRequest(
                        "APPLY",
                        "00000000-0000-4000-8000-000000000019",
                        List.of(new InventoryDeductionItemRequest(
                                1L, new BigDecimal("2"), 4L)));
        when(service.handle(7L, 91L, request)).thenReturn(
                new InventoryDeductionResponse(
                        91L, "APPLIED", null, null, List.of(), List.of()));

        mockMvc.perform(authenticated(
                        get("/api/dinner/records/91/inventory-deduction")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.proposalItems[0].ingredientId").value(1))
                .andExpect(jsonPath("$.data.proposalItems[0].recipeUnit").value("个"))
                .andExpect(jsonPath("$.data.proposalItems[0].inventoryUnit").value("个"))
                .andExpect(jsonPath("$.data.proposalItems[0].suggestedQuantity").value(2.0))
                .andExpect(jsonPath("$.data.proposalItems[0].eligibility").value("READY"));

        mockMvc.perform(authenticated(
                        post("/api/dinner/records/91/inventory-deduction"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"APPLY",
                                  "idempotencyKey":"00000000-0000-4000-8000-000000000019",
                                  "items":[{
                                    "ingredientId":1,
                                    "quantity":2,
                                    "inventoryVersion":4
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));

        verify(service).get(7L, 91L);
        verify(service).handle(7L, 91L, request);
    }

    @Test
    void rejectsMalformedActionsKeysAndItemsBeforeTheService() throws Exception {
        mockMvc.perform(authenticated(
                        post("/api/dinner/records/91/inventory-deduction"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"UNDO",
                                  "idempotencyKey":"not-a-uuid",
                                  "items":[{
                                    "ingredientId":1,
                                    "quantity":0,
                                    "inventoryVersion":0
                                  }]
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
