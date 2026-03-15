package io.nexuspay.dispute.application.port.out;

import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvidence;

import java.util.List;

/**
 * Output port for card-network dispute operations (Verifi RDR, Ethoca, etc.).
 *
 * <p>Phase 2 defines the interface; concrete network adapters are
 * implemented in Phase 3.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public interface DisputeNetworkPort {

    /**
     * Submits collected evidence to the card network for representment.
     *
     * @param dispute   the dispute being represented
     * @param evidence  evidence items to submit
     * @return network-assigned submission reference
     */
    String submitEvidence(Dispute dispute, List<DisputeEvidence> evidence);

    /**
     * Queries the card network for the current dispute status.
     *
     * @param externalDisputeId  the network's dispute identifier
     * @return current status string as reported by the network
     */
    String queryStatus(String externalDisputeId);

    /**
     * Returns the network name this adapter handles (e.g., "VERIFI", "ETHOCA").
     */
    String networkName();
}
