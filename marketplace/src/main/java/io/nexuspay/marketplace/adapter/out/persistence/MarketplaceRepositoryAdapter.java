package io.nexuspay.marketplace.adapter.out.persistence;

import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapter implementing {@link MarketplaceRepository} by delegating to Spring Data JPA
 * repositories and mapping between domain models and JPA entities.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Component
public class MarketplaceRepositoryAdapter implements MarketplaceRepository {

    private final JpaConnectedAccountRepository accountRepo;
    private final JpaSplitPaymentRepository splitPaymentRepo;
    private final JpaSplitRuleRepository splitRuleRepo;
    private final JpaPayoutRepository payoutRepo;
    private final JpaPlatformFeeRepository feeRepo;

    public MarketplaceRepositoryAdapter(JpaConnectedAccountRepository accountRepo,
                                         JpaSplitPaymentRepository splitPaymentRepo,
                                         JpaSplitRuleRepository splitRuleRepo,
                                         JpaPayoutRepository payoutRepo,
                                         JpaPlatformFeeRepository feeRepo) {
        this.accountRepo = accountRepo;
        this.splitPaymentRepo = splitPaymentRepo;
        this.splitRuleRepo = splitRuleRepo;
        this.payoutRepo = payoutRepo;
        this.feeRepo = feeRepo;
    }

    // --- ConnectedAccount ---

    @Override
    public ConnectedAccount saveAccount(ConnectedAccount account) {
        return toDomain(accountRepo.save(toEntity(account)));
    }

    @Override
    public Optional<ConnectedAccount> findAccountById(String id) {
        return accountRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<ConnectedAccount> findAccountById(String id, String tenantId) {
        return accountRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<ConnectedAccount> findAccountsByTenantId(String tenantId) {
        return accountRepo.findByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteAccount(String id) {
        accountRepo.deleteById(id);
    }

    // --- SplitPayment ---

    @Override
    public SplitPayment saveSplitPayment(SplitPayment splitPayment) {
        return toDomain(splitPaymentRepo.save(toEntity(splitPayment)));
    }

    @Override
    public Optional<SplitPayment> findSplitPaymentById(String id) {
        return splitPaymentRepo.findById(id).map(e -> {
            SplitPayment sp = toDomain(e);
            List<SplitRule> rules = findRulesBySplitPaymentId(id);
            sp.setRules(rules);
            return sp;
        });
    }

    @Override
    public Optional<SplitPayment> findSplitPaymentById(String id, String tenantId) {
        return splitPaymentRepo.findByIdAndTenantId(id, tenantId).map(e -> {
            SplitPayment sp = toDomain(e);
            List<SplitRule> rules = findRulesBySplitPaymentId(id);
            sp.setRules(rules);
            return sp;
        });
    }

    @Override
    public List<SplitPayment> findSplitPaymentsByPaymentId(String paymentId) {
        return splitPaymentRepo.findByPaymentId(paymentId).stream().map(e -> {
            SplitPayment sp = toDomain(e);
            List<SplitRule> rules = findRulesBySplitPaymentId(sp.getId());
            sp.setRules(rules);
            return sp;
        }).toList();
    }

    // --- SplitRule ---

    @Override
    public SplitRule saveSplitRule(SplitRule rule) {
        return toDomain(splitRuleRepo.save(toEntity(rule)));
    }

    @Override
    public List<SplitRule> findRulesBySplitPaymentId(String splitPaymentId) {
        return splitRuleRepo.findBySplitPaymentId(splitPaymentId).stream()
                .map(this::toDomain).toList();
    }

    // --- Payout ---

    @Override
    public Payout savePayout(Payout payout) {
        return toDomain(payoutRepo.save(toEntity(payout)));
    }

    @Override
    public Optional<Payout> findPayoutById(String id) {
        return payoutRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Payout> findPayoutById(String id, String tenantId) {
        return payoutRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<Payout> findPayoutsByAccountId(String connectedAccountId) {
        return payoutRepo.findByConnectedAccountId(connectedAccountId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<Payout> findPayoutsByAccountId(String connectedAccountId, String tenantId) {
        return payoutRepo.findByConnectedAccountIdAndTenantId(connectedAccountId, tenantId).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<Payout> findPendingPayoutsDueBefore(Instant cutoff) {
        return payoutRepo.findByStatusAndScheduledAtBefore("PENDING", cutoff).stream()
                .map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean claimPayoutForProcessing(String id) {
        // SEC-11: @Modifying needs an active tx (supplied by the scheduler's @SystemTransactional).
        // rows-affected==1 means this replica/cycle won the PENDING -> PROCESSING transition.
        // SEC-25: the claim UPDATE also stamps processing_since (the reconciler's stuck clock).
        return payoutRepo.claimForProcessing(id) == 1;
    }

    // --- SEC-25: stuck-PROCESSING recovery ---

    @Override
    public List<Payout> findStuckProcessingPayouts(Instant cutoff, Instant now, int maxAttempts, int batchSize) {
        return payoutRepo.findStuckProcessing(cutoff, now, maxAttempts, batchSize).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public List<Payout> findExhaustedProcessingPayouts(int maxAttempts) {
        return payoutRepo.findExhaustedProcessing(maxAttempts).stream()
                .map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public Optional<Payout> reloadStuckPayoutForUpdate(String id) {
        return payoutRepo.findProcessingByIdForUpdate(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public boolean markPayoutPaid(String id, String tenantId, String externalReference) {
        return payoutRepo.markPaidById(id, tenantId, externalReference) == 1;
    }

    @Override
    @Transactional
    public boolean markPayoutFailed(String id, String tenantId, String reason) {
        return payoutRepo.markFailedById(id, tenantId, reason) == 1;
    }

    @Override
    @Transactional
    public void recordPayoutReconcileFailure(String id, String tenantId, Instant nextReconcileAt, String error) {
        payoutRepo.recordReconcileFailureById(id, tenantId, nextReconcileAt, error);
    }

    // --- PlatformFee ---

    @Override
    public PlatformFee savePlatformFee(PlatformFee fee) {
        return toDomain(feeRepo.save(toEntity(fee)));
    }

    @Override
    public Optional<PlatformFee> findFeesBySplitPaymentId(String splitPaymentId) {
        return feeRepo.findBySplitPaymentId(splitPaymentId).map(this::toDomain);
    }

    // --- Entity ↔ Domain Mapping: ConnectedAccount ---

    private ConnectedAccountEntity toEntity(ConnectedAccount a) {
        ConnectedAccountEntity e = new ConnectedAccountEntity();
        e.setId(a.getId());
        e.setTenantId(a.getTenantId());
        e.setBusinessName(a.getBusinessName());
        e.setEmail(a.getEmail());
        e.setStatus(a.getStatus().name());
        e.setKycStatus(a.getKycStatus().name());
        e.setCountry(a.getCountry());
        e.setDefaultCurrency(a.getDefaultCurrency());
        e.setPayoutSchedule(a.getPayoutSchedule().name());
        e.setPayoutMinimum(a.getPayoutMinimum());
        e.setPlatformFeePercent(a.getPlatformFeePercent());
        e.setPlatformFeeFixed(a.getPlatformFeeFixed());
        e.setMetadata(a.getMetadata());
        e.setCreatedAt(a.getCreatedAt());
        e.setUpdatedAt(a.getUpdatedAt());
        return e;
    }

    private ConnectedAccount toDomain(ConnectedAccountEntity e) {
        ConnectedAccount a = new ConnectedAccount();
        a.setId(e.getId());
        a.setTenantId(e.getTenantId());
        a.setBusinessName(e.getBusinessName());
        a.setEmail(e.getEmail());
        a.setStatus(AccountState.valueOf(e.getStatus()));
        a.setKycStatus(KycStatus.valueOf(e.getKycStatus()));
        a.setCountry(e.getCountry());
        a.setDefaultCurrency(e.getDefaultCurrency());
        a.setPayoutSchedule(PayoutSchedule.valueOf(e.getPayoutSchedule()));
        a.setPayoutMinimum(e.getPayoutMinimum());
        a.setPlatformFeePercent(e.getPlatformFeePercent());
        a.setPlatformFeeFixed(e.getPlatformFeeFixed());
        a.setMetadata(e.getMetadata());
        a.setCreatedAt(e.getCreatedAt());
        a.setUpdatedAt(e.getUpdatedAt());
        return a;
    }

    // --- Entity ↔ Domain Mapping: SplitPayment ---

    private SplitPaymentEntity toEntity(SplitPayment sp) {
        SplitPaymentEntity e = new SplitPaymentEntity();
        e.setId(sp.getId());
        e.setPaymentId(sp.getPaymentId());
        e.setTenantId(sp.getTenantId());
        e.setStatus(sp.getStatus().name());
        e.setTotalAmount(sp.getTotalAmount());
        e.setCurrency(sp.getCurrency());
        e.setCreatedAt(sp.getCreatedAt());
        return e;
    }

    private SplitPayment toDomain(SplitPaymentEntity e) {
        SplitPayment sp = new SplitPayment();
        sp.setId(e.getId());
        sp.setPaymentId(e.getPaymentId());
        sp.setTenantId(e.getTenantId());
        sp.setStatus(SplitPaymentStatus.valueOf(e.getStatus()));
        sp.setTotalAmount(e.getTotalAmount());
        sp.setCurrency(e.getCurrency());
        sp.setRules(new ArrayList<>());
        sp.setCreatedAt(e.getCreatedAt());
        return sp;
    }

    // --- Entity ↔ Domain Mapping: SplitRule ---

    private SplitRuleEntity toEntity(SplitRule r) {
        SplitRuleEntity e = new SplitRuleEntity();
        e.setId(r.getId());
        e.setSplitPaymentId(r.getSplitPaymentId());
        e.setConnectedAccountId(r.getConnectedAccountId());
        e.setSplitType(r.getSplitType().name());
        e.setAmount(r.getAmount());
        e.setPercentage(r.getPercentage());
        e.setCalculatedAmount(r.getCalculatedAmount());
        e.setCurrency(r.getCurrency());
        e.setCreatedAt(r.getCreatedAt());
        return e;
    }

    private SplitRule toDomain(SplitRuleEntity e) {
        SplitRule r = new SplitRule();
        r.setId(e.getId());
        r.setSplitPaymentId(e.getSplitPaymentId());
        r.setConnectedAccountId(e.getConnectedAccountId());
        r.setSplitType(SplitType.valueOf(e.getSplitType()));
        r.setAmount(e.getAmount() != null ? e.getAmount() : 0);
        r.setPercentage(e.getPercentage());
        r.setCalculatedAmount(e.getCalculatedAmount() != null ? e.getCalculatedAmount() : 0);
        r.setCurrency(e.getCurrency());
        r.setCreatedAt(e.getCreatedAt());
        return r;
    }

    // --- Entity ↔ Domain Mapping: Payout ---

    private PayoutEntity toEntity(Payout p) {
        PayoutEntity e = new PayoutEntity();
        e.setId(p.getId());
        e.setConnectedAccountId(p.getConnectedAccountId());
        e.setTenantId(p.getTenantId());
        e.setAmount(p.getAmount());
        e.setCurrency(p.getCurrency());
        e.setStatus(p.getStatus().name());
        e.setMethod(p.getMethod().name());
        e.setScheduledAt(p.getScheduledAt());
        e.setPaidAt(p.getPaidAt());
        e.setFailureReason(p.getFailureReason());
        e.setExternalReference(p.getExternalReference());
        e.setCreatedAt(p.getCreatedAt());
        // SEC-25: PROCESSING-recovery bookkeeping.
        e.setProcessingSince(p.getProcessingSince());
        e.setReconcileAttempts(p.getReconcileAttempts());
        e.setNextReconcileAt(p.getNextReconcileAt());
        e.setLastReconcileError(p.getLastReconcileError());
        return e;
    }

    private Payout toDomain(PayoutEntity e) {
        Payout p = new Payout();
        p.setId(e.getId());
        p.setConnectedAccountId(e.getConnectedAccountId());
        p.setTenantId(e.getTenantId());
        p.setAmount(e.getAmount());
        p.setCurrency(e.getCurrency());
        p.setStatus(PayoutStatus.valueOf(e.getStatus()));
        p.setMethod(PayoutMethod.valueOf(e.getMethod()));
        p.setScheduledAt(e.getScheduledAt());
        p.setPaidAt(e.getPaidAt());
        p.setFailureReason(e.getFailureReason());
        p.setExternalReference(e.getExternalReference());
        p.setCreatedAt(e.getCreatedAt());
        // SEC-25: PROCESSING-recovery bookkeeping.
        p.setProcessingSince(e.getProcessingSince());
        p.setReconcileAttempts(e.getReconcileAttempts());
        p.setNextReconcileAt(e.getNextReconcileAt());
        p.setLastReconcileError(e.getLastReconcileError());
        return p;
    }

    // --- Entity ↔ Domain Mapping: PlatformFee ---

    private PlatformFeeEntity toEntity(PlatformFee f) {
        PlatformFeeEntity e = new PlatformFeeEntity();
        e.setId(f.getId());
        e.setSplitPaymentId(f.getSplitPaymentId());
        e.setTenantId(f.getTenantId());
        e.setFeeAmount(f.getFeeAmount());
        e.setCurrency(f.getCurrency());
        e.setFeePercent(f.getFeePercent());
        e.setFeeFixed(f.getFeeFixed());
        e.setDescription(f.getDescription());
        e.setCreatedAt(f.getCreatedAt());
        return e;
    }

    private PlatformFee toDomain(PlatformFeeEntity e) {
        PlatformFee f = new PlatformFee();
        f.setId(e.getId());
        f.setSplitPaymentId(e.getSplitPaymentId());
        f.setTenantId(e.getTenantId());
        f.setFeeAmount(e.getFeeAmount());
        f.setCurrency(e.getCurrency());
        f.setFeePercent(e.getFeePercent());
        f.setFeeFixed(e.getFeeFixed() != null ? e.getFeeFixed() : 0);
        f.setDescription(e.getDescription());
        f.setCreatedAt(e.getCreatedAt());
        return f;
    }
}
