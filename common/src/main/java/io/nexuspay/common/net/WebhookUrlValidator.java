package io.nexuspay.common.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Shared SSRF guard for merchant-supplied outbound webhook target URLs (SEC-14).
 *
 * <p>One validator serves BOTH enforcement points: webhook REGISTRATION (gateway-api Bean Validation
 * via {@code @SafeWebhookUrl}) and webhook DELIVERY ({@code WebhookDeliveryService} calls
 * {@link #validateAndResolve(String)} immediately before each POST). Registration alone is bypassable
 * by DNS rebinding — a host that resolves to a public IP at registration can rebind to an internal
 * IP before delivery — so the delivery-time re-validation is mandatory, not redundant.</p>
 *
 * <p>The validation is fail-closed: any parse error, scheme that is not {@code https}, unresolvable
 * host (NXDOMAIN / {@link UnknownHostException}), or ANY resolved address that is non-public/special
 * causes the WHOLE URL to be rejected. Rejecting on a single bad record in a multi-record DNS answer
 * defends against split-horizon answers where one A/AAAA record is public and another is internal.</p>
 *
 * <p>The delivery path uses {@link #validateAndResolve(String)} which returns the validated
 * {@link InetAddress} set computed from a SINGLE resolution, so the caller can pin the connection to
 * exactly the IP it validated (resolve-then-validate-then-connect-to-that-IP) and close the
 * resolve/connect rebinding gap. No second DNS lookup is performed.</p>
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {
    }

    /**
     * Validates a merchant webhook URL and returns the set of resolved addresses (from a single DNS
     * resolution) that the caller may pin a connection to.
     *
     * @param url the merchant-supplied target URL
     * @return the validated, non-empty list of resolved addresses (all confirmed public)
     * @throws WebhookUrlValidationException if the URL is malformed, not https, unresolvable, or
     *                                       resolves to any loopback/private/link-local/ULA/CGNAT/
     *                                       multicast/metadata/special address
     */
    public static List<InetAddress> validateAndResolve(String url) {
        if (url == null || url.isBlank()) {
            throw new WebhookUrlValidationException("Webhook URL must not be blank");
        }

        // 1. Parse with java.net.URI; reject malformed.
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new WebhookUrlValidationException("Webhook URL is malformed: " + e.getMessage());
        }

        // 2. Scheme allowlist: require https. Rejects all http:// vectors AND file/gopher/ftp on
        //    scheme alone.
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new WebhookUrlValidationException(
                    "Webhook URL must use https (got: " + scheme + ")");
        }

        // 3. Extract host via URI.getHost() — strips the port and handles bracketed IPv6 literals
        //    (e.g. [::1]) and host:port forms without raw string splitting.
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new WebhookUrlValidationException("Webhook URL has no host");
        }

        // 4. Resolve EVERY A/AAAA record. NXDOMAIN / UnknownHostException => REJECT (fail-closed,
        //    so e.g. metadata.google.internal rejects even where it does not resolve in CI).
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new WebhookUrlValidationException(
                    "Webhook host could not be resolved (rejected fail-closed): " + host);
        }
        if (resolved.length == 0) {
            throw new WebhookUrlValidationException("Webhook host resolved to no addresses: " + host);
        }

        // 5. Every resolved address must be public — one bad record rejects the whole URL.
        for (InetAddress address : resolved) {
            if (!isPublicAddress(address)) {
                throw new WebhookUrlValidationException(
                        "Webhook host resolves to a non-public address: " + address.getHostAddress());
            }
        }

        return List.of(resolved);
    }

    /**
     * @return {@code true} only if the address is a routable public address — i.e. NONE of the
     *         loopback/any-local/link-local/site-local/ULA/CGNAT/multicast/IPv4-mapped-internal
     *         predicates match.
     */
    public static boolean isPublicAddress(InetAddress address) {
        if (address == null) {
            return false;
        }

        // Unwrap IPv4-mapped IPv6 (::ffff:0:0/96) to the underlying v4 and re-check, so an attacker
        // cannot smuggle 127.0.0.1 past the v6 predicates as ::ffff:127.0.0.1.
        byte[] raw = address.getAddress();
        if (raw.length == 16 && isIpv4Mapped(raw)) {
            byte[] v4 = new byte[]{raw[12], raw[13], raw[14], raw[15]};
            try {
                return isPublicAddress(InetAddress.getByAddress(v4));
            } catch (UnknownHostException e) {
                // 4-byte literal address never triggers a lookup; treat as non-public if it somehow does.
                return false;
            }
        }

        // JDK predicates: loopback (127/8, ::1), any-local (0.0.0.0, ::), link-local
        // (169.254/16 incl. 169.254.169.254, fe80::/10), site-local (10/8, 172.16/12, 192.168/16),
        // multicast.
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        // Explicit byte-range checks for ranges the JDK predicates miss.
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // 169.254.169.254 cloud metadata (already covered by link-local, asserted for defense-in-depth).
            if (b0 == 169 && b1 == 254) {
                return false;
            }
            // Carrier-grade NAT / shared address space 100.64.0.0/10 (RFC 6598).
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return false;
            }
        } else if (raw.length == 16) {
            // ULA fc00::/7 — first byte high 7 bits == 1111110x.
            if ((raw[0] & 0xFE) == 0xFC) {
                return false;
            }
        }

        return true;
    }

    /** @return true if the 16-byte address is an IPv4-mapped IPv6 address (::ffff:0:0/96). */
    private static boolean isIpv4Mapped(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }
}
