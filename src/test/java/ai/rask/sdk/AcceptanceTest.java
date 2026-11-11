package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.errors.RaskError;
import ai.rask.sdk.internal.Json;
import ai.rask.sdk.model.RaskValue;
import ai.rask.sdk.model.UploadContentType;
import ai.rask.sdk.resources.JobsResource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The loop of the harness README: every applicable scenario, over real HTTP, on a custom base URL with a path prefix.
 * RUN_SLOW=1 adds the two slow scenarios. The last test asks the harness whether anything was left out.
 */
class AcceptanceTest {
    private static final String LANG = "java";
    private static final Set<String> LOCAL = Set.of("127.0.0.1", "localhost", "[::1]");

    @TestFactory
    Stream<DynamicTest> scenarios() {
        Harness harness = Harness.shared();
        harness.delete("/report");
        boolean slow = "1".equals(System.getenv("RUN_SLOW"));
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> scenario : list(harness.get("/scenarios?lang=" + LANG).get("scenarios"))) {
            if (Boolean.TRUE.equals(scenario.get("applies")) && (slow || !Boolean.TRUE.equals(scenario.get("slow")))) {
                String id = (String) scenario.get("id");
                tests.add(DynamicTest.dynamicTest(id, () -> runScenario(harness, id)));
            }
        }
        tests.add(DynamicTest.dynamicTest("report is complete", () -> {
            Map<String, Object> report = harness.get("/report?lang=" + LANG + (slow ? "&slow=true" : ""));
            assertEquals(List.of(), report.get("failed"), "failed");
            assertEquals(List.of(), report.get("not_run"), "not run");
            assertEquals(Boolean.TRUE, report.get("complete"), Json.write(report));
        }));
        return tests.stream();
    }

    // The runner itself. What it cannot run or check must come out as a failure with a reason, not as an exception from
    // the middle of the runner: /verify never hears of a scenario that ended that way.

    @Test
    void anExpectationOfACallThatNeverRanIsAFailure() throws ReflectiveOperationException {
        Run run = new Run(); // As after a client that could not be built: no calls.
        for (String kind : List.of("returned", "raised", "field", "header_exposed", "idempotency_key_exposed",
                "items", "pages", "progress", "injected_values_preserved")) {
            assertFalse(check(Map.of("kind", kind), run, List.of()).isEmpty(), kind);
        }
    }

    @Test
    void aNextPageAfterANextPageKeepsThePathTheFiltersAndTheNewCursor() throws Throwable {
        Harness harness = Harness.shared();
        harness.reset();
        harness.post("/pages", Map.of("operationId", "listSegments", "pages", 4, "page_size", 1));
        Run run = new Run();
        run.operations = harness.operations();
        run.client = Rask.builder().apiKey(harness.apiKey).baseUrl(harness.baseUrl).environment(Map.of()).build();
        TypedCalls typed = new TypedCalls(run.client, run.operations);
        List<Map<String, Object>> calls = List.of(
                Map.of("helper", "first_page", "op", "listSegments", "path", Map.of("project_id", "prj-1"),
                        "query", Map.of("limit", 1, "q", "hola")),
                Map.of("helper", "next_page", "after_call", 0),
                Map.of("helper", "next_page", "after_call", 1));
        for (Map<String, Object> call : calls) {
            Outcome outcome = new Outcome();
            outcome.call = call;
            runCall(run, typed, call, outcome);
            run.calls.add(outcome);
        }
        List<String> cursors = new ArrayList<>();
        for (Map<String, Object> record : harness.requests()) {
            assertEquals(harness.prefix + "/projects/prj-1/segments", record.get("path"));
            Map<String, Object> query = new LinkedHashMap<>(map(record.get("raw_query_params")));
            cursors.add(String.valueOf(query.remove("cursor")));
            assertEquals(Map.of("limit", "1", "q", "hola"), query);
        }
        assertEquals(3, cursors.size());
        assertEquals("null", cursors.get(0));
        assertEquals(3, Set.copyOf(cursors).size(), () -> "each page has a cursor of its own: " + cursors);
    }

    private static void runScenario(Harness harness, String id) throws Exception {
        Map<String, Object> scenario = harness.post("/scenarios/" + id + "/start", Map.of("lang", LANG));
        Run run = new Run();
        run.operations = list(harness.get("/operations").get("operations"));
        Map<String, Object> client = map(scenario.get("client"));
        try {
            run.client = build(map(client.get("args")), map(client.get("env")), run);
        } catch (RuntimeException e) {
            run.constructError = e;
        }
        if (run.client != null) {
            // These tests must never leave the machine: a lost base URL would otherwise reach production.
            List<Map<String, Object>> calls = list(scenario.get("calls"));
            String host = URI.create(run.client.baseUrl()).getHost();
            if (!calls.isEmpty() && !LOCAL.contains(host)) {
                throw new IllegalStateException("Refusing to run " + id + " against " + run.client.baseUrl());
            }
            TypedCalls typed = new TypedCalls(run.client, run.operations);
            for (Map<String, Object> call : calls) {
                Outcome outcome = new Outcome();
                outcome.call = call;
                try {
                    runCall(run, typed, call, outcome);
                } catch (RaskError | IllegalArgumentException e) {
                    outcome.error = e;
                } catch (Throwable e) {
                    throw new IllegalStateException("Call " + Json.write(call) + " broke the test: " + e, e);
                }
                run.calls.add(outcome);
            }
        }
        List<Map<String, Object>> records = harness.requests();
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> expectation : list(scenario.get("expect_sdk"))) {
            failures.addAll(check(expectation, run, records));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lang", LANG);
        body.put("sdk_passed", failures.isEmpty());
        body.put("sdk_detail", String.join("; ", failures));
        Map<String, Object> verify = harness.post("/scenarios/" + id + "/verify", body);
        assertEquals(Boolean.TRUE, verify.get("pass"), () -> id + ": " + Json.write(verify.get("failures")));
        assertTrue(failures.isEmpty(), () -> id + ": " + failures);
    }

    private static Rask build(Map<String, Object> args, Map<String, Object> env, Run run) {
        Map<String, String> variables = new LinkedHashMap<>();
        env.forEach((name, value) -> variables.put(name, String.valueOf(value)));
        Rask.Builder builder = Rask.builder().environment(variables)
                .logSink(run.logs::add).warningSink(run.warnings::add);
        if (args.get("api_key") != null) {
            builder.apiKey((String) args.get("api_key"));
        }
        if (args.get("base_url") != null) {
            builder.baseUrl((String) args.get("base_url"));
        }
        if (args.get("timeout_seconds") != null) {
            builder.timeout(Duration.ofSeconds(((Number) args.get("timeout_seconds")).longValue()));
        }
        return builder.build();
    }

    private static void runCall(Run run, TypedCalls typed, Map<String, Object> call, Outcome outcome)
            throws Throwable {
        Map<String, Object> path = map(call.get("path"));
        Map<String, Object> query = map(call.get("query"));
        Map<String, Object> body = map(call.get("body"));
        String op = (String) call.get("op");
        String helper = (String) call.get("helper");
        if (helper == null) {
            outcome.response = typed.call(op, path, query, body, (String) call.get("if_match"),
                    (String) call.get("idempotency_key"));
            return;
        }
        switch (helper) {
            case "jobs.wait": {
                String jobId = (String) call.get("job_id");
                if (jobId == null) {
                    int from = ((Number) call.get("job_id_from_call")).intValue();
                    jobId = (String) ((RaskValue) run.calls.get(from).response.data()).get("id");
                }
                Number timeout = (Number) call.get("timeout_seconds");
                outcome.response = timeout == null ? run.client.jobs().wait(jobId)
                        : run.client.jobs().wait(jobId, Duration.ofSeconds(timeout.longValue()));
                return;
            }
            case "uploads.upload_file": {
                Map<String, Object> content = map(call.get("content"));
                int size = ((Number) content.get("size_bytes")).intValue();
                UploadFileOptions options = UploadFileOptions.builder()
                        .filename((String) call.get("filename"))
                        .contentType(UploadContentType.of((String) call.get("content_type")))
                        .onProgress(outcome.progress::add)
                        .build();
                if ("stream".equals(call.get("source"))) {
                    outcome.response = run.client.uploads().uploadFile(
                            new ByteArrayInputStream(Harness.content(size)), options);
                } else {
                    Path file = Harness.contentFile(size);
                    outcome.response = run.client.uploads().uploadFile(file, options);
                }
                return;
            }
            case "iterate":
                outcome.items = new ArrayList<>();
                for (Object item : typed.iterate(op, path, query)) {
                    outcome.items.add(item);
                }
                return;
            case "first_page":
                outcome.response = typed.call(op, path, query, null, null, null);
                return;
            case "next_page": {
                Outcome before = run.calls.get(((Number) call.get("after_call")).intValue());
                Map<String, Object> next = new LinkedHashMap<>(map(before.call.get("query")));
                next.put("cursor", ((RaskValue) before.response.data()).get("next_cursor"));
                // The call is kept as it was sent, for a next_page after this one: the scenario's own entry names
                // neither the operation nor its path and filters.
                Map<String, Object> sent = new LinkedHashMap<>(call);
                sent.put("op", before.call.get("op"));
                sent.put("path", before.call.get("path"));
                sent.put("query", next);
                outcome.call = sent;
                outcome.response = typed.call((String) sent.get("op"), map(sent.get("path")), next, null, null, null);
                return;
            }
            case "sweep_operations":
                outcome.results = new LinkedHashMap<>();
                ModelWalker walker = new ModelWalker();
                for (Map<String, Object> operation : run.operations) {
                    if (!Boolean.TRUE.equals(operation.get("has_json_response"))) {
                        continue;
                    }
                    Map<String, Object> sample = map(operation.get("sample"));
                    String id = (String) operation.get("operationId");
                    RaskResponse<?> response = typed.call(id, map(sample.get("path")), map(sample.get("query")),
                            map(sample.get("body")), (String) map(sample.get("headers")).get("If-Match"), null);
                    walker.walk(response.data());
                    outcome.results.put(id, response);
                }
                outcome.walker = walker;
                return;
            default:
                throw new IllegalStateException("Unknown helper " + helper);
        }
    }

    private static List<String> check(Map<String, Object> expectation, Run run, List<Map<String, Object>> records)
            throws ReflectiveOperationException {
        Number index = (Number) expectation.get("call");
        Outcome outcome = run.calls.isEmpty() ? null
                : run.calls.get(index != null ? index.intValue() : run.calls.size() - 1);
        String kind = (String) expectation.get("kind");
        switch (kind) {
            case "returned":
                return outcome != null && outcome.error == null ? List.of()
                        : List.of("call " + index + " raised: " + (outcome == null ? "no call" : outcome.error));
            case "returned_or_raised":
            case "manual":
                return List.of();
            case "raised":
                return checkRaised(expectation, outcome, records);
            case "raised_before_network": {
                Throwable error = run.constructError != null ? run.constructError
                        : run.calls.isEmpty() ? null : run.calls.get(0).error;
                String text = (String) expectation.get("message_contains");
                if (error == null) {
                    return List.of("expected an error before any request");
                }
                return String.valueOf(error.getMessage()).contains(text) ? List.of()
                        : List.of("\"" + error.getMessage() + "\" does not mention " + text);
            }
            case "config":
                return checkConfig(expectation, run);
            case "repr_redacts":
                return run.client != null && !run.client.toString().contains((String) expectation.get("secret"))
                        ? List.of() : List.of("the key shows in the client representation: " + run.constructError);
            case "field":
                return checkField(expectation, outcome);
            case "header_exposed":
                return checkHeader(expectation, outcome, records);
            case "idempotency_key_exposed":
                return checkKey(expectation, outcome, records);
            case "items":
                return checkItems(expectation, outcome);
            case "pages":
                return checkPages(expectation, outcome);
            case "warnings":
                return checkWarnings(expectation, run);
            case "progress": {
                Number expected = (Number) expectation.get("final_bytes");
                boolean reached = outcome != null && !outcome.progress.isEmpty()
                        && outcome.progress.get(outcome.progress.size() - 1) == expected.longValue();
                return reached ? List.of() : List.of("on_progress did not reach the file size");
            }
            case "logs":
                return checkLogs(expectation, run, records);
            case "injected_values_preserved":
                return checkInjected(outcome, records);
            default:
                throw new IllegalStateException("Unknown expectation " + kind);
        }
    }

    private static List<String> checkRaised(Map<String, Object> expectation, Outcome outcome,
            List<Map<String, Object>> records) {
        if (outcome == null || outcome.error == null) {
            return List.of("expected an error, the call returned");
        }
        if (!(outcome.error instanceof RaskError)) {
            return List.of("expected a RaskError, got " + outcome.error);
        }
        RaskError error = (RaskError) outcome.error;
        List<String> failures = new ArrayList<>();
        String expectedClass = (String) expectation.get("error_class");
        if (expectedClass != null) {
            boolean found = false;
            for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
                found |= type.getSimpleName().equals(expectedClass);
            }
            if (!found) {
                failures.add("expected " + expectedClass + ", got " + error.getClass().getSimpleName());
            }
        }
        Number status = (Number) expectation.get("status");
        if (status != null && error.status() != status.intValue()) {
            failures.add("expected status " + status + ", got " + error.status());
        }
        Object code = expectation.get("code");
        if (code != null && !code.equals(error.code())) {
            failures.add("expected code " + code + ", got " + error.code());
        }
        for (Map.Entry<String, Object> field : map(expectation.get("fields")).entrySet()) {
            Object actual = "retry_after".equals(field.getKey())
                    ? error.retryAfter().map(Duration::getSeconds).orElse(null) : error.member(field.getKey());
            if (!JsonEq.same(actual, field.getValue())) {
                failures.add("expected " + field.getKey() + " " + field.getValue() + ", got " + actual);
            }
        }
        Object present = expectation.get("present");
        if (present != null) {
            for (Object name : (List<?>) present) {
                if (error.member((String) name) == null) {
                    failures.add("expected " + name + " on the error");
                }
            }
        }
        if (Boolean.TRUE.equals(expectation.get("request_id_matches_wire"))
                && !wireHas(records, "X-Request-Id", error.requestId())) {
            failures.add("request_id " + error.requestId() + " is not an X-Request-Id the server sent");
        }
        return failures;
    }

    private static boolean wireHas(List<Map<String, Object>> records, String header, String value) {
        for (Map<String, Object> record : records) {
            Map<String, Object> headers = map(map(record.get("response")).get("headers"));
            if (value != null && value.equals(headers.get(header))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> checkConfig(Map<String, Object> expectation, Run run) {
        if (run.client == null) {
            return List.of("no client: " + run.constructError);
        }
        String field = (String) expectation.get("field");
        Object actual;
        switch (field) {
            case "base_url":
                actual = run.client.baseUrl();
                break;
            case "timeout_seconds":
                actual = run.client.timeout().getSeconds();
                break;
            case "jobs.wait.timeout_seconds":
                actual = JobsResource.DEFAULT_WAIT_TIMEOUT.getSeconds();
                break;
            default:
                throw new IllegalStateException("Unknown config field " + field);
        }
        return JsonEq.same(actual, expectation.get("equals")) ? List.of()
                : List.of("config " + field + " is " + actual);
    }

    private static List<String> checkField(Map<String, Object> expectation, Outcome outcome) {
        if (outcome == null || outcome.response == null) {
            return List.of("call raised: " + (outcome == null ? "no call" : outcome.error));
        }
        Number rawStatus = (Number) expectation.get("raw_status");
        if (rawStatus != null && outcome.response.status() != rawStatus.intValue()) {
            return List.of("status is " + outcome.response.status());
        }
        String path = (String) expectation.get("path");
        if (path == null) {
            return List.of();
        }
        Object value = ((RaskValue) outcome.response.data()).pointer(path);
        if (Boolean.TRUE.equals(expectation.get("present"))) {
            return value == null ? List.of(path + " is missing") : List.of();
        }
        return JsonEq.same(value, expectation.get("equals")) ? List.of() : List.of(path + " is " + value);
    }

    private static List<String> checkHeader(Map<String, Object> expectation, Outcome outcome,
            List<Map<String, Object>> records) {
        if (outcome == null) {
            return List.of("no call");
        }
        String name = (String) expectation.get("name");
        String value;
        if (outcome.response != null) {
            value = outcome.response.header(name);
        } else if (outcome.error instanceof RaskError) {
            value = ((RaskError) outcome.error).header(name);
        } else {
            value = null;
        }
        if (value == null) {
            return List.of("header " + name + " is not exposed");
        }
        Object equals = expectation.get("equals");
        if (equals != null && !equals.equals(value)) {
            return List.of("header " + name + ": expected " + equals + ", got " + value);
        }
        String matches = (String) expectation.get("matches");
        if (matches != null && !Pattern.compile(matches).matcher(value).find()) {
            return List.of("header " + name + ": " + value + " does not match " + matches);
        }
        String field = (String) expectation.get("equals_field");
        if (field != null && !value.equals(((RaskValue) outcome.response.data()).pointer(field))) {
            return List.of("header " + name + " differs from " + field);
        }
        if (Boolean.TRUE.equals(expectation.get("matches_wire")) && !wireHas(records, name, value)) {
            return List.of("header " + name + " " + value + " is not what the server sent");
        }
        return List.of();
    }

    private static List<String> checkKey(Map<String, Object> expectation, Outcome outcome,
            List<Map<String, Object>> records) {
        if (outcome == null) {
            return List.of("no call");
        }
        String key = outcome.response != null ? outcome.response.idempotencyKey()
                : outcome.error instanceof RaskError ? ((RaskError) outcome.error).idempotencyKey() : null;
        if (key == null) {
            return List.of("the key is not exposed");
        }
        Object equals = expectation.get("equals");
        if (equals != null && !equals.equals(key)) {
            return List.of("key is " + key);
        }
        if (Boolean.TRUE.equals(expectation.get("matches_wire"))) {
            boolean sent = false;
            for (Map<String, Object> record : records) {
                sent |= key.equals(map(record.get("headers")).get("idempotency-key"));
            }
            if (!sent) {
                return List.of("key " + key + " was never sent");
            }
        }
        return List.of();
    }

    private static List<String> checkItems(Map<String, Object> expectation, Outcome outcome) {
        if (outcome == null || outcome.items == null) {
            return List.of("no items: " + (outcome == null ? "no call" : outcome.error));
        }
        Number count = (Number) expectation.get("count");
        if (count != null && outcome.items.size() != count.intValue()) {
            return List.of("saw " + outcome.items.size() + " items, expected " + count);
        }
        Object ids = expectation.get("ids");
        if (ids != null) {
            List<Object> seen = new ArrayList<>();
            for (Object item : outcome.items) {
                seen.add(((RaskValue) item).get("id"));
            }
            if (!seen.equals(ids)) {
                return List.of("saw ids " + seen);
            }
        }
        return List.of();
    }

    private static List<String> checkPages(Map<String, Object> expectation, Outcome outcome) {
        if (outcome == null || outcome.response == null) {
            return List.of("no page: " + (outcome == null ? "no call" : outcome.error));
        }
        RaskValue page = (RaskValue) outcome.response.data();
        int size = ((List<?>) page.get("data")).size();
        if (size != ((Number) expectation.get("item_count")).intValue()) {
            return List.of("page has " + size + " items");
        }
        return expectation.get("has_more").equals(page.get("has_more")) ? List.of()
                : List.of("has_more is " + page.get("has_more"));
    }

    private static List<String> checkWarnings(Map<String, Object> expectation, Run run) {
        List<String> failures = new ArrayList<>();
        int count = ((Number) expectation.get("count")).intValue();
        if (run.warnings.size() != count) {
            failures.add("saw " + run.warnings + ", expected " + count);
        }
        for (Map.Entry<String, Object> entry : map(expectation.get("per_operation")).entrySet()) {
            long seen = run.warnings.stream().filter(w -> w.contains("operation " + entry.getKey() + " ")).count();
            if (seen != ((Number) entry.getValue()).longValue()) {
                failures.add(entry.getKey() + ": saw " + seen + " warnings");
            }
        }
        return failures;
    }

    private static List<String> checkLogs(Map<String, Object> expectation, Run run,
            List<Map<String, Object>> records) {
        String logs = String.join("\n", run.logs);
        List<String> failures = new ArrayList<>();
        if (Boolean.TRUE.equals(expectation.get("empty")) && !logs.isEmpty()) {
            failures.add("expected no logs, got " + logs);
        }
        for (Object text : listOf(expectation.get("must_contain"))) {
            if (!logs.contains((String) text)) {
                failures.add("logs do not contain " + text + ": " + logs);
            }
        }
        for (Object text : listOf(expectation.get("must_not_contain"))) {
            if (logs.contains((String) text)) {
                failures.add("logs contain a secret");
            }
        }
        for (Map<String, Object> wire : list(expectation.get("must_not_contain_wire"))) {
            for (Map<String, Object> record : records) {
                if (wire.get("operationId").equals(record.get("operationId"))) {
                    Object value = map(record.get("raw_query_params")).get(wire.get("query"));
                    if (value != null && logs.contains(String.valueOf(value))) {
                        failures.add("logs contain " + wire.get("query"));
                    }
                }
            }
        }
        return failures;
    }

    private static List<String> checkInjected(Outcome outcome, List<Map<String, Object>> records)
            throws ReflectiveOperationException {
        if (outcome == null) {
            return List.of("no call");
        }
        List<String> failures = new ArrayList<>();
        boolean injected = false;
        for (Map<String, Object> record : records) {
            List<Map<String, Object>> injections = list(map(record.get("response")).get("injections"));
            if (injections.isEmpty()) {
                continue;
            }
            injected = true;
            String id = (String) record.get("operationId");
            RaskResponse<?> response = outcome.results != null ? outcome.results.get(id) : outcome.response;
            if (response == null) {
                failures.add(id + " returned nothing");
                continue;
            }
            for (Map<String, Object> injection : injections) {
                String pointer = (String) injection.get("pointer");
                Object actual = ((RaskValue) response.data()).pointer(pointer);
                if (!JsonEq.same(actual, injection.get("value"))) {
                    failures.add(id + " lost the " + injection.get("kind") + " injected at " + pointer);
                }
                String typed = ModelWalker.checkTyped(response.data(), pointer, (String) injection.get("kind"));
                if (typed != null) {
                    failures.add(id + ": " + typed);
                }
            }
        }
        if (!injected) {
            failures.add("nothing was injected");
        }
        // Reading the typed getters must survive too.
        if (outcome.walker == null && outcome.response != null) {
            new ModelWalker().walk(outcome.response.data());
        }
        return failures;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    private static List<?> listOf(Object value) {
        return value instanceof List ? (List<?>) value : List.of();
    }

    private static final class Run {
        Rask client;
        RuntimeException constructError;
        List<Map<String, Object>> operations;
        final List<Outcome> calls = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    private static final class Outcome {
        Map<String, Object> call;
        RaskResponse<?> response;
        Throwable error;
        List<Object> items;
        Map<String, RaskResponse<?>> results;
        ModelWalker walker;
        final List<Long> progress = new CopyOnWriteArrayList<>();
    }
}
