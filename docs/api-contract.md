# API Contract

Base URL: `http://localhost:8080`

## Response Envelope

All `/api/**` endpoints return this shape:

```json
{
  "success": true,
  "data": {},
  "message": "OK",
  "errorCode": null,
  "requestId": "..."
}
```

For failures, `success` is `false` and `errorCode` is one of the documented common or business-specific codes. `data` is normally omitted; `DINNER_RECIPE_VALIDATION_FAILED` uses it for structured field issues. Successful responses with no data also omit `data`.

## Authentication

Public endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/wechat`
- `GET /actuator/health`
- `GET /media/recipes/**` (read-only, self-hosted approved recipe derivatives)
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

All other `/api/**` endpoints require:

```http
Authorization: Bearer <accessToken>
```

## Auth And User

| Method | Path                 | Request body                                            | Response data  |
| ------ | -------------------- | ------------------------------------------------------- | -------------- |
| POST   | `/api/auth/register` | `email`, `username`, `password`, optional `displayName` | Login response |
| POST   | `/api/auth/login`    | `email`, `password`                                     | Login response |
| POST   | `/api/auth/wechat`   | `code` from `wx.login`                                  | Login response |
| POST   | `/api/auth/logout`   | None                                                    | `null`         |
| GET    | `/api/users/me`      | None                                                    | User profile   |
| POST   | `/api/users/me/deletion` | WeChat login `code` for identity re-verification    | `null`         |

Login response data:

```json
{
  "accessToken": "...",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "username": "osheeep",
    "nickname": "Osheeep",
    "avatarUrl": null
  }
}
```

Registration validation: `username` is 3-64 characters and `password` is 8-128 characters.

The WeChat endpoint exchanges the temporary code on the server. It never returns `openid`, `session_key`, or the AppSecret.

## Dinner Household

All household endpoints require a bearer token. V8 models membership as immutable history plus at most one `ACTIVE` row per user, exactly one active `OWNER` per household, and no more than two occupied active seats. The server derives the authenticated user, household and role from locked database state. `actorMembershipId`, target membership IDs and versions are untrusted optimistic-concurrency inputs only: no client-supplied user ID, household ID or role grants authority.

| Method | Path                                                | Request body | Response data |
| ------ | --------------------------------------------------- | ------------ | ------------- |
| GET    | `/api/dinner/household`                             | None | Household summary, or JSON `null` when unbound |
| GET    | `/api/dinner/household/members`                     | None | One management snapshot: `household`, ordered active `members`, `invite` |
| PUT    | `/api/dinner/household`                             | `name`, `expectedVersion` | Updated household summary |
| GET    | `/api/dinner/household/invite-code`                 | None | Current hash-only invite status |
| POST   | `/api/dinner/households`                            | Optional `name` | Household, one-time plaintext invite and expiry |
| POST   | `/api/dinner/households/invite-code/refresh`        | None | Household, replacement one-time plaintext invite and expiry |
| POST   | `/api/dinner/household/invite-code/revocation`      | None | Updated invite status |
| POST   | `/api/dinner/households/join`                       | `inviteCode` | Household summary |
| POST   | `/api/dinner/household/members/me/leave`            | `actorMembershipId`, `expectedVersion`, UUID v4 `idempotencyKey` | Mutation result |
| POST   | `/api/dinner/household/members/{membershipId}/removal` | `actorMembershipId`, `expectedVersion`, `targetMembershipVersion`, UUID v4 `idempotencyKey` | Mutation result |
| POST   | `/api/dinner/household/ownership-transfer`          | `actorMembershipId`, `expectedVersion`, `targetMembershipId`, `targetMembershipVersion`, UUID v4 `idempotencyKey` | Mutation result |
| POST   | `/api/dinner/household/dissolution`                 | `actorMembershipId`, `expectedVersion`, `householdName`, fresh WeChat `code`, UUID v4 `idempotencyKey` | Mutation result |

A household summary contains `id`, `name`, `timezone`, `memberCount`, `version`, `inviteRevision`, `myRole`, `myMembershipId`, and `myMembershipVersion`. A management member contains only `membershipId`, `version`, `role`, viewer-relative `relation` (`ME` or `PARTNER`), and UTC `joinedAt`; username, profile data and internal user ID are never returned. The management endpoint reads the household, member list and invite status in one repeatable-read snapshot.

Invite status is `{state, inviteRevision, expiresAt, createdByMe}`, where `state` is `ACTIVE`, `EXPIRED`, or `NONE`. Create/refresh adds `inviteCode` and repeats the resulting `inviteRevision` and UTC `inviteExpiresAt`. Plaintext is returned exactly once and is never recoverable from later reads; only its keyed digest is stored. New codes expire 24 hours after creation. V8 normalizes legacy open-code expiry to no later than 24 hours after creation, revokes every open invite for a two-member household, and retains at most one open invite per household. Joining consumes that invite atomically and advances both household and invite revisions; refresh, explicit revocation, membership change and dissolution invalidate applicable open codes.

Mutation responses are `{operationType, replayed, actorHasHousehold, householdVersion}`. Leave, remove, transfer and dissolution keys are retained for 14 days. Reusing the same UUID v4 with the same fingerprint replays the stored response, including after membership or household deletion; using it for a different request returns `DINNER_HOUSEHOLD_OPERATION_CONFLICT`. A member may leave; an owner must transfer ownership or dissolve. Removal, transfer and dissolution are owner-only. Dissolution also requires the exact current household name and a fresh WeChat identity matching the authenticated account.

Household errors are `DINNER_INVITE_INVALID`, `DINNER_INVITE_EXPIRED`, `DINNER_HOUSEHOLD_FULL`, `DINNER_ALREADY_IN_HOUSEHOLD`, `DINNER_HOUSEHOLD_REQUIRED`, `DINNER_HOUSEHOLD_VERSION_CONFLICT`, `DINNER_HOUSEHOLD_OWNER_REQUIRED`, `DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE`, `DINNER_HOUSEHOLD_MEMBER_NOT_FOUND`, `DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT`, `DINNER_HOUSEHOLD_NAME_MISMATCH`, `DINNER_HOUSEHOLD_NAME_REJECTED`, `DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE`, `DINNER_HOUSEHOLD_IDENTITY_MISMATCH`, and `DINNER_HOUSEHOLD_OPERATION_CONFLICT`, in addition to shared authentication and validation errors.

## Dinner Ingredients, Inventory, And Recipe Discovery

Flyway migration `V5__add_recipe_ingredients_and_household_inventory.sql` adds the standard ingredient catalog, recipe requirements, and per-household inventory. Ingredient and recipe requirement quantities use `DECIMAL(12,3)` and may be `null`; units are required. Inventory rows have their own optimistic `version` and record the last updating user and database-managed timestamp. Persisted inventory versions start at `1`; request version `0` is reserved exclusively for create-only intent. Inventory `DATETIME(3)` values are database-local `Asia/Shanghai` timestamps and are converted from that explicit zone to ISO-8601 UTC instants in API responses.

All routes in this section require a bearer token. The client never supplies a household ID: the service resolves the authenticated user's `ACTIVE` membership and uses its `householdId` for catalog visibility, inventory reads/writes, and recipe matching. Ingredient, inventory and recipe discovery require a valid active household context. A user without one receives HTTP 403 with `FORBIDDEN` and message `Access is denied`.

| Method | Path                                   | Request                                           | Response data                           |
| ------ | -------------------------------------- | ------------------------------------------------- | --------------------------------------- |
| GET    | `/api/dinner/ingredients`              | None                                              | Accessible active ingredient array      |
| POST   | `/api/dinner/ingredients`              | JSON body: `name`, `category`, `defaultUnit`      | Created household-scoped ingredient     |
| GET    | `/api/dinner/inventory`                | None                                              | Current household inventory array       |
| PUT    | `/api/dinner/inventory/{ingredientId}` | JSON body: nullable `quantity`, `unit`, `version` | Created or updated inventory item       |
| DELETE | `/api/dinner/inventory/{ingredientId}` | Required query parameter `version`                | No data                                 |
| GET    | `/api/dinner/recipes`                  | Optional query parameters described below         | Matched visible published recipe array  |
| GET    | `/api/dinner/recipes/{recipeId}/view`  | None                                              | Consumer recipe detail with all methods |
| PUT    | `/api/dinner/recipes/{recipeId}/preference` | `preference`, `version`                       | Current recipe preference summary       |

An ingredient response contains `id`, `name`, `category`, `defaultUnit`, and `scope`, where `scope` is `SYSTEM` or `HOUSEHOLD`. The catalog contains active system ingredients plus active household-scoped ingredients belonging to the current household, ordered by ID.

Both active members may create a household-scoped ingredient. `POST /api/dinner/ingredients` never accepts a household ID; the write transaction locks and revalidates the actor's active household context. The request follows these rules:

- `name` is Unicode NFC-normalized, trims leading/trailing Unicode whitespace, contains 1–20 code points, and rejects malformed UTF-16 plus control, format and line-separator code points.
- `category` is one of `蔬菜`, `蛋奶`, `肉类`, `主食`, `干货`, `调味料`, `水果`, `豆制品`, `饮品`, or `其他`.
- `defaultUnit` is one of `克`, `千克`, `毫升`, `升`, `个`, `枚`, `根`, `颗`, `片`, `块`, `盒`, `袋`, `瓶`, `罐`, `把`, `份`, `只`, or `瓣`.
- The normalized name must pass WeChat text safety before the write transaction starts. A rejection returns HTTP 422 `DINNER_INGREDIENT_NAME_REJECTED`; missing identity or unavailable moderation returns retryable HTTP 503 `DINNER_INGREDIENT_MODERATION_UNAVAILABLE`.
- A name already used by an active system ingredient or the current household returns HTTP 409 `DINNER_INGREDIENT_ALREADY_EXISTS`. The same stable conflict is returned for a concurrent household duplicate-key race.

Creation adds the ingredient to the shared catalog but does not create an inventory row. Quantity and the selected unit are saved separately through the existing versioned inventory PUT. Household ingredients remain with the household when one member leaves and are deleted with household-owned references during household dissolution or last-member account cleanup. Task 18 intentionally does not add rename or archive endpoints.

An inventory response contains `ingredientId`, `name`, `category`, nullable `quantity`, `unit`, `version`, `updatedBy`, and ISO-8601 `updatedAt`, ordered by inventory row ID. A `null` quantity means the household has the ingredient but has not confirmed its amount; it is not the same as deleting the row. `InventoryItemResponse.quantity` is serialized explicitly as JSON `null`. The `ApiResponse` wrapper's `NON_NULL` rule applies only to the wrapper's own fields and does not omit null fields from nested response records.

The PUT body follows these rules:

- `quantity` may be `null`; otherwise it must be nonnegative with at most 9 integer digits and 3 fractional digits.
- `unit` is required, nonblank, at most 16 characters, and is trimmed before persistence.
- `version` is required and nonnegative. Creating a missing inventory row requires exactly `version: 0` and returns persisted version `1`. If a row already exists, request version `0` always conflicts. Updating an existing row requires its exact persisted version of at least `1` and increments it exactly once.
- Only an active system ingredient or an active ingredient owned by the current household can be stocked.

DELETE also requires the exact current version. A stale PUT or DELETE returns HTTP 409, `DINNER_INVENTORY_VERSION_CONFLICT`, and message `Dinner inventory was updated by another member`. Concurrent PUT create races that surface as the inventory unique-key violation, and PUT lock-wait/deadlock acquisition failures, map to the same HTTP 409 conflict; unrelated database failures are not remapped. Deleting a row that does not exist returns HTTP 404, `DINNER_INVENTORY_ITEM_NOT_FOUND`, and message `Dinner inventory item was not found`. An inactive, unknown, or foreign-household ingredient on PUT returns HTTP 400, `DINNER_INGREDIENT_INVALID`, and message `Dinner ingredient is invalid`. Request validation failures return HTTP 400 with `VALIDATION_ERROR`.

`GET /api/dinner/recipes` accepts:

- `includeIngredientIds`: a repeated or comma-separated set of ingredient IDs temporarily treated as present when a matching recipe requirement is absent from inventory.
- `excludeIngredientIds`: a repeated or comma-separated set temporarily removed from effective inventory.
- `onlyCookable`: boolean, default `false`. When `true`, recipes with match status `MISSING` are removed, while both `AVAILABLE` and `UNKNOWN_QUANTITY` remain.

Temporary include/exclude values affect only this response and are never persisted. Existing household inventory takes precedence over include: include does not replace a known quantity or fix an insufficient quantity. Exclude is applied last, so it wins over both inventory and include when an ID appears in both sets.

Each recipe contains `id`, `name`, `imagePath`, `category`, `flavor`, and `estimatedMinutes`, plus:

- `scope`: `SYSTEM` or `HOUSEHOLD`.
- `version`: always `1` for a system recipe; the published aggregate version for a household recipe.
- `defaultMethod`: `null` for a system recipe; for a household recipe, the selected default-method summary `{id, name, cookingStyle}`.
- `ingredients`: ordered items with `ingredientId`, `name`, nullable `quantity`, `unit`, `required`, and `sortOrder`; `RecipeIngredientResponse.quantity` is serialized explicitly as JSON `null` when absent.
- `match`: `status`, `matchedRequired`, `totalRequired`, `matchPercent`, `missingIngredients`, and `unknownQuantityIngredients`.
- `preference`: `myPreference`, `myVersion`, and the privacy-safe relationship-level `householdPreference`.

Example household discovery item:

```json
{
  "id": 14,
  "name": "番茄鸡蛋焖饭",
  "imagePath": "https://www.osheeep.com/media/recipes/tomato-rice-list.webp",
  "category": "主食",
  "flavor": "家常",
  "estimatedMinutes": 25,
  "scope": "HOUSEHOLD",
  "version": 8,
  "defaultMethod": { "id": 21, "name": "家常焖炒", "cookingStyle": "焖炒" },
  "ingredients": [
    {
      "ingredientId": 1,
      "name": "番茄",
      "quantity": null,
      "unit": "个",
      "required": true,
      "sortOrder": 0
    }
  ],
  "match": {
    "status": "UNKNOWN_QUANTITY",
    "matchedRequired": 1,
    "totalRequired": 1,
    "matchPercent": 100,
    "missingIngredients": [],
    "unknownQuantityIngredients": ["番茄"]
  },
  "preference": {
    "myPreference": "LIKE",
    "myVersion": 2,
    "householdPreference": "BOTH_LIKE"
  }
}
```

Discovery includes published system recipes and valid published household recipes owned by the authenticated user's active household. A household recipe is omitted if its publishable aggregate has become invalid, if its required ingredient visibility is invalid, if it does not have exactly one valid active default method, or if its selected approved image cannot provide a self-hosted list URL. Drafts, archived recipes, and recipes from other households are never returned.

`GET /api/dinner/recipes/{recipeId}/view` applies the same published-system/current-household visibility boundary and returns `DINNER_RECIPE_NOT_FOUND` for a missing or invisible recipe. A visible recipe whose aggregate is no longer valid returns `DINNER_RECIPE_INVALID`. The detail contains the discovery identity, ordered ingredients and current inventory match, plus nullable `servings` and every active method ordered by default status and method ID. Each method contains `id`, `name`, `cookingStyle`, nullable `estimatedMinutes`, `defaultMethod`, and ordered `steps` with `instruction` and `sortOrder`. A valid household detail has exactly one active default method; system recipes may have no method.

Only required ingredients contribute to matching. Missing stock, insufficient known quantity, or a unit mismatch is `MISSING`. When matching-unit stock is present, a `null` recipe requirement quantity, a `null` stock quantity, or both contributes to `matchedRequired` but produces `UNKNOWN_QUANTITY` and lists the ingredient in `unknownQuantityIngredients`. Complete required stock with both quantities known is `AVAILABLE`. Optional ingredients remain ignored. A recipe with only optional ingredients, or otherwise zero required ingredients, is `AVAILABLE` with `matchedRequired: 0`, `totalRequired: 0`, `matchPercent: 100`, `missingIngredients: []`, and `unknownQuantityIngredients: []`.

Each active member may independently set `LIKE`, `NEUTRAL`, or `DISLIKE` for a visible `SYSTEM + PUBLISHED` recipe or a `HOUSEHOLD + PUBLISHED` recipe owned by the current household:

```json
{ "preference": "LIKE", "version": 0 }
```

Version `0` is create-only intent and stores version `1`. Later changes require the exact positive `myVersion` and increment once. A stale version, duplicate create race, or lock acquisition conflict returns HTTP 409 `DINNER_RECIPE_PREFERENCE_VERSION_CONFLICT`; the server does not replay the choice. A missing, draft, archived, malformed-system, or foreign-household recipe returns `DINNER_RECIPE_NOT_FOUND`.

`householdPreference` is one of `BOTH_LIKE`, `SOME_LIKE`, `NEUTRAL`, `MIXED`, `SOME_DISLIKE`, or `BOTH_DISLIKE`. It exposes no user or membership ID. `myPreference` defaults to `NEUTRAL` with `myVersion: 0` before this member has written a choice. Discovery loads all visible recipe preferences in one batch and orders recipes by match status (`AVAILABLE`, `UNKNOWN_QUANTITY`, `MISSING`), descending `matchPercent`, household preference (`BOTH_LIKE`, `SOME_LIKE`, `NEUTRAL`, `MIXED`, `SOME_DISLIKE`, `BOTH_DISLIKE`), ascending `estimatedMinutes` with unknown duration last, and finally ascending recipe ID. With no stored preferences, the former time/ID order is unchanged.

Preferences belong to a concrete membership cycle and are not copied into menu selections or cooking records. Leaving or removal deletes the ended membership's preferences; rejoining starts empty. Household dissolution deletes all household preferences, and account deletion deletes all preferences owned by that account.

Flyway migration `V12__add_dinner_recipe_preferences.sql` creates the membership-cycle-bound preference table with an exact membership/recipe unique key, four parent foreign keys, the closed three-value check, and positive-version check. Local MySQL 8.0.45 verification on 2026-07-27 passed fresh, production-shaped V4, and current V11 paths through V12, including unique/value/version constraint rejection and ephemeral-catalog cleanup. This is local evidence only and does not imply a production V12 migration or deployment.

This remains backward compatible at the request boundary: `GET /api/dinner/recipes` keeps the same authenticated route, all query parameters default to the old no-filter call, and no original recipe response field was removed. `PUT /api/dinner/menus/today/selections` continues to accept the legacy `{recipeIds, version}` body and resolves each household recipe to its active default method. Method-aware clients send `{selections:[{recipeId,methodId}],version}` instead; recipe scope, selected recipe version, method ownership and active status are always validated by the server.

## Household Custom Recipe Vertical Slice

Flyway migration `V6__add_household_custom_recipes.sql` evolves recipes into versioned `DRAFT`, `PUBLISHED`, and `ARCHIVED` aggregates, adds one default method with ordered steps, and adds traceable image assets. Existing system recipes are migrated from `ACTIVE` to `PUBLISHED`; existing discovery and tonight-menu contracts continue to accept those system rows. V6 in source or a local test catalog does not imply that the migration has been applied to production.

On 2026-07-22, local verification passed `mvn test` with 438/438 tests. Ordinary `mvn test` does not select classes ending in `*IT`, so that result is separate from the explicit guarded MySQL 8 integration evidence: `DinnerHouseholdRecipeMenuMigrationMySqlIT` passed 1/1 and exercised fresh, production-shaped V4, and current V6 catalogs through V7; `DinnerCustomRecipeMySqlIT` passed 6/6 against V7. The migration test also verified the V4 legacy selection default and nullable legacy snapshot fields. The business test covered two authorized household users, discovery through completion, immutable history, tampered selection identities, unavailable approved-list image data, and full transaction rollback on a later snapshot insert failure. The runs used disposable loopback-only catalogs and the test harness's explicit environment/catalog safety gates. These are local test results only: they are not evidence that production Flyway ran, that a backend was deployed, or that a WeChat build was uploaded, submitted, or released.

To reproduce the guarded integration runs, start a disposable MySQL 8 server on a loopback address and create an empty disposable base catalog. Set the following values explicitly in the same process that launches Maven; do not source the development or production `.env.local` file:

```bash
export OSHEEEP_DB_HOST=127.0.0.1
export OSHEEEP_DB_PORT=33307
export OSHEEEP_DB_NAME=osheeep_it_v7
export OSHEEEP_DB_TEST_NAME=osheeep_it_v7
export OSHEEEP_ALLOW_EPHEMERAL_DATABASES=true
export OSHEEEP_DB_USERNAME=root
export OSHEEEP_DB_PASSWORD=osheeep-local-it-only

export OSHEEEP_REDIS_HOST=127.0.0.1
export OSHEEEP_REDIS_PORT=6379
export OSHEEEP_REDIS_PASSWORD=unused-local-it
export OSHEEEP_RABBITMQ_HOST=127.0.0.1
export OSHEEEP_RABBITMQ_PORT=5672
export OSHEEEP_RABBITMQ_USERNAME=unused-local-it
export OSHEEEP_RABBITMQ_PASSWORD=unused-local-it
export OSHEEEP_RABBITMQ_VHOST=/
export OSHEEEP_JWT_SECRET=local-it-only-secret-at-least-32-bytes
export OSHEEEP_WECHAT_APP_ID=local-it
export OSHEEEP_WECHAT_APP_SECRET=local-it
export OSHEEEP_IMAGE_PUBLIC_BASE_URL=http://127.0.0.1:8080

mvn test -Dtest=DinnerHouseholdRecipeMenuMigrationMySqlIT -Dspring.profiles.active=local
mvn test -Dtest=DinnerCustomRecipeMySqlIT -Dspring.profiles.active=local
mvn test
```

The raw `OSHEEEP_DB_NAME` and `OSHEEEP_DB_TEST_NAME` environment values must be identical; JVM `-D` overrides do not satisfy that trust anchor. For the migration IT, the base catalog must match `[A-Za-z0-9_]+`, be at most 15 characters, and be reached through `localhost`, `127.0.0.1`, or loopback IPv6. The opt-in value must be exactly `true`, `SELECT DATABASE()` must equal the base name, and the MySQL account must be able to create and drop the three run-specific ephemeral catalogs. The harness deletes those generated catalogs in `close()`. For the business IT, the `local` profile's datasource URL must resolve to the same base catalog, no separate `spring.flyway.url` may be set, and the base catalog must remain disposable because Flyway V1-V7 and the business fixtures write to it. The Redis, RabbitMQ, WeChat, and JWT values above only satisfy required local-profile configuration; the V7 IT does not treat them as evidence of reachable external services, and moderation is replaced with a local Mockito `PASS` result.

The integration test replaces `RecipeTextSafetyGateway` with a Mockito `PASS` result. It verifies that publication looks up the seeded active user's `openid`, passes that identity and the normalized recipe text to the gateway, and then completes the publication transaction. It does not call the real WeChat `msgSecCheck` service and is not evidence of external WeChat availability or acceptance.

All `/api/dinner/**` routes below require a bearer token. `GET /media/recipes/**` is the only public route in this feature and serves read-only derivatives bundled under the application's own origin.

| Method | Path                                      | Request                                  | Response data                         |
| ------ | ----------------------------------------- | ---------------------------------------- | ------------------------------------- |
| POST   | `/api/dinner/recipes/drafts`              | None                                     | New aggregate draft                   |
| GET    | `/api/dinner/recipes/family`              | Required `tab` query parameter           | Family recipe list                    |
| GET    | `/api/dinner/recipes/{id}`                | None                                     | Full visible recipe aggregate         |
| PUT    | `/api/dinner/recipes/{id}/basic-info`     | Versioned basic-info body                | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/ingredients`    | Versioned ingredient replacement         | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/default-method` | Versioned default-method replacement     | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/methods`        | Versioned complete active-method set     | Updated visible aggregate             |
| PUT    | `/api/dinner/recipes/{id}/image`          | `version`, nullable `imageAssetId`        | Updated aggregate draft               |
| POST   | `/api/dinner/recipes/{id}/publish`        | `version`                                | Published aggregate                   |
| POST   | `/api/dinner/recipes/{id}/copy`           | None                                     | New private household draft           |
| POST   | `/api/dinner/recipes/{id}/edit-drafts`    | None                                     | Current member's private revision     |
| POST   | `/api/dinner/recipes/{id}/archive`        | `version`                                | Archived household aggregate          |
| GET    | `/api/dinner/image-assets`                | Optional `query` query parameter          | Approved image metadata array         |

`tab` is exactly `PUBLISHED`, `DRAFT`, or `ARCHIVED`. Draft lists contain only the current user's drafts, including that user's private published-recipe revisions. Published and archived lists are scoped to the current active household. A draft is visible only to its creator; before publication or application, the other member receives HTTP 403 even when both users belong to the same household. A published or archived recipe is visible to either active member of that household. The server derives user and household IDs from the bearer token and never trusts either value from a request body.

Creating a draft returns version `1`. Every successful basic-info, ingredient, default-method, complete-method-set, or image write increments the aggregate version exactly once. Publication also increments once; for example, the first complete vertical slice progresses `1` (created), `2` (basic), `3` (ingredients), `4` (method), `5` (image), `6` (published). Every write must supply the exact current version. A stale version returns HTTP 409, `DINNER_RECIPE_VERSION_CONFLICT`, with message `Dinner recipe was updated elsewhere`; the server does not replay the write.

`PUT /api/dinner/recipes/{id}/methods` accepts the complete ordered active-method set:

```json
{
  "version": 8,
  "methods": [
    {
      "id": 201,
      "name": "家常炒",
      "cookingStyle": "炒",
      "estimatedMinutes": 15,
      "defaultMethod": true,
      "steps": [{ "instruction": "热锅炒熟" }]
    },
    {
      "id": null,
      "name": "少油焖",
      "cookingStyle": "焖",
      "estimatedMinutes": 22,
      "defaultMethod": false,
      "steps": [{ "instruction": "小火焖熟" }]
    }
  ]
}
```

The list contains 1–8 methods in display order and exactly one default. Names
are required, trimmed, case-insensitively unique, and at most 40 characters;
`cookingStyle` is required and at most 32 characters; duration is 1–1440
minutes; each method contains 1–12 nonblank steps of at most 160 characters.
An existing method ID must be active and belong to the same recipe. Every
currently active method ID must remain in the request: this first version adds,
edits, reorders and changes the default but deliberately does not delete a
method that may already be referenced by a menu.

The draft creator may use the route before publication. After publication,
either active member of the same household may use it. A published edit is sent
through WeChat text-content safety before the write; rejection returns
`DINNER_RECIPE_CONTENT_REJECTED`, and an unavailable check returns
`DINNER_RECIPE_MODERATION_UNAVAILABLE`. The transaction then re-locks the active
household context and recipe, rechecks the version and membership, updates the
full method set, makes the selected default's duration the recipe summary
duration, increments the aggregate version once and emits the existing family
recipe update notification. Archived recipes are not editable.

Basic-info body:

```json
{
  "version": 1,
  "name": "番茄炒蛋",
  "category": "家常菜",
  "flavor": "酸甜",
  "servings": 2,
  "estimatedMinutes": 15
}
```

Draft saves may remain incomplete. Nonblank publish values are limited to: name 40 characters; category and flavor 16 each; servings 1-20; estimated time 1-1440 minutes. Blank basic strings are stored as absent.

Ingredient replacement body:

```json
{
  "version": 2,
  "ingredients": [
    {
      "ingredientId": 1,
      "quantity": null,
      "unit": "克",
      "required": true
    }
  ]
}
```

The array replaces the entire step in request order and may contain at most 50 distinct, currently visible ingredients. `unit` is required and limited to 16 characters. `required` must be present. `quantity` may be `null`, meaning “适量”; otherwise it is nonnegative with at most 9 integer digits and 3 fractional digits. Publication requires at least one required ingredient.

Default-method replacement body:

```json
{
  "version": 3,
  "name": "家常炒",
  "cookingStyle": "炒",
  "steps": [
    { "instruction": "切好食材" },
    { "instruction": "热锅翻炒至熟" }
  ]
}
```

The body replaces the single default method and all its steps in request order. Method name is limited to 40 characters, cooking style to 32, and the array to 12 steps. A draft may save a blank method name, blank cooking style, or incomplete step text; publication requires a nonblank method name, a nonblank cooking style, and 1-12 nonblank steps, each within the same limits and at most 160 characters per instruction.

A recipe aggregate response contains `id`, `status`, `version`, nullable basic fields, `ingredients`, nullable `defaultMethod`, ordered `methods`, nullable `image`, `incompleteSteps`, and ISO-8601 `updatedAt`. A private revision additionally exposes `revisionOfRecipeId` and `basePublishedVersion`; both are `null` for an ordinary draft or shared aggregate. Ingredient items contain `ingredientId`, `name`, nullable `quantity`, `unit`, `required`, and zero-based `sortOrder`. The backward-compatible `defaultMethod` contains `id`, `name`, `cookingStyle`, and ordered `{instruction, sortOrder}` items. Each `methods` item additionally contains `estimatedMinutes`, `defaultMethod`, `sortOrder`, and its ordered steps.

Family list items contain `id`, `status`, nullable `name` and `imageUrl`, basic fields, `version`, privacy-safe `creator` and `lastModifier` actors shaped as `{kind}`, `completedStep`, `updatedAt`, and nullable `revisionOfRecipeId`. Actor `kind` uses the household relation values defined below (`ME`, current `PARTNER`, `EXITED_MEMBER`, or `DELETED_MEMBER`); creator or modifier IDs and names are never returned. List order is `updatedAt` descending, then recipe ID descending.

Image search returns only `APPROVED` assets. A response item exposes:

- `id`, `displayName`, self-hosted `listUrl` and `detailUrl`;
- `sourcePageUrl`, `author`, `licenseName`, `licenseUrl`, and `acquiredOn`;
- original `width` and `height`.

It never exposes the stored original object key or the third-party original-file URL, and the service never proxies a search result or hotlinks it at runtime. Selecting a missing or no-longer-approved asset returns HTTP 422 with `DINNER_RECIPE_IMAGE_INVALID`. Passing `imageAssetId: null` clears a draft's selection. Publication requires an approved asset.

Publication first loads and validates an immutable snapshot. Validation failures return HTTP 422 with `DINNER_RECIPE_VALIDATION_FAILED`; unlike ordinary errors, `data` is an ordered array of objects with stable `step`, `field`, and user-facing `message` values, for example:

```json
{
  "success": false,
  "errorCode": "DINNER_RECIPE_VALIDATION_FAILED",
  "message": "Dinner recipe is incomplete",
  "data": [
    { "step": "BASIC", "field": "name", "message": "请填写菜名" },
    {
      "step": "IMAGE",
      "field": "imageAssetId",
      "message": "请选择一张已审核真实图片"
    }
  ],
  "requestId": "..."
}
```

The normalized moderation content contains flavor, method name, cooking style, and ordered steps and may not exceed 2500 characters. The service looks up the publishing user's server-side WeChat identity: its `openid` must belong to that active mini-program user and is never accepted from the client. It calls WeChat `msgSecCheck` 2.0 with `scene=3` outside a database transaction. Only `result.suggest=pass` continues. `review` or `risky` returns HTTP 422 with `DINNER_RECIPE_CONTENT_REJECTED`; missing identity, missing configuration, platform error, timeout, or retry exhaustion returns HTTP 503 with `DINNER_RECIPE_MODERATION_UNAVAILABLE`. In every failure case the draft remains a draft.

After moderation passes, a short transaction re-locks the recipe and active household membership, revalidates the same expected version, completeness, and approved image, then atomically sets `PUBLISHED`, `publishedAt`, last modifier, and the next version. A change during moderation therefore returns the same 409 conflict instead of publishing stale text.

`POST /api/dinner/recipes/{systemRecipeId}/copy` requires a
`SYSTEM + PUBLISHED` source with no household owner and an active household
membership. Each explicit request creates a new version-`1`, current-user
private `HOUSEHOLD + DRAFT` in the active household. The transaction copies
basic information, ordered ingredients, every active method and its ordered
steps, records `sourceRecipeId`, and leaves revision fields empty. It never
copies the legacy system `imagePath`; `imageAssetId` is retained only when the
asset still resolves as approved, otherwise the draft image is empty and the
normal approved-library publish gate applies. A missing source returns
`DINNER_RECIPE_NOT_FOUND`; a non-system, unpublished, archived or
household-owned source returns `FORBIDDEN`.

`POST /api/dinner/recipes/{publishedId}/edit-drafts` requires a current-household
`PUBLISHED` recipe. It creates or returns the current user's single private
revision for that shared recipe. The revision starts at version `1`, records the
current published version as `basePublishedVersion`, and copies basic
information, ordered ingredients, approved image and all active methods. The two
members may therefore have independent revisions. Revision basic information,
ingredients and image use the existing versioned draft routes; default-method
and complete-method-set writes are rejected because published method variants
remain managed through the shared Task 21 route.

Publishing a revision uses the existing
`POST /api/dinner/recipes/{revisionDraftId}/publish` request. Moderation runs
before a short transaction that locks the revision and shared recipe in ascending
recipe-ID order. The transaction requires the exact revision version and the
unchanged published base version, then applies basic information, ingredients
and image to the shared recipe, synchronizes its default-method summary
duration, increments the shared version exactly once, deletes the applied
private revision and returns the shared aggregate. If either member or another
route changed the shared version, the request returns
`DINNER_RECIPE_VERSION_CONFLICT`; the private revision remains available and the
server does not replay it.

`POST /api/dinner/recipes/{publishedId}/archive` accepts `{ "version": 12 }`.
Either current active member may archive a current-household `PUBLISHED` recipe.
The exact version is required. The transaction sets `ARCHIVED`, records
`archivedAt`, increments the shared version once and converts every outstanding
private revision into an ordinary independent draft by clearing its revision
base. Those drafts and their content remain owned by their creators and may
later be published as new household recipes. There is no unarchive route.

V7 connects valid published household recipes to discovery, tonight-menu selection, and immutable cooking-record detail. The complete-method-set route supports adding and editing published household method variants; the copy, revision and archive routes use V6's reserved fields and statuses without a new migration.

Flyway migration `V7__connect_household_recipes_to_menus.sql` adds `recipe_version BIGINT NOT NULL DEFAULT 1` and nullable `method_id` to `dinner_menu_selections`; `method_id` is indexed and references `dinner_recipe_methods(id)`. Existing system-recipe selections therefore normalize to recipe version `1` with no method. It also adds nullable `recipe_scope`, `recipe_version`, `servings`, `method_id`, `method_name`, `cooking_style`, `method_steps` JSON, and `ingredients` JSON columns to `dinner_record_dish_snapshots`. The snapshot `method_id` intentionally has no foreign key to the live method table. All snapshot additions are nullable so pre-V7 record rows remain readable without rewriting historical data.

## Dinner Menu And Records

All menu and record endpoints require a bearer token and an `ACTIVE` household membership. `DinnerMenuService` operations and `DinnerRecordService.complete` additionally lock or validate the active household aggregate. Record list and detail scope results by that active membership's `householdId` and `historyVisibleFrom`. The server derives the household and current user from the token.

| Method | Path                                 | Request body                        | Response data                    |
| ------ | ------------------------------------ | ----------------------------------- | -------------------------------- |
| GET    | `/api/dinner/menus/today`            | None                                                     | Today's merged menu              |
| PUT    | `/api/dinner/menus/today/selections` | `recipeIds` or method-aware `selections`, plus `version` | Updated merged menu              |
| POST   | `/api/dinner/menus/today/confirm`    | `version`, UUID v4 `idempotencyKey`, `methodResolutions` | Confirmed menu                   |
| POST   | `/api/dinner/menus/today/cooking/start` | `version`, UUID v4 `idempotencyKey`                   | Frozen cooking session           |
| GET    | `/api/dinner/menus/today/cooking`       | None                                                     | Current frozen cooking session   |
| POST   | `/api/dinner/menus/today/cooking/dishes` | `recipeId`, optional `methodId`, `version`, UUID v4 `idempotencyKey` | Updated cooking session |
| PUT    | `/api/dinner/menus/today/cooking/dishes/{dishId}/completion` | `completed`, `version`              | Updated cooking session          |
| POST   | `/api/dinner/menus/today/complete`   | `version`, UUID v4 `idempotencyKey`                      | `recordId` and completed menu    |
| GET    | `/api/dinner/records`                | None                                                     | Completed record summaries       |
| GET    | `/api/dinner/records/{id}`           | None                                                     | Record detail and dish snapshots |
| GET    | `/api/dinner/records/{id}/inventory-deduction` | None                     | Record-scoped deduction state and proposal |
| POST   | `/api/dinner/records/{id}/inventory-deduction` | `action`, UUID v4 `idempotencyKey`, `items` | Terminal deduction result |

The menu business day changes at 04:00 in the household timezone. `TodayMenuResponse` contains `id`, `menuDate`, `status`, `version`, `mySelectionCount`, `partnerSelectionCount`, `consensusCount`, `selectedRecipeIds`, merged `dishes`, confirmation/completion metadata, optional `recordId`, and `historyVisible`. A completed menu strictly before the viewer's UTC `historyVisibleFrom` is returned as `{menuDate,status:"PRE_MEMBERSHIP",historyVisible:false}` with no menu, recipe, record, dish or actor identifiers. The inclusive boundary is visible. Record list/detail apply the same UTC boundary, with stable `(completedAt,id)` ordering.

Actors are privacy-safe objects `{kind}`. `kind` is `ME`, current `PARTNER`, `EXITED_MEMBER`, or `DELETED_MEMBER`; arrays are deduplicated by internal user identity and emitted in that relation order. Today-menu `confirmedBy`/`completedBy`, record `completedBy`, and each menu/record dish's `selectedBy` use this shape. `source` remains `ME`, `PARTNER`, or `BOTH` only when those current-member combinations apply; historical combinations rely on `selectedBy`. No wire response exposes actor user IDs, deleted usernames, or a raw-name fallback.

Each merged menu dish contains `recipeId`, `name`, `imagePath`, `category`, `flavor`, `estimatedMinutes`, `source`, `selectedBy`, `scope`, `recipeVersion`, nullable `method`, `methodChoices`, and `methodConflict`. A method choice is `{method,selectedBy}` and preserves the privacy-safe actors who selected that active method. `method` is the single effective `{id,name,cookingStyle}` summary when all selectors agree or after confirmation resolves a conflict; `methodConflict` is true when the two members selected different active methods. A system recipe is saved and returned with `scope: "SYSTEM"`, `recipeVersion: 1`, `method: null`, an empty `methodChoices` array and no conflict. A household recipe is saved with its current positive aggregate version and an explicitly selected active method, or the active default method for a legacy request.

Example merged household menu dish:

```json
{
  "recipeId": 14,
  "name": "番茄鸡蛋焖饭",
  "imagePath": "https://www.osheeep.com/media/recipes/tomato-rice-list.webp",
  "category": "主食",
  "flavor": "家常",
  "estimatedMinutes": 25,
  "source": "BOTH",
  "scope": "HOUSEHOLD",
  "recipeVersion": 8,
  "method": { "id": 21, "name": "家常焖炒", "cookingStyle": "焖炒" }
}
```

Selection updates replace only the current member's complete selection set. A request must use exactly one of:

```json
{ "recipeIds": [14, 18], "version": 5 }
```

or:

```json
{
  "selections": [
    { "recipeId": 14, "methodId": 22 },
    { "recipeId": 18, "methodId": null }
  ],
  "version": 5
}
```

The method-aware form requires an active method belonging to each household recipe; a system recipe uses `methodId: null`. Clients never send recipe scope, recipe version or household identity. Every write compares the menu `version`; stale writes return HTTP 409 with `DINNER_MENU_VERSION_CONFLICT`.

If both members selected the same recipe with different methods, confirmation must contain exactly one `{recipeId,methodId}` resolution for every conflicting dish and no extra resolution:

```json
{
  "version": 7,
  "idempotencyKey": "00000000-0000-4000-8000-000000000020",
  "methodResolutions": [{ "recipeId": 14, "methodId": 22 }]
}
```

The chosen method must be one of the methods actually selected for that recipe. Missing resolutions return HTTP 409 `DINNER_MENU_METHOD_RESOLUTION_REQUIRED`; duplicates, extra recipes or unselected methods return HTTP 400 `DINNER_MENU_METHOD_RESOLUTION_INVALID`. The service applies all exact resolutions and confirmation in one transaction. Confirming an empty menu returns `DINNER_MENU_EMPTY`. Updating a completed menu returns `DINNER_MENU_COMPLETED`; selection or confirmation writes after cooking starts return `DINNER_MENU_COOKING`.

### Actual cooking session (V15)

Starting cooking is the only transition from `CONFIRMED` to `COOKING`. It freezes every merged planned dish at that moment, including the display summary, selected recipe version, servings, selected method name/style/estimated time and ordered steps, ingredients, selectors, and image path. Later live recipe edits, archive operations, method changes, ingredient changes, or image metadata changes cannot alter this cooking session. The start request is replay-safe by its UUID v4 key and increments the menu version exactly once.

`CookingSessionResponse` is:

```json
{
  "menuId": 31,
  "menuDate": "2026-08-13",
  "status": "COOKING",
  "version": 8,
  "recordId": null,
  "dishes": [
    {
      "id": 101,
      "recipeId": 14,
      "name": "番茄炒蛋",
      "imagePath": "https://www.osheeep.com/media/recipes/family-list.webp",
      "category": "家常菜",
      "flavor": "酸甜",
      "estimatedMinutes": 10,
      "scope": "HOUSEHOLD",
      "recipeVersion": 8,
      "servings": 2,
      "method": {
        "id": 21,
        "name": "家常做法",
        "cookingStyle": "炒",
        "estimatedMinutes": 10,
        "steps": [{"instruction": "翻炒至熟", "sortOrder": 0}]
      },
      "ingredients": [
        {"ingredientId": 101, "name": "鸡蛋", "quantity": 2,
         "unit": "枚", "required": true, "sortOrder": 0}
      ],
      "origin": "PLANNED",
      "selectedBy": [{"kind": "ME"}, {"kind": "PARTNER"}],
      "addedBy": null,
      "completed": false,
      "completedBy": null,
      "completedAt": null,
      "sortOrder": 0
    }
  ]
}
```

A temporary dish is added with `POST /cooking/dishes`. The server validates a currently published system or current-household recipe, resolves or validates its method, freezes the same complete snapshot, assigns `origin: "TEMPORARY"`, records privacy-safe `addedBy` semantics, and increments the menu version. The UUID v4 add key is idempotent. When the original request omits `methodId`, replaying that same omitted-method request returns the originally frozen default method even though its stored snapshot contains a concrete method ID. Reusing a key for another menu or recipe, or supplying a different explicit method ID, returns `DINNER_COOKING_CONFLICT`. A recipe already present in the cooking session or a session above 20 dishes returns `DINNER_COOKING_DISH_INVALID`.

Dish completion is a desired-state `PUT`. Both checking and unchecking require the exact current menu version, including a retry whose dish already has the requested state. A successful state change records or clears `completedBy`/`completedAt` and increments the version. Stale versions return `DINNER_MENU_VERSION_CONFLICT`; missing dishes return `DINNER_COOKING_DISH_NOT_FOUND`. Mutations after final completion return `DINNER_MENU_COMPLETED`. `GET /cooking` remains readable with `status: "COMPLETED"` after another member ends dinner, includes the immutable record ID in `recordId`, and always returns the stored frozen fields rather than live recipe data. `recordId` is absent/null while the session is still `COOKING`.

Flyway migration `V15__add_menu_cooking_dish_snapshots.sql` creates the menu-owned cooking snapshot table and adds `origin` to immutable record dish snapshots. The cooking table deliberately has a menu foreign key but no live recipe, method, ingredient, membership, or user foreign keys: its JSON/display/actor-ID fields are frozen semantic data, and lifecycle services lock and remove cooking rows before resetting or deleting uncompleted menus. `origin` is exactly `PLANNED` or `TEMPORARY`.

The explicitly selected `DinnerCookingV15MySqlIT` is the guarded MySQL 8 acceptance suite for this migration and transaction boundary. It verifies fresh-to-V15 and production-shaped V13-to-V15 migration, V13 snapshot `origin` backfill, V15 constraints, two ACTIVE members racing add/completion writes at one version, concurrent final completion creating exactly one record with replay after conflict, rollback after a forced real snapshot-insert failure, and legacy `CONFIRMED` completion without cooking rows. It requires raw `OSHEEEP_DB_NAME == OSHEEEP_DB_TEST_NAME`, `OSHEEEP_ALLOW_EPHEMERAL_DATABASES=true`, and a loopback MySQL URL. It then creates only UUID-scoped child catalogs through `DinnerEphemeralCatalogHarness`, closes the Spring pool before cleanup, drops all generated catalogs, and verifies their absence. Never source the development or production `.env.local` for this test. Ordinary `mvn test` does not select `*MySqlIT`; run it explicitly with `mvn test -Dtest=DinnerCookingV15MySqlIT` after supplying a dedicated disposable loopback catalog.

Completion is idempotent by both the request key and the unique menu record. Repeated completion returns the existing record and never creates duplicate snapshots.

Final completion normally runs from `COOKING` and uses the exact current menu version. It requires at least one completed cooking dish; otherwise it returns `DINNER_COOKING_EMPTY`. Before creating the record, it locks the frozen cooking rows, validates their snapshot shape, and copies only rows whose completion state is true. Unchecked planned dishes are omitted; checked temporary dishes are included with `origin: "TEMPORARY"`. It never re-resolves live recipe data. Any validation or insert failure aborts the transaction without a partial record or completed menu.

For backward compatibility with already released clients whose completion request body is indistinguishable from the current one, `POST /api/dinner/menus/today/complete` also accepts an exact-version `CONFIRMED` menu. In that state only, the server atomically freezes every planned selection directly into `PLANNED` record snapshots using the legacy all-planned-dishes semantics. It does not create cooking rows, impose the cooking-session dish limit, create a temporary dish, expose an intermediate `COOKING` version, or write a `START_COOKING` action. A menu already in `COOKING` never takes this compatibility path and retains the current actual-dish subset semantics. Existing-record replay remains read-only and does not rebuild snapshots or cooking rows.

`GET /api/dinner/records/{id}` is a read-only historical view. Each dish returns `recipeId`, the frozen display fields, viewer-relative `source`, `scope`, `recipeVersion`, nullable `servings`, nullable `method` with ordered steps, ordered `ingredients`, and `origin`. It reads the snapshot only and does not re-resolve the live recipe, method, ingredients, or image asset, so later aggregate or approved-asset metadata changes cannot rewrite the record. Pre-V15 rows normalize `origin` to `PLANNED`. A pre-V7 row is recognized as legacy only when every V7 snapshot field is empty; it is normalized on read to `scope: "SYSTEM"`, `recipeVersion: 1`, `servings: null`, `method: null`, and `ingredients: []` without mutating the stored row.

### Record inventory deduction

Menu completion and inventory deduction are separate idempotent actions. Completing a new menu atomically creates its immutable cooking record with deduction status `PENDING`; a failure or later choice in the deduction flow cannot roll back, recreate, or rewrite that record. Records created before V11 default to `NOT_APPLICABLE` and never receive a retroactive proposal.

`GET /api/dinner/records/{id}/inventory-deduction` returns:

- `recordId` and status `NOT_APPLICABLE`, `PENDING`, `APPLIED`, or `SKIPPED`;
- nullable privacy-safe `handledBy` and UTC `handledAt` for a terminal result;
- `proposalItems` only while pending; and
- `appliedItems` only after an applied result.

Pending proposal items are aggregated from the immutable record snapshot by ingredient ID. They contain `ingredientId`, `name`, nullable `recipeQuantity`, `recipeUnit`, `required`, nullable `inventoryQuantity`, nullable `inventoryUnit`, nullable `inventoryVersion`, nullable `suggestedQuantity`, `selectedByDefault`, and `eligibility`. Eligibility is one of `READY`, `INSUFFICIENT`, `NOT_IN_INVENTORY`, `INVENTORY_QUANTITY_UNKNOWN`, `RECIPE_QUANTITY_UNKNOWN`, or `UNIT_MISMATCH`. Only a positive, exact-unit suggestion can be submitted. Required ready items are selected by default; optional ready items are not. Insufficient positive stock suggests at most the current amount. The service does not convert units or invent a quantity for `适量`.

`POST /api/dinner/records/{id}/inventory-deduction` accepts either:

```json
{
  "action": "APPLY",
  "idempotencyKey": "00000000-0000-4000-8000-000000000019",
  "items": [
    {
      "ingredientId": 1,
      "quantity": 2,
      "inventoryVersion": 4
    }
  ]
}
```

or `{"action":"SKIP","idempotencyKey":"...","items":[]}`. Apply requires 1–100 unique snapshot ingredients, positive quantities with at most 9 integer and 3 fraction digits, and the exact positive inventory version. The transaction follows household → record → ascending ingredient inventory lock order. It validates exact units and available quantities, updates all selected inventory rows, persists a terminal record result, and publishes the existing partner inventory notifications in the same transaction. Any failure rolls the inventory changes and terminal result back together.

`APPLIED` and `SKIPPED` are terminal. Repeating a request after either terminal state returns the stored terminal result without another inventory write or notification. A missing row, changed version, insufficient current amount, lock failure, or idempotency-key race returns HTTP 409 `DINNER_INVENTORY_DEDUCTION_CONFLICT`; the client must refresh and ask for confirmation again instead of replaying automatically. Invalid actions, items, snapshot eligibility, units, or quantities return HTTP 400 `DINNER_INVENTORY_DEDUCTION_INVALID`.

Flyway migration `V11__add_record_inventory_deduction.sql` adds the record status, terminal idempotency key, actor/time metadata and strict JSON result, with an enforced state check, unique key and actor foreign key. Local verification on 2026-07-24 passed the focused service/controller/codec/persistence tests and the guarded MySQL 8 migration test across fresh, production-shaped V4, and current V10 catalogs through V11. This is local evidence only; it does not imply a production migration or deployment.

Example immutable household record dish:

```json
{
  "recipeId": 14,
  "name": "番茄鸡蛋焖饭",
  "imagePath": "https://www.osheeep.com/media/recipes/tomato-rice-list.webp",
  "category": "主食",
  "flavor": "家常",
  "estimatedMinutes": 25,
  "source": "BOTH",
  "scope": "HOUSEHOLD",
  "recipeVersion": 8,
  "origin": "PLANNED",
  "servings": 2,
  "method": {
    "id": 21,
    "name": "家常焖炒",
    "cookingStyle": "焖炒",
    "steps": [
      { "instruction": "番茄切块后与米饭焖熟", "sortOrder": 0 }
    ]
  },
  "ingredients": [
    {
      "ingredientId": 1,
      "name": "番茄",
      "quantity": null,
      "unit": "个",
      "required": true,
      "sortOrder": 0
    }
  ]
}
```

Invalid recipe identity or state returns HTTP 400 with `DINNER_RECIPE_INVALID` and message `Dinner recipe is invalid`. New selection rejects unknown, non-published, foreign-household, incomplete, or otherwise nonselectable recipes. Rendering a previously saved pre-cooking menu accepts a current-household recipe that is still `PUBLISHED` or was archived after selection; it still rejects a missing recipe, an invalid system identity, a null/non-positive household saved version, inconsistent identities for the same recipe, an invalid saved method association, or missing approved list-image data, and returns the positive saved household `recipeVersion`. Starting cooking performs the stronger live-aggregate check before freezing the session: a published household recipe must still equal the saved version, while an archived household recipe must equal the saved version plus the single archive increment. It also rejects tampered method ownership, ingredient visibility, household ownership, or snapshot data. Discovery omits archived and invalid household catalog entries. Final completion does not re-query or revalidate live recipe state or versions; it validates the already frozen cooking snapshots and copies only completed rows. Any snapshot validation or insert failure aborts the transaction without a partial record or completed menu.

## Dinner Notifications

Flyway migration `V9__add_dinner_notifications.sql` adds an authenticated, privacy-safe in-app notification feed. V9 does not modify V1–V8. A row stores the recipient, optional active-household scope, controlled notification type, controlled reference identity/version, a SHA-256 dedupe key, read time, creation time and expiry. It deliberately has no user, household or business foreign keys: notification writes occur at the end of existing domain lock orders, while account deletion and household dissolution remove rows explicitly. CHECK constraints, active-recipient snapshots, visibility queries and cleanup tests enforce the contract without introducing reverse parent-row locks.

All notification routes require a bearer token:

| Method | Path                                            | Request | Response data |
| ------ | ----------------------------------------------- | ------- | ------------- |
| GET    | `/api/dinner/notifications`                     | Optional `beforeId`; `limit` defaults to 20 and must be 1–50 | `{items, unreadCount, nextBeforeId}` |
| GET    | `/api/dinner/notifications/unread-count`        | None | `{unreadCount}` |
| PUT    | `/api/dinner/notifications/{notificationId}/read` | None | No data |
| PUT    | `/api/dinner/notifications/read-all`            | None | `{updatedCount}` |

Items are ordered by descending ID, which is the stable cursor order for rows created with database-millisecond UTC times. Each item contains only:

```json
{
  "id": 42,
  "type": "MENU_RECONFIRM_REQUIRED",
  "title": "今晚菜单需要重新确认",
  "body": "TA 修改了选择，请查看最新内容",
  "target": "TONIGHT",
  "read": false,
  "createdAt": "2026-07-23T10:26:00Z"
}
```

The first release type allowlist is `PARTNER_JOINED`, `PARTNER_SELECTION_UPDATED`, `MENU_RECONFIRM_REQUIRED`, `MENU_COMPLETED`, `FAMILY_RECIPE_UPDATED`, `INVENTORY_UPDATED`, `OWNERSHIP_TRANSFERRED`, `MEMBER_LEFT`, and `MEMBER_REMOVED`. Targets are controlled enum values only: `HOUSEHOLD_MANAGE`, `HOUSEHOLD_BINDING`, `TONIGHT`, `RECORDS`, `FAMILY_RECIPES`, or `INGREDIENTS`. Responses never contain a client-supplied URL, household ID, business reference ID/version, internal user ID, username, nickname, avatar, `openid`, or dedupe key.

Visibility is evaluated for every list, count and read operation:

- A personal `MEMBER_REMOVED` message has `household_id = NULL` and remains visible to its active recipient after removal.
- A household-scoped message is visible only while the recipient's current `ACTIVE` membership belongs to that same household.
- Old-household messages do not appear after leaving, removal, dissolution or joining another household.
- Expired rows are never listed, counted or marked read. The default expiry is 90 days after creation; read state never extends it.
- Account deletion deletes all rows received by that user. Household dissolution deletes all rows scoped to that household. A scheduled retention pass deletes expired rows.

`beforeId` and `notificationId` must be positive; invalid query/path values return HTTP 400 `VALIDATION_ERROR`. A missing, foreign, expired or no-longer-visible single notification returns HTTP 404 `DINNER_NOTIFICATION_NOT_FOUND` without revealing whether another user's row exists. Single-read and read-all operations are idempotent.

Domain services publish a notification only after their existing authorization, optimistic-version and idempotency checks succeed, in the same transaction as the business change. Fixed reference type/id/version semantics derive a deterministic dedupe key. Duplicate delivery of the same successful semantic event is treated as already published, while other persistence failures abort the transaction so the business change cannot commit without its in-app notification. Events cover partner join, partner selection changes or reconfirmation, menu completion, household-recipe publication or published-method update, inventory creation/update/removal, ownership transfer, member leave and member removal.

The local V9 migration integration test uses a disposable loopback-only MySQL 8.0.45 instance and covers fresh, production-shaped V4 and current V8 catalogs through V9. It verifies the CHECK constraints, absence of foreign keys, valid rows and dedupe enforcement, then removes every ephemeral catalog and container. This is local migration evidence only; production V9, server deployment, WeChat upload and subscription-message templates have not been executed.

## Dinner WeChat Subscriptions

Flyway migration `V10__add_dinner_subscription_deliveries.sql` adds optional, one-time WeChat subscription delivery state without modifying V1–V9. WeChat delivery supplements the V9 in-app feed: accepting, rejecting or failing a WeChat request never changes the completed business action and never removes or rolls back the in-app notification.

All subscription routes require a bearer token and derive the user and active household from the server-side session:

| Method | Path | Request | Response data |
| ------ | ---- | ------- | ------------- |
| GET | `/api/dinner/subscriptions/config` | None | `{actions}` containing only currently promptable runtime template IDs |
| POST | `/api/dinner/subscriptions/results` | UUID v4 `requestId`, controlled `action`, controlled `results` | No data |

The first-release actions are fixed:

- `HOUSEHOLD_INVITE_READY` may request `PARTNER_JOINED`.
- `MENU_CONFIRMED` may request `MENU_CHANGED` and `MENU_COMPLETED`.

`GET /config` returns an empty `actions` array when the feature is disabled, there is no active household, or every relevant scenario is already blocked by an unexpired authorization/result. A sample enabled response is:

```json
{
  "actions": [
    {
      "action": "MENU_CONFIRMED",
      "templates": [
        { "scenario": "MENU_CHANGED", "templateId": "runtime-value" },
        { "scenario": "MENU_COMPLETED", "templateId": "runtime-value" }
      ]
    }
  ]
}
```

The template ID is runtime configuration needed by `wx.requestSubscribeMessage`; it is never accepted back from the client and is not stored in V10. The client may register only the scenarios returned for the successful action:

```json
{
  "requestId": "00000000-0000-4000-8000-000000000016",
  "action": "MENU_CONFIRMED",
  "results": [
    { "scenario": "MENU_CHANGED", "outcome": "ACCEPT" },
    { "scenario": "MENU_COMPLETED", "outcome": "REJECT" }
  ]
}
```

Outcomes are exactly `ACCEPT`, `REJECT`, `BAN` or `FILTER`. The list must contain 1–5 distinct scenarios, every scenario must belong to the action allowlist, and `requestId` must be UUID v4. Invalid, disabled, unauthenticated or nonmember requests fail without a write. `(recipient_id, request_key, scenario)` makes an identical client retry idempotent.

V10 stores recipient, household, scenario, request key, controlled outcome/status, controlled notification reference, event dedupe key, bounded attempt state and timestamps. It deliberately stores no template ID/title/field key, `openid`, access token, WeChat error text, household name, recipe name or other free text. Status is exactly `WAITING_EVENT`, `READY`, `SENDING`, `SENT`, `REJECTED` or `TERMINAL_FAILED`; database CHECK constraints enforce the outcome/status/event shape and the 0–5 attempt bound.

After the V9 notification transaction commits, a separate transaction consumes at most one matching unexpired `WAITING_EVENT` authorization:

- `PARTNER_JOINED` consumes a `PARTNER_JOINED` event.
- `MENU_CHANGED` consumes the first subsequent `PARTNER_SELECTION_UPDATED` or `MENU_RECONFIRM_REQUIRED` event.
- `MENU_COMPLETED` consumes a `MENU_COMPLETED` event.

The sender claims a row in a short transaction, calls WeChat outside the transaction, then records the result in another short transaction. It sends only controlled notification title/body text, an event time rendered in `Asia/Shanghai`, and a fixed mini-program page. Invalid token responses `40001`/`40014` refresh the token at most once per send. Transport failures, `-1` and `43108` retry with bounded backoff; no subscription, invalid template/data, platform ban and other terminal responses do not retry. A stale fifth attempt is terminalized without creating a sixth send.

Records expire 90 days after registration. A scheduled cleanup deletes rows at the expiry boundary; account deletion deletes rows by recipient and household dissolution deletes rows by household. The table has no foreign keys so these lifecycle deletes do not introduce a reverse parent-row lock.

Production configuration is disabled by default with `OSHEEEP_WECHAT_SUBSCRIPTION_ENABLED=false`. Enabling it requires three complete, distinct templates through the `OSHEEEP_WECHAT_SUBSCRIPTION_*` environment variables documented in `deploy/production/OPERATIONS.md`: each template needs its platform ID, exact platform title and distinct subject/time/note field keys, and the state must be `developer`, `trial` or `formal`. Missing or duplicate production configuration fails application startup. No formal template value is committed to this repository.

On 2026-07-23, the isolated MySQL 8.0.45 V10 integration test passed 1/1 for fresh, production-shaped V4 and current V9 catalogs. It verified 19 columns, enforced CHECK constraints, no foreign keys, no template/openid columns, request/event uniqueness and invalid state rejection, then removed all generated catalogs and the temporary container. This is local migration evidence only: production V10, formal templates, deployment, experience-build upload and real WeChat delivery remain unexecuted.

## Thought Clusters

| Method | Path                             | Request body | Response data          |
| ------ | -------------------------------- | ------------ | ---------------------- |
| GET    | `/api/thoughts/clusters`         | None         | Cluster array          |
| POST   | `/api/thoughts/clusters/rebuild` | None         | Completed job response |

A cluster contains `id`, `userId`, `title`, `thesis`, `fragmentIds`, `maturityScore`, `missingQuestions`, `status`, and `updatedAt`.

The rebuild response contains `userId`, `status`, `note`, and `jobId`. The first version completes synchronously, so a successful response has `status: "completed"` and a persisted job ID.

## Thought Outlines

| Method | Path                              | Request body | Response data     |
| ------ | --------------------------------- | ------------ | ----------------- |
| POST   | `/api/thoughts/outlines/generate` | None         | Generated outline |
| GET    | `/api/thoughts/outlines/{id}`     | None         | Stored outline    |

Generation selects the current user's latest mature cluster, stores the result, and returns it synchronously. The response contains `id`, `userId`, `clusterId`, `titleCandidates`, `coreArgument`, `outline`, `supportingFragmentIds`, `missingMaterials`, and `createdAt`.

`outline` is an array of sections with `title` and `content`. The rule-based first version always creates `问题背景`, `核心论证`, and `行动与补链`.

If no mature cluster exists, generation returns `BUSINESS_ERROR`.

## Fragment API Status

The thought-fragment module is intentionally pending redesign. The frontend currently contains calls to `/api/thoughts/fragments`, but those CRUD endpoints are not available until that redesign is resumed. Do not treat the frontend calls as an active backend contract yet.

## Local Exploration

Use Swagger UI at `http://localhost:8080/swagger-ui.html` for request and response schemas. After logging in, use the **Authorize** action and paste the access token to call protected endpoints.
