package ai.rask.sdk;

import ai.rask.sdk.errors.RaskConfigError;
import ai.rask.sdk.internal.Transport;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;

/**
 * The Rask API v3 client. Thread-safe; create one and share it.
 *
 * <pre>{@code
 * Rask rask = Rask.builder().apiKey("raskai_live_key_...").build();
 * Me me = rask.account().getMe().data();
 * }</pre>
 *
 * <p>The key comes from {@link Builder#apiKey(String)} or {@code RASK_API_KEY}, the base URL from
 * {@link Builder#baseUrl(String)} or {@code RASK_BASE_URL}, and the argument wins in both cases.
 */
public final class Rask extends RaskResources {
    /** The base URL used when neither the builder nor {@code RASK_BASE_URL} sets one. */
    public static final String DEFAULT_BASE_URL = "https://api.rask.ai/v3";
    /** The timeout of one attempt unless changed: the server's 25 s budget plus a 20 s long-poll, with a margin. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** How long retries of one call may take unless changed. */
    public static final Duration DEFAULT_RETRY_BUDGET = Duration.ofSeconds(120);

    private static final System.Logger LOGGER = System.getLogger("ai.rask.sdk");

    private final Transport transport;

    private Rask(Transport transport) {
        super(transport);
        this.transport = transport;
    }

    /**
     * Starts a client.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the base URL every operation and helper uses, with no trailing slash.
     *
     * @return the base URL
     */
    public String baseUrl() {
        return transport.baseUrl();
    }

    /**
     * Returns the timeout of one attempt.
     *
     * @return the timeout
     */
    public Duration timeout() {
        return transport.timeout();
    }

    /**
     * Returns how long retries of one call may take.
     *
     * @return the budget
     */
    public Duration retryBudget() {
        return transport.retryBudget();
    }

    /** Shows the configuration, never the key. */
    @Override
    public String toString() {
        return "Rask{baseUrl=" + baseUrl() + ", timeout=" + timeout() + ", apiKey=***}";
    }

    /** Builds {@link Rask}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration retryBudget = DEFAULT_RETRY_BUDGET;
        private Map<String, String> environment;
        private Boolean debug;
        private Consumer<String> logSink;
        private Consumer<String> warningSink;
        private ProxySelector proxy;
        private SSLContext sslContext;

        private Builder() {
        }

        /**
         * Sets the API key. Without it {@code RASK_API_KEY} is read.
         *
         * @param value the key
         * @return this builder
         */
        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        /**
         * Sets the base URL, for example {@code https://gw.example.com/rask/v3}. Its path prefix is kept and a trailing
         * slash does not matter. Without it {@code RASK_BASE_URL} is read, then {@link Rask#DEFAULT_BASE_URL}.
         *
         * @param value the base URL
         * @return this builder
         */
        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        /**
         * Sets the timeout of one attempt.
         *
         * @param value the timeout; the default is {@link Rask#DEFAULT_TIMEOUT}
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * Sets how long retries of one call may take; {@link Duration#ZERO} turns retries off.
         *
         * @param value the budget; the default is {@link Rask#DEFAULT_RETRY_BUDGET}
         * @return this builder
         */
        public Builder retryBudget(Duration value) {
            this.retryBudget = value;
            return this;
        }

        /**
         * Replaces the process environment as the source of {@code RASK_API_KEY}, {@code RASK_BASE_URL} and
         * {@code RASK_LOG}, for tests and for applications that keep their settings elsewhere.
         *
         * @param value the variables
         * @return this builder
         */
        public Builder environment(Map<String, String> value) {
            this.environment = value;
            return this;
        }

        /**
         * Turns traffic logging on or off, overriding {@code RASK_LOG=debug}. The log redacts the key and presigned
         * signatures and holds no bodies.
         *
         * @param value whether to log
         * @return this builder
         */
        public Builder debug(boolean value) {
            this.debug = value;
            return this;
        }

        /**
         * Receives debug log lines instead of standard error.
         *
         * @param value the receiver
         * @return this builder
         */
        public Builder logSink(Consumer<String> value) {
            this.logSink = value;
            return this;
        }

        /**
         * Receives warnings, such as a deprecated operation, instead of the {@code ai.rask.sdk} system logger.
         *
         * @param value the receiver
         * @return this builder
         */
        public Builder warningSink(Consumer<String> value) {
            this.warningSink = value;
            return this;
        }

        /**
         * Sends traffic through a proxy.
         *
         * @param value the proxy selector
         * @return this builder
         */
        public Builder proxy(ProxySelector value) {
            this.proxy = value;
            return this;
        }

        /**
         * Uses a TLS context, for example with a private certificate authority.
         *
         * @param value the context
         * @return this builder
         */
        public Builder sslContext(SSLContext value) {
            this.sslContext = value;
            return this;
        }

        /**
         * Creates the client.
         *
         * @return the client
         * @throws RaskConfigError when there is no API key or the base URL cannot work
         */
        public Rask build() {
            Map<String, String> env = environment != null ? environment : System.getenv();
            Transport.Settings settings = new Transport.Settings();
            String key = firstNonBlank(apiKey, env.get("RASK_API_KEY"));
            if (key == null) {
                throw new RaskConfigError("No API key: call Rask.builder().apiKey(...) or set RASK_API_KEY");
            }
            settings.apiKey = key.trim();
            // The HTTP client quotes an invalid header value in its exception, so a bad key must stop here.
            if (!settings.apiKey.matches("[\\x21-\\x7E]+")) {
                throw new RaskConfigError("The API key holds spaces or control characters: check how it is read");
            }
            settings.baseUrl = normalizeBaseUrl(firstNonBlank(baseUrl, env.get("RASK_BASE_URL"), DEFAULT_BASE_URL));
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new RaskConfigError("timeout must be positive");
            }
            if (retryBudget == null || retryBudget.isNegative()) {
                throw new RaskConfigError("retryBudget must not be negative");
            }
            settings.timeout = timeout;
            settings.retryBudget = retryBudget;
            String logEnv = env.get("RASK_LOG");
            settings.debug = debug != null ? debug : logEnv != null && "debug".equals(logEnv.toLowerCase(Locale.ROOT));
            settings.logSink = logSink != null ? logSink : System.err::println;
            settings.warningSink = warningSink != null
                    ? warningSink : message -> LOGGER.log(System.Logger.Level.WARNING, message);
            settings.proxy = proxy;
            settings.sslContext = sslContext;
            warnOnCleartext(settings);
            return new Rask(new Transport(settings));
        }

        private static void warnOnCleartext(Transport.Settings settings) {
            URI uri = URI.create(settings.baseUrl);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean loopback = "localhost".equals(host) || host.endsWith(".localhost")
                    || host.startsWith("127.") || "[::1]".equals(host);
            if ("http".equalsIgnoreCase(uri.getScheme()) && !loopback) {
                settings.warningSink.accept("The API key is sent in clear text: the base URL " + settings.baseUrl
                        + " is not HTTPS");
            }
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return null;
        }

        private static String normalizeBaseUrl(String value) {
            String url = value.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new RaskConfigError("Base URL is not a URL: " + e.getReason());
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new RaskConfigError("Base URL must start with http:// or https://");
            }
            if (uri.getHost() == null) {
                throw new RaskConfigError("Base URL has no host the JDK accepts (an underscore in a host name, too)");
            }
            if (uri.getRawUserInfo() != null) {
                throw new RaskConfigError("Base URL must not hold credentials: use apiKey(...)");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new RaskConfigError("Base URL must not have a query string or a fragment");
            }
            return url;
        }
    }
}
