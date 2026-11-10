package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.errors.InsufficientBalanceError;
import ai.rask.sdk.errors.JobFailedError;
import ai.rask.sdk.errors.PreconditionFailedError;
import ai.rask.sdk.errors.RaskConfigError;
import ai.rask.sdk.errors.RaskConnectionError;
import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.errors.RateLimitError;
import ai.rask.sdk.internal.SdkVersion;
import ai.rask.sdk.model.Cost;
import ai.rask.sdk.model.CreateDubRequest;
import ai.rask.sdk.model.CreateFolderRequest;
import ai.rask.sdk.model.DubJobParams;
import ai.rask.sdk.model.DubMode;
import ai.rask.sdk.model.Job;
import ai.rask.sdk.model.JobParams;
import ai.rask.sdk.model.JobStatus;
import ai.rask.sdk.model.Project;
import ai.rask.sdk.model.Segment;
import ai.rask.sdk.model.UpdateProjectRequest;
import ai.rask.sdk.model.UpdateSegmentRequest;
import ai.rask.sdk.params.ListProjectsParams;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the scenarios cannot see: the typed surface an application writes against. */
class TypedSurfaceTest {
    private final Harness harness = Harness.shared();
    private Rask rask;

    @BeforeEach
    void reset() {
        harness.reset();
        rask = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of()).build();
    }

    @Test
    void unknownValuesReadAsUnknownThroughTheTypedGetters() {
        harness.script("getJob", Map.of("inject", true));
        Job job = rask.jobs().getJob("job-1").data();
        assertFalse(job.status().isKnown() && job.type().isKnown());
        assertEquals("s12_unknown_enum_value", job.type().value());
        assertTrue(job.params().isUnknown());
        assertEquals("s12_unknown_variant", job.params().discriminator());
        assertEquals("unknown variant payload", job.params().get("s12_unknown_field"));
        assertEquals("unknown field value", job.get("s12_unknown_field"));
    }

    @Test
    void knownUnionVariantsDecodeToTheirClass() {
        Job job = rask.jobs().getJob("job-1").data();
        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertTrue(job.status().isKnown());
        JobParams params = job.params();
        assertFalse(params.isUnknown());
        assertTrue(params instanceof DubJobParams, params.getClass().getName());
        Cost cost = job.cost();
        assertNotNull(cost);
        assertNotNull(cost.discriminator());
    }

    @Test
    void openEnumsCompareByValue() {
        assertEquals(JobStatus.SUCCEEDED, JobStatus.of("succeeded"));
        assertTrue(JobStatus.SUCCEEDED == JobStatus.of("succeeded"));
        assertEquals(JobStatus.of("new_status"), JobStatus.of("new_status"));
        assertEquals("new_status", JobStatus.of("new_status").toString());
        assertNull(JobStatus.of(null));
    }

    @Test
    void responsesExposeTheEtagTypedAndRaw() {
        RaskResponse<Segment> response = rask.segments().getSegment("p", "s");
        assertEquals(response.etag(), response.data().etag());
        assertEquals(response.etag(), response.headers().firstValue("etag").orElse(null));
        assertNotNull(response.requestId());
        assertEquals(200, response.status());
    }

    @Test
    void createExposesLocationAndKey() {
        harness.script("createFolder", Map.of("headers", Map.of("Idempotent-Replayed", "true")));
        RaskResponse<?> response = rask.folders().createFolder(CreateFolderRequest.builder().name("x").build());
        assertEquals(201, response.status());
        assertTrue(response.location().startsWith("/v3/folders/"));
        assertTrue(response.idempotentReplayed());
        assertNotNull(response.idempotencyKey());
    }

    @Test
    void ifMatchIsRequiredBeforeTheNetwork() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> rask.segments()
                .updateSegment("p", "s", null, UpdateSegmentRequest.builder().translationText("x").build()));
        assertTrue(error.getMessage().contains("if_match"), error.getMessage());
        assertEquals(List.of(), harness.requests());
    }

    @Test
    void clearingSendsEmptyStringsAndOmittedStaysOmitted() {
        UpdateProjectRequest clear = UpdateProjectRequest.builder().folderId("").metadata(Map.of("crm_id", "")).build();
        assertEquals("{\"folder_id\":\"\",\"metadata\":{\"crm_id\":\"\"}}", clear.toJson());
        UpdateProjectRequest rename = UpdateProjectRequest.builder().name("n").folderId(null).build();
        assertEquals("{\"name\":\"n\"}", rename.toJson());
    }

    @Test
    void buildersRefuseWhatTheContractRefuses() {
        assertThrows(IllegalArgumentException.class, () -> CreateFolderRequest.builder().build());
        assertThrows(IllegalArgumentException.class, () -> UpdateProjectRequest.builder().build());
    }

    @Test
    void pathParametersCannotEscapeTheirSegment() {
        assertThrows(IllegalArgumentException.class, () -> rask.projects().getProject(".."));
        assertThrows(IllegalArgumentException.class, () -> rask.projects().getProject(""));
        assertThrows(IllegalArgumentException.class, () -> rask.projects().getProject(null));
        rask.projects().getProject("a/b c");
        assertEquals(harness.prefix + "/projects/a%2Fb%20c", harness.requests().get(0).get("path"));
    }

    @Test
    void arrayQueryParametersAreOneCommaSeparatedValue() {
        rask.projects().listProjects(ListProjectsParams.builder().ids(List.of("a b", "c")).limit(5).build());
        assertEquals("ids=a%20b,c&limit=5", harness.requests().get(0).get("raw_query"));
    }

    @Test
    void autoPagingIsLazy() {
        harness.post("/pages", Map.of("operationId", "listProjects", "pages", 3, "page_size", 2));
        PageIterable<Project> walk = rask.projects().listProjectsAutoPaging(
                ListProjectsParams.builder().limit(2).build());
        assertEquals(List.of(), harness.requests());
        assertEquals("listProjects-item-1", walk.stream().findFirst().map(Project::id).orElse(null));
        assertEquals(1, harness.requests().size());
        assertEquals(6, walk.stream().count());
    }

    @Test
    void problemMembersAreTyped() {
        harness.script("createDub", Map.of("problem", "insufficient_balance"));
        InsufficientBalanceError error = assertThrows(InsufficientBalanceError.class, () -> rask.dubbing()
                .createDub("p", CreateDubRequest.builder().mode(DubMode.AUTO).expectedCostMinutes(1).build()));
        assertEquals(402, error.status());
        assertEquals("insufficient_balance", error.code());
        assertEquals("Insufficient balance", error.title());
        assertEquals("https://docs.api.rask.ai/errors/insufficient_balance", error.type());
        assertEquals(Boolean.FALSE, error.retryable());
        assertEquals("none", error.retry().value());
        assertEquals("minutes", error.creditType().value());
        assertEquals(Integer.valueOf(1), error.requiredMinutes());
        assertEquals(Integer.valueOf(0), error.balanceMinutes());
        assertEquals(List.of(), error.errors());
        assertNotNull(error.detail());
        assertNotNull(error.hint());
        assertNotNull(error.requestId());
        assertNotNull(error.idempotencyKey());
        assertTrue(error.getMessage().contains("insufficient_balance"));
    }

    @Test
    void preconditionFailedHasTheCurrentEtag() {
        harness.script("updateSegment", Map.of("problem", "precondition_failed",
                "patch", Map.of("current_etag", "\"s-1\"")));
        PreconditionFailedError error = assertThrows(PreconditionFailedError.class, () -> rask.segments()
                .updateSegment("p", "s", "\"s-0\"", UpdateSegmentRequest.builder().translationText("x").build()));
        assertEquals("\"s-1\"", error.currentEtag());
    }

    @Test
    void rateLimitHasRetryAfter() {
        harness.script("getMe", Map.of("problem", "rate_limited", "retry_after", 130));
        RateLimitError error = assertThrows(RateLimitError.class, () -> rask.account().getMe());
        assertEquals(Duration.ofSeconds(130), error.retryAfter().orElse(null));
    }

    @Test
    void aFailedJobCarriesTheJobAndItsProblem() {
        harness.post("/jobs", Map.of("id", "job-f", "type", "dub", "states", List.of("failed")));
        JobFailedError error = assertThrows(JobFailedError.class, () -> rask.jobs().wait("job-f"));
        assertEquals("job-f", error.job().id());
        assertEquals("dub_voiceover_failed", error.code());
    }

    @Test
    void connectionFailuresEndWithAConnectionError() throws Exception {
        int unused;
        try (ServerSocket socket = new ServerSocket(0)) {
            unused = socket.getLocalPort();
        }
        Rask dead = Rask.builder().apiKey(harness.apiKey).baseUrl("http://127.0.0.1:" + unused + "/v3")
                .environment(Map.of()).retryBudget(Duration.ofSeconds(1)).build();
        RaskConnectionError error = assertThrows(RaskConnectionError.class, () -> dead.folders().createFolder(
                CreateFolderRequest.builder().name("x").build()));
        assertEquals(0, error.status());
        assertNotNull(error.idempotencyKey());
        assertFalse(error.getMessage().contains(harness.apiKey));
    }

    @Test
    void theKeyIsRedactedEverywhereItCouldBePrinted() {
        List<String> logs = new ArrayList<>();
        Rask logged = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of())
                .debug(true).logSink(logs::add).build();
        logged.account().getMe();
        harness.script("getMe", Map.of("problem", "invalid_token"));
        RaskError error = assertThrows(RaskError.class, () -> logged.account().getMe());
        String printed = String.join("\n", logs) + logged + logged.account() + error + error.getMessage();
        assertFalse(printed.contains(harness.apiKey));
        assertTrue(String.join("\n", logs).contains("Bearer ***"));
    }

    @Test
    void headersCannotReplaceTheKey() {
        assertThrows(IllegalArgumentException.class,
                () -> RequestOptions.builder().header("authorization", "Bearer other"));
    }

    @Test
    void optionsTheOperationDoesNotTakeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> rask.account()
                .getMe(RequestOptions.builder().idempotencyKey("k").build()));
        assertThrows(IllegalArgumentException.class, () -> rask.account()
                .getMe(RequestOptions.builder().ifNoneMatch("\"e\"").build()));
        assertEquals(List.of(), harness.requests());
    }

    @Test
    void notModifiedIsAResultWithoutData() {
        harness.post("/jobs", Map.of("id", "job-e", "type", "dub", "states", List.of("running"), "retry_after", 1));
        RaskResponse<Job> first = rask.jobs().getJob("job-e");
        RaskResponse<Job> second = rask.jobs().getJob("job-e", null,
                RequestOptions.builder().ifNoneMatch(first.etag()).build());
        assertTrue(second.notModified());
        assertNull(second.data());
    }

    @Test
    void aKeyWithAStrayNewlineIsTrimmedAndAnInvalidOneNeverQuoted() {
        Rask trimmed = Rask.builder().apiKey(harness.apiKey + "\n").baseUrl(harness.baseUrl).environment(Map.of())
                .build();
        trimmed.account().getMe();
        assertEquals("Bearer " + harness.apiKey,
                ((Map<?, ?>) harness.requests().get(0).get("headers")).get("authorization"));
        RaskConfigError error = assertThrows(RaskConfigError.class,
                () -> Rask.builder().apiKey("secret\nvalue").environment(Map.of()).build());
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void aMissingKeyNamesTheVariable() {
        RaskConfigError error = assertThrows(RaskConfigError.class,
                () -> Rask.builder().environment(Map.of()).build());
        assertTrue(error.getMessage().contains("RASK_API_KEY"));
    }

    @Test
    void theUserAgentAndClientHeaderNameTheSdk() {
        rask.account().getMe();
        Map<?, ?> headers = (Map<?, ?>) harness.requests().get(0).get("headers");
        assertEquals("rask-sdk-java/" + SdkVersion.VALUE, headers.get("x-rask-client"));
        assertTrue(String.valueOf(headers.get("user-agent")).matches("rask-sdk-java/\\S+ java/\\S+"));
    }

    @Test
    void theVersionMatchesThePom() throws Exception {
        String pom = new String(Files.readAllBytes(Path.of("pom.xml")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<artifactId>rask-sdk</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(matcher.find());
        assertEquals(matcher.group(1), SdkVersion.VALUE);
    }
}
