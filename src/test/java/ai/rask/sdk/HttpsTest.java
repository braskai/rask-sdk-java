package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.errors.RaskConnectionError;
import ai.rask.sdk.model.Me;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness speaks plain HTTP, so this covers what only TLS can show: an HTTPS base URL with a prefix, a private
 * certificate authority through sslContext, and a redirect from HTTPS to HTTP on the same host, which is another origin
 * and must not carry the key.
 */
class HttpsTest {
    private static final String KEY = "raskai_test_key_https";
    private static final String PASSWORD = "changeit";

    @TempDir
    Path directory;

    private HttpsServer https;
    private HttpServer http;
    private SSLContext context;
    private final List<String> httpsSeen = Collections.synchronizedList(new ArrayList<>());
    private final List<String> httpSeen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        Path store = directory.resolve("store.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD, "-validity", "2")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store)) {
            keys.load(input, PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD.toCharArray());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keys);
        context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);

        http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        http.createContext("/", exchange -> answer(exchange, httpSeen));
        http.start();
        https = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(context));
        https.createContext("/rask/v3/me", exchange -> answer(exchange, httpsSeen));
        https.createContext("/rask/v3/plan", exchange -> {
            httpsSeen.add(describe(exchange));
            exchange.getResponseHeaders().set("Location", "http://localhost:" + http.getAddress().getPort() + "/plan");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        https.start();
    }

    @AfterEach
    void stop() {
        https.stop(0);
        http.stop(0);
    }

    private static String describe(HttpExchange exchange) {
        return exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization");
    }

    private static void answer(HttpExchange exchange, List<String> seen) throws IOException {
        seen.add(describe(exchange));
        byte[] body = "{\"object\":\"me\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String baseUrl() {
        return "https://localhost:" + https.getAddress().getPort() + "/rask/v3/";
    }

    @Test
    void anHttpsBaseUrlKeepsItsPrefixAndUsesThePrivateAuthority() {
        Rask rask = Rask.builder().apiKey(KEY).baseUrl(baseUrl()).environment(Map.of()).sslContext(context).build();
        RaskResponse<Me> response = rask.account().getMe();
        assertEquals(200, response.status());
        assertEquals(List.of("/rask/v3/me Bearer " + KEY), httpsSeen);
    }

    @Test
    void anUntrustedCertificateIsAnErrorNotAFallback() {
        Rask rask = Rask.builder().apiKey(KEY).baseUrl(baseUrl()).environment(Map.of())
                .retryBudget(Duration.ZERO).build();
        assertThrows(RaskConnectionError.class, () -> rask.account().getMe());
        assertEquals(List.of(), httpsSeen);
    }

    /**
     * The certificate is the same on every attempt, so a retry only delays the error, and a wait that took it for an
     * outage would end hours later as a timeout, without its cause.
     */
    @Test
    void anUntrustedCertificateEndsACallAndAWaitAtOnce() {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        Rask rask = Rask.builder().apiKey(KEY).baseUrl(baseUrl()).environment(Map.of())
                .retryBudget(Duration.ofSeconds(5)).debug(true).logSink(logs::add).build();
        assertThrows(RaskConnectionError.class, () -> rask.account().getMe());
        RaskConnectionError error = assertThrows(RaskConnectionError.class,
                () -> rask.jobs().wait("job-1", Duration.ofSeconds(5)));
        assertTrue(error.getCause() instanceof SSLException, () -> String.valueOf(error.getCause()));
        assertEquals(2, logs.stream().filter(line -> line.contains("> GET ")).count(),
                () -> "one attempt of the call and one of the wait: " + logs);
        assertEquals(List.of(), httpsSeen);
    }

    @Test
    void aRedirectFromHttpsToHttpOnTheSameHostDropsTheKey() {
        Rask rask = Rask.builder().apiKey(KEY).baseUrl(baseUrl()).environment(Map.of()).sslContext(context).build();
        assertEquals(200, rask.account().getPlan().status());
        assertEquals(List.of("/rask/v3/plan Bearer " + KEY), httpsSeen);
        assertEquals(List.of("/plan null"), httpSeen);
    }
}
