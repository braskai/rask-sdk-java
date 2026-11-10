package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.internal.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs the example programs in a child JVM, with the key and base URL in its real process environment: the proof that
 * RASK_API_KEY and RASK_BASE_URL work outside the injectable environment the other tests use.
 */
class SmokeProgramTest {
    private final Harness harness = Harness.shared();

    @Test
    void readsTheProcessEnvironmentAndPrintsOneLinePerCall() throws IOException, InterruptedException {
        harness.reset();
        harness.post("/pages", Map.of("operationId", "listProjects", "pages", 2, "page_size", 1));
        Process process = start(harness.apiKey, harness.baseUrl + "/", "examples/smoke/Smoke.java");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
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

    /** The README's example, whole: upload, wait, createProject, wait, list the downloads. */
    @Test
    void theWorkedExampleRunsEndToEnd() throws IOException, InterruptedException {
        harness.reset();
        harness.post("/jobs", Map.of("id", "job-import", "type", "media_import",
                "states", List.of("running", "succeeded"),
                "retry_after", 1));
        harness.post("/jobs", Map.of("id", "job-project", "type", "project_processing",
                "states", List.of("succeeded")));
        harness.script("completeUpload", Map.of("job", "job-import"));
        harness.script("createProject", Map.of("job", "job-project"));
        // The contract's example URLs point off this machine: send them to the harness's other host instead.
        Rask rask = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of()).build();
        String outputs = rask.exports().listProjectOutputs("p").data().toJson().replaceAll(
                "\"url\":\"[^\"]*\"", "\"url\":\"" + harness.otherBaseUrl + "/download\"");
        assertFalse(outputs.contains("\"url\":\"https:"), outputs);
        harness.delete("/requests");
        harness.script("listProjectOutputs", Map.of("body", Json.parse(outputs)));
        Path file = Harness.contentFile(4096);
        Process process = start(harness.apiKey, harness.baseUrl, "examples/worked/WorkedExample.java", file.toString());
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertFalse(output.contains(harness.apiKey), "the key was printed");
        List<String> order = new ArrayList<>();
        for (Map<String, Object> record : harness.requests()) {
            assertEquals(List.of(), record.get("violations"), String.valueOf(record.get("operationId")));
            String id = String.valueOf(record.get("operationId"));
            if (order.isEmpty() || !id.equals(order.get(order.size() - 1))) {
                order.add(id);
            }
        }
        assertEquals(List.of("createUpload", "__storage_put", "completeUpload", "getJob", "createProject", "getJob",
                "listProjectOutputs"), order.subList(0, 7));
        assertTrue(output.contains(" 200 "), output);
        boolean downloaded = false;
        for (Map<String, Object> record : harness.requests()) {
            if (String.valueOf(record.get("path")).equals("/download")) {
                downloaded = true;
                assertFalse(((Map<?, ?>) record.get("headers")).containsKey("authorization"));
            }
        }
        assertTrue(downloaded, output);
    }

    @Test
    void aMissingKeyFailsWithoutARequest() throws IOException, InterruptedException {
        harness.reset();
        Process process = start(null, harness.baseUrl, "examples/smoke/Smoke.java");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("RASK_API_KEY"), output);
        assertEquals(List.of(), harness.requests());
    }

    @Test
    void aMissingBaseUrlFailsWithoutARequest() throws IOException, InterruptedException {
        harness.reset();
        Process process = start(harness.apiKey, null, "examples/smoke/Smoke.java");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue(), output);
        assertTrue(output.contains("RASK_BASE_URL"), output);
        assertFalse(output.contains("getMe"), output);
        assertEquals(List.of(), harness.requests());
    }

    private static Process start(String apiKey, String baseUrl, String... program) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/classes").toAbsolutePath().toString(),
                Path.of(program[0]).toAbsolutePath().toString()));
        command.addAll(List.of(program).subList(1, program.length));
        // Anything the program saves lands in a temporary directory, not in this repository.
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .directory(Files.createTempDirectory("rask-sdk-example-").toFile());
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
