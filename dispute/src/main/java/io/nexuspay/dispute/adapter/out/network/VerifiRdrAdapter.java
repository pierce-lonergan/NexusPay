package io.nexuspay.dispute.adapter.out.network;

import io.nexuspay.dispute.application.port.out.DisputeNetworkPort;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stub adapter for Verifi Rapid Dispute Resolution (RDR).
 *
 * <p>Interface defined in Phase 2 (Sprint 2.4); full integration with
 * Verifi's API deferred to Phase 3.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Component
public class VerifiRdrAdapter implements DisputeNetworkPort {

    private static final Logger log = LoggerFactory.getLogger(VerifiRdrAdapter.class);

    @Override
    public String submitEvidence(Dispute dispute, List<DisputeEvidence> evidence) {
        log.info("[STUB] Verifi RDR: submitting {} evidence items for dispute {}",
                evidence.size(), dispute.getId());
        return "verifi_ref_" + dispute.getId();
    }

    @Override
    public String queryStatus(String externalDisputeId) {
        log.info("[STUB] Verifi RDR: querying status for {}", externalDisputeId);
        return "PENDING";
    }

    @Override
    public String networkName() {
        return "VERIFI";
    }
}
