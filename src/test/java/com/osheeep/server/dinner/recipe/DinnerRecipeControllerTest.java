package com.osheeep.server.dinner.recipe;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeDetailResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMatchResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodOptionResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.dto.HouseholdRecipePreference;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceResponse;
import com.osheeep.server.dinner.recipe.dto.RecipePreferenceValue;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipePreferenceRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerRecipeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerRecipeService recipeService;
    @MockitoBean private DinnerRecipePreferenceService preferenceService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(recipeService);
        reset(preferenceService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void recipeDiscoveryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/recipes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void defaultsTemporarySetsToEmptyAndPreservesExpandedRecipeContract() throws Exception {
        when(recipeService.discover(7L, Set.of(), Set.of(), false))
                .thenReturn(List.of(response()));

        mockMvc.perform(authenticated(get("/api/dinner/recipes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data[0].imagePath").value("/assets/recipes/tomato-eggs.jpg"))
                .andExpect(jsonPath("$.data[0].category").value("家常菜"))
                .andExpect(jsonPath("$.data[0].flavor").value("酸甜"))
                .andExpect(jsonPath("$.data[0].estimatedMinutes").value(10))
                .andExpect(jsonPath("$.data[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data[0].version").value(8))
                .andExpect(jsonPath("$.data[0].defaultMethod.id").value(21))
                .andExpect(jsonPath("$.data[0].defaultMethod.name").value("家常做法"))
                .andExpect(jsonPath("$.data[0].defaultMethod.cookingStyle").value("炒"))
                .andExpect(jsonPath("$.data[0].defaultMethod.steps").doesNotExist())
                .andExpect(jsonPath("$.data[0].ingredients[0].ingredientId").value(101))
                .andExpect(jsonPath("$.data[0].ingredients[0].name").value("番茄"))
                .andExpect(jsonPath("$.data[0].ingredients[0].quantity").value(2.000))
                .andExpect(jsonPath("$.data[0].ingredients[0].unit").value("个"))
                .andExpect(jsonPath("$.data[0].ingredients[0].required").value(true))
                .andExpect(jsonPath("$.data[0].ingredients[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data[0].match.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].match.matchPercent").value(100))
                .andExpect(jsonPath("$.data[0].preference.myPreference").value("NEUTRAL"))
                .andExpect(jsonPath("$.data[0].preference.myVersion").value(0))
                .andExpect(jsonPath("$.data[0].preference.householdPreference")
                        .value("NEUTRAL"));
        verify(recipeService).discover(7L, Set.of(), Set.of(), false);
    }

    @Test
    void passesTemporaryIngredientSetsAndOnlyCookableToDiscovery() throws Exception {
        when(recipeService.discover(7L, Set.of(101L, 102L), Set.of(103L), true))
                .thenReturn(List.of());

        mockMvc.perform(authenticated(get("/api/dinner/recipes")
                        .queryParam("includeIngredientIds", "101", "102")
                        .queryParam("excludeIngredientIds", "103")
                        .queryParam("onlyCookable", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(recipeService).discover(7L, Set.of(101L, 102L), Set.of(103L), true);
    }

    @Test
    void returnsTheConsumerRecipeDetailWithAllMethods() throws Exception {
        RecipeDetailResponse detail = new RecipeDetailResponse(
                14L, "番茄炒蛋", "/assets/recipes/tomato-eggs.jpg",
                "家常菜", "酸甜", 2, 10, "HOUSEHOLD", 8L,
                List.of(new RecipeIngredientResponse(
                        101L, "番茄", new BigDecimal("2"), "个", true, 0)),
                new RecipeMatchResponse(
                        "AVAILABLE", 1, 1, 100, List.of(), List.of()),
                List.of(
                        new RecipeMethodOptionResponse(
                                21L, "家常做法", "炒", 10, true,
                                List.of(new RecipeMethodStepResponse("翻炒", 0))),
                        new RecipeMethodOptionResponse(
                                22L, "少油版", "煎", 12, false,
                                List.of(new RecipeMethodStepResponse("慢煎", 0)))));
        when(recipeService.detail(7L, 14L)).thenReturn(detail);

        mockMvc.perform(authenticated(get("/api/dinner/recipes/14/view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(14))
                .andExpect(jsonPath("$.data.servings").value(2))
                .andExpect(jsonPath("$.data.methods[0].defaultMethod").value(true))
                .andExpect(jsonPath("$.data.methods[0].steps[0].instruction")
                        .value("翻炒"))
                .andExpect(jsonPath("$.data.methods[1].name").value("少油版"));
        verify(recipeService).detail(7L, 14L);
    }

    @Test
    void writesAnAuthenticatedPreferenceWithTheExactClientVersion() throws Exception {
        when(preferenceService.update(
                7L,
                14L,
                new UpdateRecipePreferenceRequest(RecipePreferenceValue.LIKE, 2L)))
                .thenReturn(new RecipePreferenceResponse(
                        RecipePreferenceValue.LIKE,
                        3L,
                        HouseholdRecipePreference.BOTH_LIKE));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/14/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"LIKE\",\"version\":2}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myPreference").value("LIKE"))
                .andExpect(jsonPath("$.data.myVersion").value(3))
                .andExpect(jsonPath("$.data.householdPreference").value("BOTH_LIKE"));

        verify(preferenceService).update(
                7L,
                14L,
                new UpdateRecipePreferenceRequest(RecipePreferenceValue.LIKE, 2L));
    }

    @Test
    void preferenceWriteRequiresAValidEnumAndVersion() throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/recipes/14/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"LOVE\",\"version\":-1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private RecipeResponse response() {
        return new RecipeResponse(
                1L,
                "番茄炒蛋",
                "/assets/recipes/tomato-eggs.jpg",
                "家常菜",
                "酸甜",
                10,
                "HOUSEHOLD",
                8L,
                new RecipeMethodSummaryResponse(21L, "家常做法", "炒"),
                List.of(new RecipeIngredientResponse(
                        101L, "番茄", new BigDecimal("2.000"), "个", true, 1)),
                new RecipeMatchResponse("AVAILABLE", 1, 1, 100, List.of(), List.of()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
