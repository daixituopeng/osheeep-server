package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeArchiveServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;

    @Test
    void archivesPublishedRecipeAndPreservesRevisionWorkAsStandaloneDrafts() {
        DinnerRecipeArchiveService service =
                new DinnerRecipeArchiveService(recipeMapper, authorizer, queryService);
        DinnerRecipeEntity published = recipe(101L, "PUBLISHED", 8L, 70L);
        DinnerRecipeEntity revision = recipe(201L, "DRAFT", 3L, 70L);
        revision.setCreatorId(8L);
        revision.setRevisionOfRecipeId(101L);
        revision.setBasePublishedVersion(8L);
        RecipeAccess access = new RecipeAccess(7L, 70L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(published);
        when(recipeMapper.selectRevisionDraftsForUpdate(101L))
                .thenReturn(List.of(revision));
        when(queryService.detail(access, 101L)).thenReturn(response(101L, "ARCHIVED", 9L));

        RecipeDraftResponse result = service.archive(7L, 101L, 8L);

        assertThat(result.status()).isEqualTo("ARCHIVED");
        assertThat(published.getArchivedAt()).isNotNull();
        assertThat(published.getVersion()).isEqualTo(9L);
        assertThat(revision.getRevisionOfRecipeId()).isNull();
        assertThat(revision.getBasePublishedVersion()).isNull();
        assertThat(revision.getSourceRecipeId()).isEqualTo(101L);
        assertThat(revision.getVersion()).isEqualTo(4L);
        verify(recipeMapper).updateById(revision);
        verify(recipeMapper).updateById(published);
    }

    @Test
    void staleArchiveVersionDoesNotTouchRevisions() {
        DinnerRecipeArchiveService service =
                new DinnerRecipeArchiveService(recipeMapper, authorizer, queryService);
        RecipeAccess access = new RecipeAccess(7L, 70L);
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(access);
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(recipe(101L, "PUBLISHED", 9L, 70L));

        assertThatThrownBy(() -> service.archive(7L, 101L, 8L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verify(recipeMapper, never()).selectRevisionDraftsForUpdate(any());
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    private DinnerRecipeEntity recipe(Long id, String status, Long version, Long householdId) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setScope("HOUSEHOLD");
        recipe.setStatus(status);
        recipe.setVersion(version);
        recipe.setHouseholdId(householdId);
        recipe.setCreatorId(7L);
        recipe.setLastModifiedBy(7L);
        return recipe;
    }

    private RecipeDraftResponse response(Long id, String status, Long version) {
        return new RecipeDraftResponse(
                id, status, version, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(), null, null, List.of(), null);
    }
}
