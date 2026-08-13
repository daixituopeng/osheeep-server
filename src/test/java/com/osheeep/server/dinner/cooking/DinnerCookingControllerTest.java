package com.osheeep.server.dinner.cooking;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.cooking.dto.AddCookingDishRequest;
import com.osheeep.server.dinner.cooking.dto.CookingDishResponse;
import com.osheeep.server.dinner.cooking.dto.CookingMethodSnapshotResponse;
import com.osheeep.server.dinner.cooking.dto.CookingSessionResponse;
import com.osheeep.server.dinner.cooking.dto.StartCookingRequest;
import com.osheeep.server.dinner.cooking.dto.UpdateCookingDishCompletionRequest;
import com.osheeep.server.dinner.household.dto.HouseholdActorResponse;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
class DinnerCookingControllerTest {

    private static final String START_KEY =
            "00000000-0000-4000-8000-000000000101";
    private static final String ADD_KEY =
            "00000000-0000-4000-8000-000000000102";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerCookingService cookingService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(cookingService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void cookingEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/menus/today/cooking"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void readsTheCompleteFrozenCookingWireIncludingRecordId() throws Exception {
        when(cookingService.get(7L)).thenReturn(session("COMPLETED", 10L, 91L));

        mockMvc.perform(authenticated(get("/api/dinner/menus/today/cooking")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuId").value(31))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.recordId").value(91))
                .andExpect(jsonPath("$.data.dishes[0].origin").value("TEMPORARY"))
                .andExpect(jsonPath("$.data.dishes[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data.dishes[0].recipeVersion").value(8))
                .andExpect(jsonPath("$.data.dishes[0].method.estimatedMinutes").value(10))
                .andExpect(jsonPath("$.data.dishes[0].method.steps[0].instruction")
                        .value("翻炒至熟"))
                .andExpect(jsonPath("$.data.dishes[0].ingredients[0].name")
                        .value("鸡蛋"))
                .andExpect(jsonPath("$.data.dishes[0].selectedBy[0].kind").value("ME"))
                .andExpect(jsonPath("$.data.dishes[0].addedBy.kind").value("ME"))
                .andExpect(jsonPath("$.data.dishes[0].completedBy.kind")
                        .value("PARTNER"))
                .andExpect(jsonPath("$.data.dishes[0].sortOrder").value(0));
    }

    @Test
    void startsAddsAndUpdatesDishCompletionWithExactRequestModels() throws Exception {
        when(cookingService.start(
                7L, new StartCookingRequest(5L, START_KEY)))
                .thenReturn(session("COOKING", 6L, null));
        when(cookingService.addDish(
                7L, new AddCookingDishRequest(14L, 21L, 6L, ADD_KEY)))
                .thenReturn(session("COOKING", 7L, null));
        when(cookingService.setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(true, 7L)))
                .thenReturn(session("COOKING", 8L, null));

        mockMvc.perform(authenticated(post("/api/dinner/menus/today/cooking/start"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"idempotencyKey\":\"" + START_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(6));
        mockMvc.perform(authenticated(post("/api/dinner/menus/today/cooking/dishes"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipeId":14,"methodId":21,"version":6,
                                 "idempotencyKey":"%s"}
                                """.formatted(ADD_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7));
        mockMvc.perform(authenticated(put(
                        "/api/dinner/menus/today/cooking/dishes/101/completion"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true,\"version\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8));

        verify(cookingService).start(7L, new StartCookingRequest(5L, START_KEY));
        verify(cookingService).addDish(
                7L, new AddCookingDishRequest(14L, 21L, 6L, ADD_KEY));
        verify(cookingService).setCompleted(
                7L, 101L, new UpdateCookingDishCompletionRequest(true, 7L));
    }

    @Test
    void validatesUuidAndMapsCookingConflicts() throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/menus/today/cooking/start"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"idempotencyKey\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        when(cookingService.get(7L)).thenThrow(
                new BusinessException(ErrorCode.DINNER_MENU_NOT_COOKING));
        mockMvc.perform(authenticated(get("/api/dinner/menus/today/cooking")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_MENU_NOT_COOKING"));
    }

    private CookingSessionResponse session(
            String status,
            Long version,
            Long recordId
    ) {
        return new CookingSessionResponse(
                31L, LocalDate.of(2026, 8, 13), status, version, recordId,
                List.of(new CookingDishResponse(
                        101L, 14L, "番茄炒蛋",
                        "https://www.osheeep.com/media/recipes/family.webp",
                        "家常菜", "酸甜", 10, "HOUSEHOLD", 8L, 2,
                        new CookingMethodSnapshotResponse(
                                21L, "家常做法", "炒", 10,
                                List.of(new RecordMethodStepSnapshotResponse(
                                        "翻炒至熟", 0))),
                        List.of(new RecordIngredientSnapshotResponse(
                                201L, "鸡蛋", new BigDecimal("2.000"),
                                "枚", true, 0)),
                        "TEMPORARY",
                        List.of(new HouseholdActorResponse("ME")),
                        new HouseholdActorResponse("ME"), true,
                        new HouseholdActorResponse("PARTNER"),
                        Instant.parse("2026-08-13T11:00:00Z"), 0)));
    }

    private MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
