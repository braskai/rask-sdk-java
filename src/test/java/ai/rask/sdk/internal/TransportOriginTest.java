package ai.rask.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * What decides whether the key may follow a redirect. A server cannot speak TLS and plain HTTP on one port, so the case
 * of the scheme alone differing cannot be shown over a socket; HttpsTest covers the scheme and port differing together.
 */
class TransportOriginTest {
    private static boolean same(String a, String b) {
        return Transport.sameOrigin(URI.create(a), URI.create(b));
    }

    @Test
    void theSameSchemeHostAndPortAreTheSameOrigin() {
        assertTrue(same("https://gw.example.com/rask/v3", "https://gw.example.com/other"));
        assertTrue(same("https://gw.example.com/v3", "HTTPS://GW.example.com:443/v3"));
        assertTrue(same("http://gw.example.com:80/v3", "http://gw.example.com/v3"));
    }

    @Test
    void theKeyStaysUnderTheBaseUrlsPathToo() {
        URI base = URI.create("https://gw.example.com/rask/v3");
        assertTrue(Transport.underBase(base, URI.create("https://gw.example.com/rask/v3/me")));
        assertTrue(Transport.underBase(base, URI.create("https://gw.example.com/rask/v3")));
        assertTrue(Transport.underBase(base, URI.create("https://gw.example.com/rask/v3/a/../me")));
        assertFalse(Transport.underBase(base, URI.create("https://gw.example.com/other-service/me")));
        assertFalse(Transport.underBase(base, URI.create("https://gw.example.com/rask/v3/../../other")));
        assertFalse(Transport.underBase(base, URI.create("https://gw.example.com/rask/v3-other/me")));
        assertFalse(Transport.underBase(base, URI.create("https://gw.example.com/rask")));
        assertFalse(Transport.underBase(base, URI.create("https://other.example.com/rask/v3/me")));
        assertTrue(Transport.underBase(URI.create("https://gw.example.com"), URI.create("https://gw.example.com/me")));
    }

    @Test
    void anythingElseIsAnotherOrigin() {
        assertFalse(same("https://gw.example.com:8443/v3", "http://gw.example.com:8443/v3"), "scheme alone");
        assertFalse(same("https://gw.example.com/v3", "http://gw.example.com:443/v3"), "scheme alone, implied port");
        assertFalse(same("https://gw.example.com/v3", "https://other.example.com/v3"), "host");
        assertFalse(same("https://gw.example.com/v3", "https://gw.example.com.evil.test/v3"), "host suffix");
        assertFalse(same("https://gw.example.com/v3", "https://gw.example.com:8443/v3"), "port");
    }
}
