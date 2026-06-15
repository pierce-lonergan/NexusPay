package io.nexuspay.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-14 — direct unit tests for the SSRF brain {@link WebhookUrlValidator}.
 *
 * <p>These run IN-GATE (plain JUnit, no Docker, no Spring). To stay deterministic and network-free,
 * the address-category cases use IP-LITERAL URLs / addresses — {@code InetAddress.getByName("10.0.0.1")}
 * and friends parse the literal WITHOUT a DNS lookup, so the validator's resolution step returns exactly
 * that address and the public/special classification is what is under test. Real DNS is exercised only
 * for the deterministic NXDOMAIN case (an {@code .invalid} host, which RFC 6761 guarantees never
 * resolves) and the public-IP-literal accept case (a literal resolves to itself).</p>
 */
@DisplayName("SEC-14: WebhookUrlValidator SSRF guard")
class WebhookUrlValidatorTest {

    // ---------------------------------------------------------------------------------------------
    // Scheme allow-list: only https. http/file/gopher/ftp all reject.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("scheme allow-list (https only)")
    class SchemeChecks {

        @ParameterizedTest(name = "non-https scheme rejected: {0}")
        @ValueSource(strings = {
                "http://example.com/hook",          // plain http
                "file:///etc/passwd",               // local file
                "gopher://127.0.0.1:70/_internal",  // gopher SSRF classic
                "ftp://example.com/x",              // ftp
        })
        void nonHttpsScheme_rejected(String url) {
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve(url))
                    .isInstanceOf(WebhookUrlValidationException.class)
                    .hasMessageContaining("https");
        }

        @Test
        @DisplayName("blank / null URL rejected")
        void blankUrl_rejected() {
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve(""))
                    .isInstanceOf(WebhookUrlValidationException.class);
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve(null))
                    .isInstanceOf(WebhookUrlValidationException.class);
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve("   "))
                    .isInstanceOf(WebhookUrlValidationException.class);
        }

        @Test
        @DisplayName("malformed URL rejected")
        void malformedUrl_rejected() {
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve("https://exa mple.com/ x"))
                    .isInstanceOf(WebhookUrlValidationException.class);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Address classification via validateAndResolve, using IP-literal hosts (no DNS lookup).
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("non-public address ranges rejected (IP-literal hosts, no DNS)")
    class AddressRangeChecks {

        @ParameterizedTest(name = "non-public target rejected: {0}")
        @ValueSource(strings = {
                "https://127.0.0.1/hook",            // IPv4 loopback
                "https://[::1]/hook",                // IPv6 loopback
                "https://10.0.0.5/hook",             // RFC1918 10/8
                "https://172.16.0.9/hook",           // RFC1918 172.16/12
                "https://192.168.1.10/hook",         // RFC1918 192.168/16
                "https://169.254.1.1/hook",          // link-local
                "https://169.254.169.254/latest/",   // cloud metadata (link-local)
                "https://[fe80::1]/hook",            // IPv6 link-local
                "https://[fc00::1]/hook",            // IPv6 ULA fc00::/7
                "https://[fd00::1]/hook",            // IPv6 ULA fd00::/8
                "https://100.64.0.1/hook",           // CGNAT 100.64/10 (RFC6598)
                "https://100.127.255.254/hook",      // CGNAT upper bound
                "https://0.0.0.0/hook",              // any-local / unspecified
                "https://[::ffff:10.0.0.1]/hook",    // IPv4-mapped IPv6 of a private v4
                "https://[::ffff:127.0.0.1]/hook",   // IPv4-mapped IPv6 of loopback
                "https://224.0.0.1/hook",            // IPv4 multicast
                "https://[ff02::1]/hook",            // IPv6 multicast
        })
        void nonPublicTarget_rejected(String url) {
            assertThatThrownBy(() -> WebhookUrlValidator.validateAndResolve(url))
                    .as("must reject non-public target %s", url)
                    .isInstanceOf(WebhookUrlValidationException.class);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Unresolvable host: fail closed.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("unresolvable host (NXDOMAIN) rejected fail-closed")
    void unresolvableHost_rejected() {
        // RFC 6761: the .invalid TLD is guaranteed never to resolve, so this is deterministic offline.
        assertThatThrownBy(() ->
                WebhookUrlValidator.validateAndResolve("https://no-such-host.invalid/hook"))
                .isInstanceOf(WebhookUrlValidationException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Multi-record host where ANY record is private -> whole URL rejected. Exercised at the
    // classification level (isPublicAddress) since synthesizing a real multi-record DNS answer offline
    // is not possible; the validateAndResolve loop rejects on the first non-public record it sees.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("any private record in a multi-record set is non-public -> whole set rejected")
    void multiRecord_anyPrivate_rejected() throws UnknownHostException {
        InetAddress publicA = InetAddress.getByName("93.184.216.34");   // public literal
        InetAddress privateB = InetAddress.getByName("10.1.2.3");        // private literal
        InetAddress[] mixed = {publicA, privateB};

        // The validateAndResolve loop rejects the whole URL the moment it sees a non-public record;
        // assert the classifier agrees on the building blocks that drive that loop.
        assertThat(WebhookUrlValidator.isPublicAddress(publicA)).isTrue();
        assertThat(WebhookUrlValidator.isPublicAddress(privateB)).isFalse();
        boolean allPublic = true;
        for (InetAddress a : mixed) {
            allPublic &= WebhookUrlValidator.isPublicAddress(a);
        }
        assertThat(allPublic)
                .as("a set containing ANY private record must NOT be considered all-public")
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Positive: a normal public https target is ACCEPTED and returns its resolved addresses.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("public https target ACCEPTED and returns pin-able resolved addresses")
    void publicHttpsTarget_accepted() {
        // A public IP literal resolves to itself with no DNS — deterministic and network-free.
        List<InetAddress> resolved =
                WebhookUrlValidator.validateAndResolve("https://93.184.216.34/webhooks");

        assertThat(resolved).isNotEmpty();
        assertThat(resolved).allSatisfy(a ->
                assertThat(WebhookUrlValidator.isPublicAddress(a)).isTrue());
    }

    @Test
    @DisplayName("validateAndResolve does not throw for a public literal")
    void publicLiteral_doesNotThrow() {
        assertThatCode(() -> WebhookUrlValidator.validateAndResolve("https://8.8.8.8/hook"))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // isPublicAddress direct classification sanity.
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("isPublicAddress classification")
    class IsPublicAddressChecks {

        @Test
        void nullAddress_isNotPublic() {
            assertThat(WebhookUrlValidator.isPublicAddress(null)).isFalse();
        }

        @ParameterizedTest(name = "non-public literal: {0}")
        @ValueSource(strings = {
                "127.0.0.1", "::1", "10.0.0.1", "172.31.255.255", "192.168.0.1",
                "169.254.169.254", "fe80::1", "fc00::1", "fd12:3456::1",
                "100.64.0.1", "0.0.0.0", "::ffff:10.0.0.1", "224.0.0.1", "ff02::1",
        })
        void nonPublicLiterals(String literal) throws UnknownHostException {
            assertThat(WebhookUrlValidator.isPublicAddress(InetAddress.getByName(literal)))
                    .as("%s must classify as non-public", literal)
                    .isFalse();
        }

        @ParameterizedTest(name = "public literal: {0}")
        @ValueSource(strings = {
                "8.8.8.8", "1.1.1.1", "93.184.216.34", "99.99.99.99",
                "100.63.255.255",   // just BELOW CGNAT 100.64/10 -> public
                "100.128.0.1",      // just ABOVE CGNAT 100.64/10 -> public
                "2606:2800:220:1:248:1893:25c8:1946",  // a public IPv6
        })
        void publicLiterals(String literal) throws UnknownHostException {
            assertThat(WebhookUrlValidator.isPublicAddress(InetAddress.getByName(literal)))
                    .as("%s must classify as public", literal)
                    .isTrue();
        }
    }
}
