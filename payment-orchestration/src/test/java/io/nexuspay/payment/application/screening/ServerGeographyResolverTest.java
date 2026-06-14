package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import io.nexuspay.payment.application.screening.ServerGeographyResolver.ResolvedGeography;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-025: the resolver derives geography from SERVER-authoritative sources and treats client
 * metadata as advisory-only. A caller must NOT be able to drive the sanctions destination via
 * the request, nor inject a "trusted" source.
 */
class ServerGeographyResolverTest {

    private MerchantCurrencyPrefsRepository prefs(String tenantId, String merchantCountry) {
        MerchantCurrencyPrefsRepository r = mock(MerchantCurrencyPrefsRepository.class);
        if (merchantCountry == null && tenantId == null) {
            when(r.findByTenantId(anyString())).thenReturn(Optional.empty());
            return r;
        }
        MerchantCurrencyPrefs p = new MerchantCurrencyPrefs(
                UUID.randomUUID(), tenantId, "USD", true, 0, "ECB", 15, merchantCountry);
        when(r.findByTenantId(tenantId)).thenReturn(Optional.of(p));
        return r;
    }

    @Test
    void destination_comesFromMerchantConfig_notClientMetadata() {
        // Client forges a benign destination "US", but server merchant_country is IR.
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "IR"));
        GateSignals client = new GateSignals(null, "US", null, null, null, null, null, null);

        ResolvedGeography geo = resolver.resolve("t1", client, Map.of("destination_country", "US"));

        assertThat(geo.destinationCountry()).isEqualTo("IR");  // SERVER value wins
        assertThat(geo.destinationKnown()).isTrue();
        assertThat(geo.advisoryFlags()).contains("advisory_destination_mismatch");
    }

    @Test
    void clientCannotInflateDestination_serverUsWins_whenClientClaimsIr() {
        // Mirror: client claims IR (sanctioned) but server merchant_country is US → US wins,
        // mismatch flagged (the client cannot force a block on a clean merchant either).
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "US"));
        GateSignals client = new GateSignals(null, "IR", null, null, null, null, null, null);

        ResolvedGeography geo = resolver.resolve("t1", client, Map.of("destination_country", "IR"));

        assertThat(geo.destinationCountry()).isEqualTo("US");
        assertThat(geo.advisoryFlags()).contains("advisory_destination_mismatch");
    }

    @Test
    void unconfiguredMerchant_destinationUnknown() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", null));
        // tenant resolves to prefs with null merchantCountry
        ResolvedGeography geo = resolver.resolve("t1", GateSignals.none(), Map.of());
        assertThat(geo.destinationKnown()).isFalse();
        assertThat(geo.destinationCountry()).isNull();
    }

    @Test
    void source_readsOnlyTrustedEdgeKey_neverClientSourceCountry() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "US"));
        // Client supplies source_country=KP and ip_country=KP — both must be IGNORED for source.
        GateSignals client = new GateSignals("KP", "US", null, "KP", null, null, null, null);

        ResolvedGeography geo = resolver.resolve("t1", client,
                Map.of("source_country", "KP", "ip_country", "KP"));

        assertThat(geo.sourceKnown()).isFalse();      // no trusted edge key present
        assertThat(geo.sourceCountry()).isNull();     // client KP ignored
    }

    @Test
    void source_usesTrustedEdgeKey_whenPresent() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "US"));
        ResolvedGeography geo = resolver.resolve("t1", GateSignals.none(),
                Map.of(ServerGeographyResolver.TRUSTED_IP_COUNTRY_KEY, "gb"));
        assertThat(geo.sourceKnown()).isTrue();
        assertThat(geo.sourceCountry()).isEqualTo("GB");
    }

    @Test
    void crossBorderCapable_trueWhenLegUnknown_neverInfersDomesticFromMissing() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "US"));
        // dest US known, source unknown (no edge) → cannot prove domestic → cross-border-capable.
        ResolvedGeography geo = resolver.resolve("t1", GateSignals.none(), Map.of());
        assertThat(geo.destinationKnown()).isTrue();
        assertThat(geo.sourceKnown()).isFalse();
        assertThat(geo.crossBorderCapable()).isTrue();
    }

    @Test
    void crossBorderCapable_falseOnlyWhenBothKnownAndEqual() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs("t1", "US"));
        ResolvedGeography geo = resolver.resolve("t1", GateSignals.none(),
                Map.of(ServerGeographyResolver.TRUSTED_IP_COUNTRY_KEY, "US"));
        assertThat(geo.crossBorderCapable()).isFalse(); // US→US domestic, both known
    }

    @Test
    void nullTenant_destinationUnknown() {
        ServerGeographyResolver resolver = new ServerGeographyResolver(prefs(null, null));
        ResolvedGeography geo = resolver.resolve(null, GateSignals.none(), Map.of());
        assertThat(geo.destinationKnown()).isFalse();
    }
}
