package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.rask.sdk.internal.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the example programs in a child JVM, with the key and base URL in its real process environment: the proof that
 * RASK_API_KEY and RASK_BASE_URL work outside the injectable environment the other tests use.
 */
class SmokeProgramTest {
    /** The auto-dub outcome of a project_processing job that started its dub. */
    private static final String DISPATCHED =
            "{\"state\":\"dispatched\",\"skipped_reason\":null,\"dub_job_id\":\"job-dub\"}";

    private final Harness harness = Harness.shared();

    @Test
    void readsTheProcessEnvironmentAndPrintsOneLinePerCall() throws IOException, InterruptedException {
        harness.reset();
        harness.post("/pages", Map.of("operationId", "listProjects", "pages", 2, "page_size", 1));
        Process process = start(harness.apiKey, harness.baseUrl + "/", "examples/smoke/Smoke.java");
        String output = outputOf(process);
        assertEquals(0, process.exitValue(), output);
        assertFalse(output.contains(harness.apiKey), "the key was printed");
        for (String call : List.of("getMe 200", "listLanguages 200", "listVoices 200", "listProjects[1] 200",
                "listProjects[2] 200", "getBalance 200", "getPlan 200", "createFolder 201", "getFolder 200",
                "deleteFolder 204")) {
            assertTrue(output.contains(call), () -> call + " is missing from:\n" + output);
        }
        List<Map<String, Object>> records = harness.requests();
        assertEquals(10, records.size());
        for (Map<String, Object> record : records) {
            assertEquals(List.of(), record.get("violations"), String.valueOf(record.get("operationId")));
            assertTrue(String.valueOf(record.get("path")).startsWith(harness.prefix + "/"));
        }
    }

    /** A project_processing job's result, with the auto-dub outcome given as JSON. */
    private static Object projectResult(String autoDub) {
        return Json.parse("{\"type\":\"project_processing\",\"project_id\":\"project-1\","
                + "\"source_language\":\"en-us\",\"speakers_detected\":1,"
                + "\"subtitles_imported\":{\"source\":false,\"target\":false},"
                + "\"copy_of_project_id\":null,\"reused_dub\":false,\"auto_dub\":" + autoDub + ","
                + "\"onscreen_auto_start\":{\"state\":\"not_requested\",\"rejection_code\":null},"
                + "\"localization_job_id\":null,\"notices\":[]}");
    }

    /** Scripts what the worked example calls, with every output's download at the given URL. */
    private void scriptTheWorkedExample(String downloadUrl, String autoDub) {
        harness.reset();
        harness.post("/jobs", Map.of("id", "job-import", "type", "media_import",
                "states", List.of("running", "succeeded"),
                "retry_after", 1));
        harness.post("/jobs", Map.of("id", "job-project", "type", "project_processing",
                "states", List.of("running", "succeeded"), "retry_after", 1, "result", projectResult(autoDub)));
        harness.post("/jobs", Map.of("id", "job-dub", "type", "dub",
                "states", List.of("running", "succeeded"), "retry_after", 1));
        harness.script("completeUpload", Map.of("job", "job-import"));
        harness.script("createProject", Map.of("job", "job-project"));
        // The contract's example URLs point off this machine: send them to a host on it instead.
        Rask rask = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of()).build();
        String outputs = rask.exports().listProjectOutputs("p").data().toJson().replaceAll(
                "\"url\":\"[^\"]*\"", "\"url\":\"" + downloadUrl + "\"");
        assertFalse(outputs.contains("\"url\":\"https:"), outputs);
        harness.delete("/requests");
        harness.script("listProjectOutputs", Map.of("body", Json.parse(outputs)));
    }

    /** Each operation the harness saw, with a repeat of the one before left out. */
    private List<String> callsInOrder() {
        List<String> order = new ArrayList<>();
        for (Map<String, Object> record : harness.requests()) {
            assertEquals(List.of(), record.get("violations"), String.valueOf(record.get("operationId")));
            assertEquals(List.of(), ((Map<?, ?>) record.get("response")).get("self_check"),
                    String.valueOf(record.get("operationId")));
            String id = String.valueOf(record.get("operationId"));
            if (order.isEmpty() || !id.equals(order.get(order.size() - 1))) {
                order.add(id);
            }
        }
        return order;
    }

    /** The README's example, whole: upload, estimate, createProject, wait for it and for the dub, download. */
    @Test
    void theWorkedExampleRunsEndToEnd(@TempDir Path directory) throws IOException, InterruptedException {
        scriptTheWorkedExample(harness.otherBaseUrl + "/download", DISPATCHED);
        Path file = Harness.contentFile(4096);
        Path progress = Path.of(file + ".es-es.properties");
        progress.toFile().deleteOnExit();
        Process process = startIn(directory, harness.apiKey, harness.baseUrl, "examples/worked/WorkedExample.java",
                file.toString());
        String output = outputOf(process);
        assertEquals(0, process.exitValue(), output);
        assertFalse(output.contains(harness.apiKey), "the key was printed");
        assertEquals(List.of("createUpload", "__storage_put", "completeUpload", "getJob", "estimateProject",
                "createProject", "getJob", "listProjectOutputs"), callsInOrder().subList(0, 8));
        List<String> waited = new ArrayList<>();
        boolean downloaded = false;
        String createKey = null;
        for (Map<String, Object> record : harness.requests()) {
            String path = String.valueOf(record.get("path"));
            Map<?, ?> headers = (Map<?, ?>) record.get("headers");
            if ("getJob".equals(record.get("operationId"))) {
                waited.add(path.substring(path.lastIndexOf('/') + 1));
            }
            if ("createProject".equals(record.get("operationId"))) {
                createKey = String.valueOf(headers.get("idempotency-key"));
            }
            if (path.equals("/download")) {
                downloaded = true;
                assertFalse(headers.containsKey("authorization"));
            }
        }
        assertEquals(List.of("job-import", "job-project", "job-dub"),
                waited.stream().distinct().collect(Collectors.toList()));
        assertTrue(output.contains("dubbed_video 200 dubbed.mp4"), output);
        assertTrue(downloaded, output);
        assertTrue(Files.exists(directory.resolve("dubbed.mp4")), output);
        // The key it sent is the one it saved, so that a run started again sends the same one.
        Properties saved = new Properties();
        try (Reader reader = Files.newBufferedReader(progress)) {
            saved.load(reader);
        }
        assertEquals(saved.getProperty("createKey"), createKey);
        assertEquals("job-project", saved.getProperty("projectJobId"));
    }

    /** Started again, it goes on from what it saved: no second upload and no second project. */
    @Test
    void theWorkedExampleStartedAgainGoesOn(@TempDir Path directory) throws IOException, InterruptedException {
        Path file = Harness.contentFile(4096);
        Path.of(file + ".es-es.properties").toFile().deleteOnExit();
        scriptTheWorkedExample(harness.otherBaseUrl + "/download", DISPATCHED);
        Process first = startIn(directory, harness.apiKey, harness.baseUrl, "examples/worked/WorkedExample.java",
                file.toString());
        String firstOutput = outputOf(first);
        assertEquals(0, first.exitValue(), firstOutput);
        scriptTheWorkedExample(harness.otherBaseUrl + "/download", DISPATCHED);
        Process again = startIn(directory, harness.apiKey, harness.baseUrl, "examples/worked/WorkedExample.java",
                file.toString());
        String output = outputOf(again);
        assertEquals(0, again.exitValue(), output);
        List<String> calls = callsInOrder();
        assertEquals(List.of("getJob", "listProjectOutputs"), calls.subList(0, 2), String.valueOf(calls));
        assertFalse(calls.contains("createUpload"), String.valueOf(calls));
        assertFalse(calls.contains("createProject"), String.valueOf(calls));
    }

    /** A skipped auto-dub stops the program before the download, with what to do next. */
    @Test
    void theWorkedExampleStopsWhenTheDubWasSkipped(@TempDir Path directory) throws IOException, InterruptedException {
        scriptTheWorkedExample(harness.otherBaseUrl + "/download",
                "{\"state\":\"skipped\",\"skipped_reason\":\"cost_changed\",\"dub_job_id\":null}");
        Path file = Harness.contentFile(4096);
        Path.of(file + ".es-es.properties").toFile().deleteOnExit();
        Process process = startIn(directory, harness.apiKey, harness.baseUrl, "examples/worked/WorkedExample.java",
                file.toString());
        String output = outputOf(process);
        assertNotEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Project project-1 was not dubbed: auto_dub is skipped (cost_changed). Call "
                + "estimateDub, then createDub within the same cap of"), output);
        assertFalse(callsInOrder().contains("listProjectOutputs"), output);
        try (Stream<Path> saved = Files.list(directory)) {
            assertEquals(List.of(), saved.collect(Collectors.toList()));
        }
    }

    /** Storage answers an expired URL with an error page. Saved under the output's name, it would pass for the file. */
    @Test
    void theWorkedExampleDoesNotSaveAnErrorPageAsTheDownload(@TempDir Path directory)
            throws IOException, InterruptedException {
        HttpServer storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        storage.createContext("/", exchange -> {
            byte[] page = "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        storage.start();
        try {
            scriptTheWorkedExample("http://127.0.0.1:" + storage.getAddress().getPort() + "/expired", DISPATCHED);
            Path file = Harness.contentFile(4096);
            Path.of(file + ".es-es.properties").toFile().deleteOnExit();
            Process process = startIn(directory, harness.apiKey, harness.baseUrl,
                    "examples/worked/WorkedExample.java", file.toString());
            String output = outputOf(process);
            assertNotEquals(0, process.exitValue(), output);
            assertTrue(output.contains("403"), output);
            try (Stream<Path> saved = Files.list(directory)) {
                assertEquals(List.of(), saved.collect(Collectors.toList()), "an error page was saved as a download");
            }
        } finally {
            storage.stop(0);
        }
    }

    @Test
    void aMissingKeyFailsWithoutARequest() throws IOException, InterruptedException {
        harness.reset();
        Process process = start(null, harness.baseUrl, "examples/smoke/Smoke.java");
        String output = outputOf(process);
        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("RASK_API_KEY"), output);
        assertEquals(List.of(), harness.requests());
    }

    @Test
    void aMissingBaseUrlFailsWithoutARequest() throws IOException, InterruptedException {
        harness.reset();
        Process process = start(harness.apiKey, null, "examples/smoke/Smoke.java");
        String output = outputOf(process);
        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("RASK_BASE_URL"), output);
        assertFalse(output.contains("getMe"), output);
        assertEquals(List.of(), harness.requests());
    }

    /**
     * What the program printed, once it has ended. The output is read on another thread: read here first, a program
     * that never ended would hold the test for ever, and the wait below would never start. One that hangs is killed.
     */
    private static String outputOf(Process process) throws InterruptedException {
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the program did not end in 60 s");
        }
        return new String(output.join(), StandardCharsets.UTF_8);
    }

    private static Process start(String apiKey, String baseUrl, String... program) throws IOException {
        // Anything the program saves lands in a temporary directory, not in this repository.
        return startIn(Files.createTempDirectory("rask-sdk-example-"), apiKey, baseUrl, program);
    }

    private static Process startIn(Path directory, String apiKey, String baseUrl, String... program)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/classes").toAbsolutePath().toString(),
                Path.of(program[0]).toAbsolutePath().toString()));
        command.addAll(List.of(program).subList(1, program.length));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .directory(directory.toFile());
        builder.environment().remove("RASK_API_KEY");
        builder.environment().remove("RASK_LOG");
        builder.environment().remove("RASK_BASE_URL");
        if (baseUrl != null) {
            builder.environment().put("RASK_BASE_URL", baseUrl);
        }
        if (apiKey != null) {
            builder.environment().put("RASK_API_KEY", apiKey);
        }
        return builder.start();
    }
}
