package com.example.app.todo;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagIntegrationTest extends IntegrationTest {

    private static final String TAGS = "/api/v1/tags";
    private static final String DROPDOWN = TAGS + "/dropdown?for=TODO_EDIT_OWN";

    private static String createPayload(String name, String color) {
        String colorJson = Objects.isNull(color) ? "null" : "\"" + color + "\"";
        return """
                {"name": "%s", "color": %s}
                """.formatted(name, colorJson);
    }

    private static String updatePayload(String name, String color, boolean active, long version) {
        String colorJson = Objects.isNull(color) ? "null" : "\"" + color + "\"";
        return """
                {"name": "%s", "color": %s, "active": %b, "version": %d}
                """.formatted(name, colorJson, active, version);
    }

    /** Creates a tag as {@code user} and returns its id; fails the test unless it answers 201. */
    private String createTag(TestUser user, String name) throws Exception {
        return read(postAs(user.accessToken(), TAGS, createPayload(name, null)).andExpect(status().isCreated()), "$.response.id");
    }

    /** Creates a tag and switches it inactive; fails the test unless both answer 2xx. */
    private String createInactiveTag(TestUser user, String name) throws Exception {
        String id = createTag(user, name);
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload(name, null, false, 0)).andExpect(status().isOk());
        return id;
    }

    /**
     * Follows {@code pageInfo.nextCursor} from {@code firstBatch} (a path with its query string) to
     * the last batch, which must say {@code hasMore} false with no cursor; returns each batch's labels.
     */
    private List<List<String>> walk(TestUser user, String firstBatch) throws Exception {
        List<List<String>> batches = new ArrayList<>();
        String next = firstBatch;
        while (Objects.nonNull(next)) {
            assertThat(batches).hasSizeLessThan(20);
            ResultActions batch = getAs(user.accessToken(), next).andExpect(status().isOk());
            batches.add(read(batch, "$.response[*].label"));
            if (Boolean.TRUE.equals(read(batch, "$.pageInfo.hasMore"))) {
                next = firstBatch + "&after=" + read(batch, "$.pageInfo.nextCursor");
            } else {
                batch.andExpect(jsonPath("$.pageInfo.nextCursor").doesNotExist());
                next = null;
            }
        }
        return batches;
    }

    private ResultActions getWithQuery(TestUser user, String path, String query) throws Exception {
        return mvc.perform(get(path).param("query", query).header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
    }

    @Test
    @DisplayName("Create answers 201 with the trimmed name, the colour upper-cased, active, version 0 and the audit fields")
    void createReturns201WithTheSavedEntity() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TAGS, createPayload("  Urgent  ", "#ff8800"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.id").isNotEmpty())
                .andExpect(jsonPath("$.response.name").value("Urgent"))
                .andExpect(jsonPath("$.response.color").value("#FF8800"))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(user.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(user.id().toString()));
    }

    @Test
    @DisplayName("No colour is null whether absent or empty, on create and update")
    void anEmptyColourIsStoredAsNone() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TAGS, """
                {"name": "Plain"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.color").value(nullValue()));
        postAs(user.accessToken(), TAGS, createPayload("Empty", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.color").value(nullValue()));
        String id = read(postAs(user.accessToken(), TAGS, createPayload("Coloured", "#00ff00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.color").value("#00FF00")), "$.response.id");
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("Coloured", "", true, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.color").value(nullValue()));
        Integer emptyColours = jdbc.queryForObject("select count(*) from tags where owner_id = ? and color = ''",
                Integer.class, user.id());
        assertThat(emptyColours).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FF8800", "#FF880", "#FF88001", "#GG8800", "red", "#ff 800"})
    @DisplayName("A colour that is not #RRGGBB is 400 VALIDATION_FAILED naming color")
    void colourMustBeHexRgb(String color) throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TAGS, createPayload("Coloured", color))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/color"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be a colour as #RRGGBB"));
        String id = createTag(user, "Recoloured");
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("Recoloured", color, true, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/color"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be a colour as #RRGGBB"));
    }

    @Test
    @DisplayName("Get returns the tag")
    void getReturnsTheEntity() throws Exception {
        TestUser user = newUser();
        String id = createTag(user, "Work");

        getAs(user.accessToken(), TAGS + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(id))
                .andExpect(jsonPath("$.response.name").value("Work"))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.ownerId").value(user.id().toString()));
    }

    @Test
    @DisplayName("Get of an unknown id is 404 TAG_NOT_FOUND")
    void getUnknownIdIs404() throws Exception {
        getAs(newUser().accessToken(), TAGS + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
    }

    @Test
    @DisplayName("A non-UUID id in the path is 400 REQUEST_PARAMETER_INVALID")
    void malformedIdIs400() throws Exception {
        getAs(newUser().accessToken(), TAGS + "/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("Update changes name, colour and active and increments the version; the same values again change nothing")
    void updateChangesTheFieldsAndBumpsTheVersion() throws Exception {
        TestUser user = newUser();
        String id = read(postAs(user.accessToken(), TAGS, createPayload("Old", "#000000")).andExpect(status().isCreated()),
                "$.response.id");

        putAs(user.accessToken(), TAGS + "/" + id, updatePayload(" New ", "#abcdef", false, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("New"))
                .andExpect(jsonPath("$.response.color").value("#ABCDEF"))
                .andExpect(jsonPath("$.response.active").value(false))
                .andExpect(jsonPath("$.response.version").value(1));
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("New", "#ABCDEF", false, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("New", null, true, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.color").value(nullValue()))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.version").value(2));
    }

    @Test
    @DisplayName("Update with a stale version is 409 OPTIMISTIC_LOCK_CONFLICT and changes nothing")
    void updateWithAStaleVersionIs409() throws Exception {
        TestUser user = newUser();
        String id = createTag(user, "Original");
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("First edit", null, true, 0)).andExpect(status().isOk());

        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("Stale edit", null, false, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
        getAs(user.accessToken(), TAGS + "/" + id)
                .andExpect(jsonPath("$.response.name").value("First edit"))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.version").value(1));
    }

    @Test
    @DisplayName("PUT and DELETE on an unknown id are 404 TAG_NOT_FOUND")
    void writesToAnUnknownIdAre404() throws Exception {
        TestUser user = newUser();
        String unknown = TAGS + "/" + UUID.randomUUID();

        putAs(user.accessToken(), unknown, updatePayload("Ghost", null, true, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        deleteAs(user.accessToken(), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
    }

    @Test
    @DisplayName("Delete answers 204 and the tag is gone")
    void deleteReturns204AndTheEntityIsGone() throws Exception {
        TestUser user = newUser();
        String id = createTag(user, "Temporary");
        getAs(user.accessToken(), TAGS + "/" + id).andExpect(status().isOk());

        deleteAs(user.accessToken(), TAGS + "/" + id).andExpect(status().isNoContent());
        getAs(user.accessToken(), TAGS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
    }

    @Test
    @DisplayName("Create without a name, or with a blank one, is 400 VALIDATION_FAILED naming the field")
    void createWithoutRequiredFieldsIs400() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), TAGS, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"));
        postAs(user.accessToken(), TAGS, createPayload("   ", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"));
    }

    @Test
    @DisplayName("Update without a version or without active is 400 VALIDATION_FAILED naming each")
    void writesWithoutAVersionAre400() throws Exception {
        TestUser user = newUser();
        String id = createTag(user, "Versionless");

        putAs(user.accessToken(), TAGS + "/" + id, createPayload("No version", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem("#/version")))
                .andExpect(jsonPath("$.errors[*].pointer", hasItem("#/active")));
    }

    @Test
    @DisplayName("A name of 50 Gurmukhi letters is accepted; 51 characters is 400 with the configured limit, on create and update")
    void nameLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();

        String id = read(postAs(user.accessToken(), TAGS, createPayload("ਪ".repeat(50), null))
                .andExpect(status().isCreated()), "$.response.id");
        postAs(user.accessToken(), TAGS, createPayload("y".repeat(51), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 50 characters"));
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("ਬ".repeat(50), null, true, 0))
                .andExpect(status().isOk());
        putAs(user.accessToken(), TAGS + "/" + id, updatePayload("y".repeat(51), null, true, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/name"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 50 characters"));
    }

    @Test
    @DisplayName("Markup in a name is stored and returned as text (escape-on-output policy)")
    void createWithAScriptTagStoresItAsText() throws Exception {
        TestUser user = newUser();
        String hostile = "<script>alert(1)</script>";

        postAs(user.accessToken(), TAGS, createPayload(hostile, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value(hostile));
    }

    @Test
    @DisplayName("A name you already use is 409 TAG_NAME_TAKEN without internals in the body; another user may use it")
    void createWithATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createTag(user, "Home");

        String body = postAs(user.accessToken(), TAGS, createPayload("Home", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_NAME_TAKEN"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        createTag(newUser(), "Home");
    }

    @Test
    @DisplayName("Names are unique per owner ignoring case and surrounding spaces")
    void nameUniquenessIgnoresCaseAndSpaces() throws Exception {
        TestUser user = newUser();
        createTag(user, "Errands");

        postAs(user.accessToken(), TAGS, createPayload("  ERRANDS ", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_NAME_TAKEN"));
    }

    @Test
    @DisplayName("Renaming to a name you already use, in another case, is 409 TAG_NAME_TAKEN")
    void updateToATakenNameIs409() throws Exception {
        TestUser user = newUser();
        createTag(user, "Taken");
        String id = createTag(user, "Free");

        String body = putAs(user.accessToken(), TAGS + "/" + id, updatePayload(" taken", null, true, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TAG_NAME_TAKEN"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(user.accessToken(), TAGS + "/" + id).andExpect(jsonPath("$.response.name").value("Free"));
    }

    @Test
    @DisplayName("Concurrent creates with one name store exactly one tag; the others are 409 TAG_NAME_TAKEN")
    void concurrentCreatesWithTheSameNameStoreOne() throws Exception {
        TestUser user = newUser();

        List<Answer> answers = race(() -> postAs(user.accessToken(), TAGS, createPayload("Race", null)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(201).containsOnly(201, 409);
        assertRefusalsAre(answers, 409, "TAG_NAME_TAKEN");
        Integer rows = jdbc.queryForObject("select count(*) from tags where owner_id = ? and name = 'Race'",
                Integer.class, user.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent updates with one version apply exactly once; the others are 409 OPTIMISTIC_LOCK_CONFLICT")
    void concurrentUpdatesWithOneVersionApplyOnce() throws Exception {
        TestUser user = newUser();
        String id = createTag(user, "Contested");

        List<Answer> answers = race(() -> putAs(user.accessToken(), TAGS + "/" + id, updatePayload("Renamed", null, false, 0)));

        assertThat(answers).extracting(Answer::status).containsOnlyOnce(200).containsOnly(200, 409);
        assertRefusalsAre(answers, 409, "OPTIMISTIC_LOCK_CONFLICT");
        Long version = jdbc.queryForObject("select version from tags where id = ?::uuid", Long.class, id);
        assertThat(version).isEqualTo(1L);
    }

    static Stream<Arguments> endpoints() {
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", TAGS),
                Arguments.of("GET", TAGS + "/" + someId),
                Arguments.of("POST", TAGS),
                Arguments.of("PUT", TAGS + "/" + someId),
                Arguments.of("DELETE", TAGS + "/" + someId),
                Arguments.of("GET", DROPDOWN));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    @DisplayName("Every endpoint answers 401 AUTHENTICATION_REQUIRED without a token")
    void everyEndpointNeedsAToken(String method, String path) throws Exception {
        ResultActions result = switch (method) {
            case "GET" -> getAs(null, path);
            case "POST" -> postAs(null, path, createPayload("x", null));
            case "PUT" -> putAs(null, path, updatePayload("x", null, true, 0));
            case "DELETE" -> deleteAs(null, path);
            default -> throw new IllegalArgumentException(method);
        };
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Another user's tag is 404 on get, update and delete, and stays untouched")
    void anotherUsersRecordIs404() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String id = createTag(owner, "Private");
        getAs(owner.accessToken(), TAGS + "/" + id).andExpect(status().isOk());

        getAs(stranger.accessToken(), TAGS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        putAs(stranger.accessToken(), TAGS + "/" + id, updatePayload("Hijacked", null, false, 0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        deleteAs(stranger.accessToken(), TAGS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TAG_NOT_FOUND"));
        getAs(owner.accessToken(), TAGS + "/" + id)
                .andExpect(jsonPath("$.response.name").value("Private"))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("Your page holds only your tags, and its pageInfo counts only them")
    void listShowsOnlyYourTags() throws Exception {
        TestUser user = newUser();
        createTag(user, "Mine");
        createTag(newUser(), "Theirs");

        getAs(user.accessToken(), TAGS)
                .andExpect(jsonPath("$.response[*].name", contains("Mine")))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.pageInfo.totalPages").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false));
    }

    @Test
    @DisplayName("The list is offset-paged: pageInfo describes each page; the configured maximum size is accepted, one more is 400")
    void listIsOffsetPaged() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Paged C", "Paged A", "Paged B"}) {
            createTag(user, name);
        }

        getAs(user.accessToken(), TAGS + "?size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].name", contains("Paged A", "Paged B")))
                .andExpect(jsonPath("$.pageInfo.number").value(0))
                .andExpect(jsonPath("$.pageInfo.size").value(2))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(3))
                .andExpect(jsonPath("$.pageInfo.totalPages").value(2))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(true))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(false));
        getAs(user.accessToken(), TAGS + "?size=2&page=1")
                .andExpect(jsonPath("$.response[*].name", contains("Paged C")))
                .andExpect(jsonPath("$.pageInfo.number").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
        getAs(user.accessToken(), TAGS + "?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(3))
                .andExpect(jsonPath("$.pageInfo.size").value(100));
        getAs(user.accessToken(), TAGS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100"));
    }

    @Test
    @DisplayName("A page past the last is empty but says where it is")
    void listPastTheLastPageIsEmptyButNavigable() throws Exception {
        TestUser user = newUser();
        createTag(user, "Only one");
        getAs(user.accessToken(), TAGS).andExpect(jsonPath("$.response.length()").value(1));

        getAs(user.accessToken(), TAGS + "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0))
                .andExpect(jsonPath("$.pageInfo.number").value(5))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(1))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
    }

    @ParameterizedTest
    @EnumSource(DropdownStatus.class)
    @DisplayName("The page's status, the dropdown's word: ACTIVE lists active tags, INACTIVE inactive ones, ALL both")
    void filterByStatusReturnsOnlyMatching(DropdownStatus wanted) throws Exception {
        TestUser user = newUser();
        createTag(user, "Live");
        createInactiveTag(user, "Retired");
        List<String> expected = switch (wanted) {
            case ACTIVE -> List.of("Live");
            case INACTIVE -> List.of("Retired");
            case ALL -> List.of("Live", "Retired");
        };

        List<String> names = read(getAs(user.accessToken(), TAGS + "?status=" + wanted).andExpect(status().isOk()),
                "$.response[*].name");
        assertThat(names).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("Without status the page lists every tag (ALL), unlike the dropdown's default ACTIVE")
    void listWithoutStatusListsEveryTag() throws Exception {
        TestUser user = newUser();
        createTag(user, "Live");
        createInactiveTag(user, "Retired");

        getAs(user.accessToken(), TAGS)
                .andExpect(jsonPath("$.response[*].name", contains("Live", "Retired")));
        getAs(user.accessToken(), DROPDOWN)
                .andExpect(jsonPath("$.response[*].label", contains("Live")));
    }

    @Test
    @DisplayName("query matches part of the name, ignoring case; % in it matches only a %")
    void filterByQueryReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        createTag(user, "Holiday packing");
        createTag(user, "Work");
        createTag(user, "100% done");

        getAs(user.accessToken(), TAGS + "?query=PACK")
                .andExpect(jsonPath("$.response[*].name", hasItem("Holiday packing")))
                .andExpect(jsonPath("$.response[*].name", not(hasItem("Work"))));
        getWithQuery(user, TAGS, "%")
                .andExpect(jsonPath("$.response[*].name", contains("100% done")));
    }

    @Test
    @DisplayName("A query of 100 characters is accepted; 101 is 400 with the configured limit")
    void queryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        createTag(user, "ਪ".repeat(50));

        getWithQuery(user, TAGS, "ਪ".repeat(50))
                .andExpect(jsonPath("$.response.length()").value(1));
        getWithQuery(user, TAGS, "ਪ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));
        getWithQuery(user, TAGS, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("query"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100 characters"));
    }

    @Test
    @DisplayName("sort=NAME (the default) is alphabetical; NEWEST is newest first")
    void sortOrdersThePage() throws Exception {
        TestUser user = newUser();
        createTag(user, "Alpha");
        createTag(user, "Charlie");
        createTag(user, "Bravo");

        getAs(user.accessToken(), TAGS)
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), TAGS + "?sort=NAME")
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), TAGS + "?sort=NEWEST")
                .andExpect(jsonPath("$.response[*].name", contains("Bravo", "Charlie", "Alpha")));
    }

    @Test
    @DisplayName("Unconvertible filter values are 400 REQUEST_PARAMETER_INVALID naming only the fields")
    void unconvertibleFilterValuesAre400() throws Exception {
        String body = getAs(newUser().accessToken(), TAGS + "?sort=OLDEST_FIRST&page=first&size=many&status=maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", containsInAnyOrder("sort", "page", "size", "status")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("invalid value", "invalid value", "invalid value", "invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
    }

    @Test
    @DisplayName("An unknown JSON field is 400 REQUEST_UNREADABLE")
    void unknownJsonFieldIs400() throws Exception {
        postAs(newUser().accessToken(), TAGS, """
                {"name": "Sneaky", "active": false}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("A page costs one statement while partial and two (with the count) when full, whatever the total")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestUser one = newUser();
        createTag(one, "Single");
        TestUser many = newUser();
        for (int index = 0; index < 25; index++) {
            createTag(many, "Tag " + index);
        }

        long forOne = statementsFor(one, TAGS + "?size=20");
        long forFullPage = statementsFor(many, TAGS + "?size=20");
        long forFullPageOfMore = statementsFor(many, TAGS + "?size=5");

        assertThat(forOne).isEqualTo(1);
        assertThat(forFullPage).isEqualTo(2);
        assertThat(forFullPageOfMore).isEqualTo(forFullPage);
    }

    @Test
    @DisplayName("ids returns only the asked tags, in the page's order; unknown and another user's ids are simply absent")
    void filterByIdsReturnsOnlyMatching() throws Exception {
        TestUser user = newUser();
        String charlie = createTag(user, "Charlie");
        String alpha = createTag(user, "Alpha");
        createTag(user, "Bravo");
        String theirs = createTag(newUser(), "Theirs");
        getAs(user.accessToken(), TAGS).andExpect(jsonPath("$.response[*].name", hasItem("Bravo")));

        getAs(user.accessToken(), TAGS + "?ids=" + String.join(",", charlie, alpha, theirs, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].name", contains("Alpha", "Charlie")))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(2));
    }

    @Test
    @DisplayName("ids takes up to the maximum page size; one more is 400 VALIDATION_FAILED, a malformed id 400 REQUEST_PARAMETER_INVALID")
    void idsAreBounded() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), TAGS + "?ids=" + randomIds(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));
        getAs(user.accessToken(), TAGS + "?ids=" + randomIds(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must have at most 100 items")));
        getAs(user.accessToken(), TAGS + "?ids=nope," + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }

    @Test
    @DisplayName("A page by ids costs the same statements as any page: one while partial, two when full")
    void filterByIdsRunsAConstantNumberOfQueries() throws Exception {
        TestUser user = newUser();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            ids.add(createTag(user, "By id " + index));
        }

        assertThat(statementsFor(user, TAGS + "?size=20&ids=" + ids.getFirst())).isEqualTo(1);
        assertThat(statementsFor(user, TAGS + "?size=5&ids=" + String.join(",", ids))).isEqualTo(2);
    }

    /** Statements run by one request: for a page, plus the count only when the page is full. */
    private long statementsFor(TestUser user, String path) throws Exception {
        statistics().clear();
        getAs(user.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    @DisplayName("A page index over the configured maximum is 400 VALIDATION_FAILED naming page")
    void pageIndexIsBounded() throws Exception {
        getAs(newUser().accessToken(), TAGS + "?page=100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("page"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100000"));
    }

    @Test
    @DisplayName("A dropdown batch costs exactly one statement, for one option and for a full batch")
    void dropdownRunsOneStatement() throws Exception {
        TestUser one = newUser();
        createTag(one, "Single");
        TestUser many = newUser();
        for (int index = 0; index < 21; index++) {
            createTag(many, "Option " + index);
        }
        getAs(many.accessToken(), DROPDOWN + "&size=20")
                .andExpect(jsonPath("$.response.length()").value(20))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));

        assertThat(statementsFor(one, DROPDOWN + "&size=20")).isEqualTo(1);
        assertThat(statementsFor(many, DROPDOWN + "&size=20")).isEqualTo(1);
    }

    @Test
    @DisplayName("The dropdown needs for: without it 400 REQUEST_PARAMETER_MISSING, with an unknown permission 400 REQUEST_PARAMETER_INVALID at for")
    void theDropdownNeedsFor() throws Exception {
        String token = newUser().accessToken();

        getAs(token, TAGS + "/dropdown")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_MISSING"));
        getAs(token, TAGS + "/dropdown?for=NOT_A_PERMISSION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("for"));
    }

    @Test
    @DisplayName("A for the caller doesn't hold is 403 ACCESS_DENIED, whatever else they hold, before any statement")
    void aForTheCallerDoesntHoldIs403() throws Exception {
        TestUser user = newUser();
        Set<Permission> allButIt = EnumSet.allOf(Permission.class);
        allButIt.remove(Permission.TODO_EDIT_OWN);
        // Whatever a caller's first request reads once (and later requests don't) is read here, before the count.
        getAs(user.accessToken(), TAGS).andExpect(status().isOk());

        statistics().clear();
        mvc.perform(get(DROPDOWN).with(holding(user.accessToken(), allButIt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("A held for whose reach is every record is 400 DROPDOWN_REACH_UNSUPPORTED at for: this dropdown lists your own tags only")
    void aForOfAnotherReachIs400() throws Exception {
        Optional<Permission> everyRecord = Arrays.stream(Permission.values())
                .filter(permission -> permission.reach() == Reach.ALL)
                .findFirst();
        assumeTrue(everyRecord.isPresent(), "no permission reaches every record among these pieces");
        String holdingIt = newUser().accessToken();

        mvc.perform(get(TAGS + "/dropdown?for=" + everyRecord.get()).with(holding(holdingIt, EnumSet.of(everyRecord.get()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DROPDOWN_REACH_UNSUPPORTED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("for"));
    }

    @Test
    @DisplayName("The dropdown lists only your active tags, as exactly {value, label}, ordered by name")
    void dropdownListsYourOptionsInDefaultOrder() throws Exception {
        TestUser user = newUser();
        String alpha = createTag(user, "Alpha");
        createTag(user, "Charlie");
        createTag(user, "Bravo");
        createInactiveTag(user, "Aardvark retired");
        createTag(newUser(), "Aardvark of someone else");

        getAs(user.accessToken(), DROPDOWN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo", "Charlie")))
                .andExpect(jsonPath("$.response[0].value").value(alpha))
                .andExpect(jsonPath("$.response[0].length()").value(2))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
    }

    @ParameterizedTest
    @EnumSource(DropdownStatus.class)
    @DisplayName("status ACTIVE lists active tags, INACTIVE inactive ones, ALL both")
    void dropdownStatusSelectsByTheActiveFlag(DropdownStatus wanted) throws Exception {
        TestUser user = newUser();
        createTag(user, "Live");
        createInactiveTag(user, "Retired");
        List<String> expected = switch (wanted) {
            case ACTIVE -> List.of("Live");
            case INACTIVE -> List.of("Retired");
            case ALL -> List.of("Live", "Retired");
        };

        List<String> labels = read(getAs(user.accessToken(), DROPDOWN + "&status=" + wanted).andExpect(status().isOk()),
                "$.response[*].label");
        assertThat(labels).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("sortDirection=DESC reverses the dropdown's name order")
    void dropdownSortDirectionReversesTheOrder() throws Exception {
        TestUser user = newUser();
        createTag(user, "Alpha");
        createTag(user, "Charlie");
        createTag(user, "Bravo");

        getAs(user.accessToken(), DROPDOWN + "&sortDirection=ASC")
                .andExpect(jsonPath("$.response[*].label", contains("Alpha", "Bravo", "Charlie")));
        getAs(user.accessToken(), DROPDOWN + "&sortDirection=DESC")
                .andExpect(jsonPath("$.response[*].label", contains("Charlie", "Bravo", "Alpha")));
    }

    @Test
    @DisplayName("The dropdown's query matches part of the label, ignoring case")
    void dropdownQueryMatchesPartOfTheLabelIgnoringCase() throws Exception {
        TestUser user = newUser();
        createTag(user, "Garden work");
        createTag(user, "Reading");

        getAs(user.accessToken(), DROPDOWN + "&query=GARD")
                .andExpect(jsonPath("$.response[*].label", contains("Garden work")));
    }

    @Test
    @DisplayName("A dropdown query of 100 characters is accepted; 101 is 400 with the configured limit")
    void dropdownQueryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser user = newUser();
        createTag(user, "ਬ".repeat(50));

        getWithQuery(user, DROPDOWN, "ਬ".repeat(50))
                .andExpect(jsonPath("$.response.length()").value(1));
        getWithQuery(user, DROPDOWN, "ਬ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(0));
        getWithQuery(user, DROPDOWN, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("query"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100 characters"));
    }

    @Test
    @DisplayName("The dropdown comes in batches: nextCursor continues by name to the last batch")
    void dropdownComesInBatches() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Delta", "Alpha", "Echo", "Charlie", "Bravo"}) {
            createTag(user, name);
        }

        assertThat(walk(user, DROPDOWN + "&size=2"))
                .containsExactly(List.of("Alpha", "Bravo"), List.of("Charlie", "Delta"), List.of("Echo"));
    }

    @Test
    @DisplayName("Descending dropdown batches walk to the end in reverse name order")
    void dropdownDescendingBatchesWalkToTheEnd() throws Exception {
        TestUser user = newUser();
        for (String name : new String[] {"Delta", "Alpha", "Echo", "Charlie", "Bravo"}) {
            createTag(user, name);
        }

        assertThat(walk(user, DROPDOWN + "&size=2&sortDirection=DESC"))
                .containsExactly(List.of("Echo", "Delta"), List.of("Charlie", "Bravo"), List.of("Alpha"));
    }

    @Test
    @DisplayName("A dropdown cursor taken in one direction is refused in the other")
    void dropdownCursorFromAnotherDirectionIsRefused() throws Exception {
        TestUser user = newUser();
        createTag(user, "Alpha");
        createTag(user, "Bravo");
        String ascendingCursor = read(getAs(user.accessToken(), DROPDOWN + "&size=1"), "$.pageInfo.nextCursor");

        getAs(user.accessToken(), DROPDOWN + "&size=1&sortDirection=DESC&after=" + ascendingCursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("after"));
    }

    @Test
    @DisplayName("A tampered dropdown cursor is 400 REQUEST_PARAMETER_INVALID naming after")
    void dropdownTamperedCursorIsRefused() throws Exception {
        getAs(newUser().accessToken(), DROPDOWN + "&after=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[0].parameter").value("after"));
    }

    @Test
    @DisplayName("A dropdown batch of the configured maximum is accepted, one more is 400 naming size")
    void dropdownSizeFollowsTheConfiguredMaximum() throws Exception {
        String token = newUser().accessToken();
        getAs(token, DROPDOWN + "&size=100").andExpect(status().isOk());
        getAs(token, DROPDOWN + "&size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("size"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 100"));
    }

    @Test
    @DisplayName("An unknown dropdown status is 400 REQUEST_PARAMETER_INVALID naming status")
    void dropdownUnknownStatusIs400() throws Exception {
        getAs(newUser().accessToken(), DROPDOWN + "&status=ARCHIVED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("status")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }
}
