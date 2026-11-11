package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.errors.RaskConfigError;
import ai.rask.sdk.errors.RaskConnectionError;
import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.errors.UploadFailedError;
import ai.rask.sdk.internal.Json;
import ai.rask.sdk.model.CreateFolderRequest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner's hard requirement: every operation and helper honours a custom base URL, keeps its path prefix, tolerates
 * a trailing slash and never sends the key anywhere else. Over real HTTP against the harness.
 */
class BaseUrlTest {
    private final Harness harness = Harness.shared();

    @BeforeEach
    void reset() {
        harness.reset();
    }

    private Rask.Builder client(String baseUrl) {
        return Rask.builder().apiKey(harness.apiKey).baseUrl(baseUrl).environment(Map.of());
    }

    private static String normalized(String value) {
        return Rask.builder().apiKey("k").baseUrl(value).environment(Map.of()).build().baseUrl();
    }

    @Test
    void normalizesWithoutLosingThePrefix() {
        assertEquals("https://gw.example.com/rask/v3", normalized("https://gw.example.com/rask/v3"));
        assertEquals("https://gw.example.com/rask/v3", normalized("https://gw.example.com/rask/v3/"));
        assertEquals("https://gw.example.com/rask/v3", normalized("https://gw.example.com/rask/v3///"));
        assertEquals("https://gw.example.com/rask/v3", normalized("  https://gw.example.com/rask/v3  "));
        assertEquals("https://gw.example.com:8443/v3", normalized("https://gw.example.com:8443/v3"));
        assertEquals("http://localhost:4010", normalized("http://localhost:4010/"));
    }

    @Test
    void refusesBaseUrlsThatCannotWork() {
        for (String bad : List.of("ftp://example.com/v3", "api.rask.ai/v3", "https://", "https:///v3",
                "https://user:pass@example.com/v3", "https://example.com/v3?x=1", "https://example.com/v3#f",
                "http://exa mple.com")) {
            assertThrows(RaskConfigError.class, () -> normalized(bad), bad);
        }
    }

    @Test
    void readsTheEnvironmentInOrder() {
        Map<String, String> env = Map.of("RASK_API_KEY", "k", "RASK_BASE_URL", "https://env.example.com/v3/");
        assertEquals("https://env.example.com/v3", Rask.builder().environment(env).build().baseUrl());
        assertEquals("https://arg.example.com/v3",
                Rask.builder().environment(env).baseUrl("https://arg.example.com/v3").build().baseUrl());
        assertEquals(Rask.DEFAULT_BASE_URL, Rask.builder().environment(Map.of("RASK_API_KEY", "k")).build().baseUrl());
        assertEquals(Rask.DEFAULT_BASE_URL,
                Rask.builder().environment(Map.of("RASK_API_KEY", "k", "RASK_BASE_URL", " ")).build().baseUrl());
        assertEquals("https://api.rask.ai/v3", Rask.DEFAULT_BASE_URL);
    }

    @Test
    void warnsWhenTheKeyWouldTravelInClearText() {
        List<String> warnings = new ArrayList<>();
        Rask.builder().apiKey("k").environment(Map.of()).warningSink(warnings::add)
                .baseUrl("http://gw.example.com/v3").build();
        assertEquals(1, warnings.size());
        assertFalse(warnings.get(0).contains("k "));
        warnings.clear();
        Rask.builder().apiKey("k").environment(Map.of()).warningSink(warnings::add)
                .baseUrl("http://127.0.0.1:4010/v3").build();
        Rask.builder().apiKey("k").environment(Map.of()).warningSink(warnings::add)
                .baseUrl("https://gw.example.com/v3").build();
        assertEquals(List.of(), warnings);
    }

    /** Every one of the 82 operations, by its generated method, with the prefix and with a trailing slash. */
    @Test
    void everyOperationStaysUnderThePrefix() throws Throwable {
        List<Map<String, Object>> operations = harness.operations();
        assertEquals(82, operations.size());
        for (String baseUrl : List.of(harness.baseUrl, harness.baseUrl + "/")) {
            harness.reset();
            Rask rask = client(baseUrl).build();
            TypedCalls typed = new TypedCalls(rask, operations);
            for (Map<String, Object> operation : operations) {
                Map<String, Object> sample = map(operation.get("sample"));
                String id = (String) operation.get("operationId");
                RaskResponse<?> response = typed.call(id, map(sample.get("path")), map(sample.get("query")),
                        map(sample.get("body")), (String) map(sample.get("headers")).get("If-Match"), null);
                assertEquals(((Number) operation.get("success_status")).intValue(), response.status(), id);
                if (Boolean.TRUE.equals(operation.get("has_json_response"))) {
                    assertNotNull(response.data(), id);
                    new ModelWalker().walk(response.data());
                } else {
                    assertNull(response.data(), id);
                }
            }
            List<Map<String, Object>> records = harness.requests();
            assertEquals(82, records.size());
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> record : records) {
                String id = (String) record.get("operationId");
                seen.add(id);
                assertEquals("api", record.get("listener"), id);
                assertTrue(((String) record.get("path")).startsWith(harness.prefix + "/"), id);
                assertFalse(((String) record.get("path")).contains("//"), id);
                assertEquals(List.of(), record.get("violations"),
                        () -> id + ": " + Json.write(record.get("violations")));
            }
            assertEquals(82, seen.size());
        }
    }

    /** Every operation has a method on a resource, named after its operationId. */
    @Test
    void everyOperationIsCallableByName() throws ReflectiveOperationException {
        Rask rask = client(harness.baseUrl).build();
        int found = 0;
        for (Map<String, Object> operation : harness.operations()) {
            for (Object resource : TypedCalls.resources(rask)) {
                Method method = TypedCalls.fullMethod(resource, (String) operation.get("operationId"));
                if (method != null) {
                    found++;
                }
            }
        }
        assertEquals(82, found);
    }

    @Test
    void aRedirectOnTheSameHostOutsideThePrefixDropsTheKey() {
        String elsewhere = harness.baseUrl.replace(harness.prefix, "/elsewhere") + "/me";
        harness.script("getMe", Map.of("redirect", Map.of("to", elsewhere, "status", 307)));
        Rask rask = client(harness.baseUrl).build();
        assertThrows(RaskError.class, () -> rask.account().getMe());
        List<Map<String, Object>> records = harness.requests();
        assertEquals(2, records.size());
        assertEquals("/elsewhere/me", records.get(1).get("path"));
        assertFalse(map(records.get(1).get("headers")).containsKey("authorization"));
    }

    @Test
    void aWriteRedirectedOutsideThePrefixIsNotFollowed() {
        String elsewhere = harness.baseUrl.replace(harness.prefix, "/elsewhere") + "/folders";
        harness.script("createFolder", Map.of("redirect", Map.of("to", elsewhere, "status", 307)));
        Rask rask = client(harness.baseUrl).build();
        RaskError error = assertThrows(RaskError.class,
                () -> rask.folders().createFolder(CreateFolderRequest.builder().name("x").build()));
        assertEquals(307, error.status());
        assertEquals(1, harness.requests().size());
    }

    @Test
    void aRedirectOnTheSameHostKeepsTheKey() {
        harness.script("getMe", Map.of("redirect", Map.of("to", harness.baseUrl + "/plan", "status", 307)));
        client(harness.baseUrl).build().account().getMe();
        List<Map<String, Object>> records = harness.requests();
        assertEquals(2, records.size());
        assertEquals("Bearer " + harness.apiKey, map(records.get(1).get("headers")).get("authorization"));
    }

    @Test
    void aGetRedirectedToAnotherHostFollowsWithoutTheKey() {
        harness.script("getMe", Map.of("redirect", Map.of("to", "other", "status", 307)));
        RaskResponse<?> response = client(harness.baseUrl).build().account().getMe();
        assertEquals(200, response.status());
        List<Map<String, Object>> other = onListener("other");
        assertEquals(1, other.size());
        assertFalse(map(other.get(0).get("headers")).containsKey("authorization"));
    }

    @Test
    void aCallersHeadersStayOnTheBaseUrlsHostToo() {
        harness.script("getMe", Map.of("redirect", Map.of("to", "other", "status", 307)));
        client(harness.baseUrl).build().account().getMe(
                RequestOptions.builder().header("X-Gateway-Token", "gateway-secret").build());
        assertEquals("gateway-secret", map(onListener("api").get(0).get("headers")).get("x-gateway-token"));
        assertFalse(map(onListener("other").get(0).get("headers")).containsKey("x-gateway-token"));
    }

    @Test
    void aPostRedirectedToAnotherHostIsRaisedNotFollowed() {
        harness.script("createFolder", Map.of("redirect", Map.of("to", "other", "status", 307)));
        Rask rask = client(harness.baseUrl).build();
        RaskError error = assertThrows(RaskError.class, () -> rask.folders().createFolder(
                CreateFolderRequest.builder().name("x").build()));
        assertEquals(307, error.status());
        assertEquals(List.of(), onListener("other"));
    }

    /**
     * A Location that is a URL, but one no request can be made of: no host, or a port that does not exist. There is
     * nothing to follow and nothing a retry would mend, so the redirect is the answer, as when the Location is no URL.
     */
    @Test
    void aRedirectNoRequestCanBeMadeOfIsTheAnswer() {
        for (String location : List.of("http:///nowhere", "http://127.0.0.1:99999/me")) {
            harness.reset();
            harness.script("getMe",
                    Map.of("redirect", Map.of("to", location, "status", 307), "repeat", "forever"));
            Rask rask = client(harness.baseUrl).retryBudget(Duration.ofSeconds(2)).build();
            RaskError error = assertThrows(RaskError.class, () -> rask.account().getMe(), location);
            assertFalse(error instanceof RaskConnectionError, () -> location + ": " + error);
            assertEquals(307, error.status(), location);
            assertEquals(1, harness.requests().size(), location);
        }
    }

    /** The HTTP client refuses such a port before it connects. That is no outage, so no retry is spent on it. */
    @Test
    void aBaseUrlNoRequestCanBeMadeOfIsNotRetriedAsAConnectionError() {
        Rask rask = client("http://127.0.0.1:99999/v3").retryBudget(Duration.ofSeconds(2)).build();
        assertThrows(IllegalArgumentException.class, () -> rask.account().getMe());
    }

    /** The same refusal for the URL storage is to be reached at: it is the upload that failed, with its id. */
    @Test
    void aStorageUrlNoRequestCanBeMadeOfIsAnUploadFailure() throws IOException {
        harness.script("createUpload", Map.of("patch", Map.of("transfer",
                Map.of("url", "http://127.0.0.1:99999/object?X-Amz-Signature=s"))));
        Rask rask = client(harness.baseUrl).build();
        Path file = Harness.contentFile(4096);
        UploadFailedError failed = assertThrows(UploadFailedError.class, () -> rask.uploads().uploadFile(file));
        assertNotNull(failed.uploadId());
        assertTrue(failed.getCause() instanceof IllegalArgumentException);
    }

    private List<Map<String, Object>> onListener(String listener) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> record : harness.requests()) {
            if (listener.equals(record.get("listener"))) {
                found.add(record);
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
