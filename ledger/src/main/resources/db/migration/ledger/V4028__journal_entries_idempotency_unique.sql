-- SEC-10: idempotent ledger journal entries. A duplicate Kafka delivery (at-least-once) or a DLT
-- replay of an identical PaymentCaptured / RefundCompleted must produce exactly ONE journal entry
-- per logical (payment_reference, description). PaymentEventConsumer dedups via a check-then-act
-- existsByPaymentReferenceAndDescription read (the cheap fast path); this UNIQUE index is the
-- concurrency backstop -- a truly-concurrent redelivery that slips past the read (both reads miss,
-- both attempt the INSERT) is rejected at insert. CreateJournalEntryUseCase.execute switches
-- save -> saveAndFlush and treats the violation as a deterministic no-op (returns instead of
-- throwing), so the loser does not re-enter retry/DLT. The idempotency key is
-- (payment_reference, description) where description in {'Payment captured','Refund completed'}.
--
-- NOTE on numbering: all module migration locations share ONE global Flyway schema history, and
-- out-of-order is disabled, so this must exceed the current global max (V4027 from the gateway-api
-- token-purge migration). V4028 is the ledger module's leaf but a globally-ordered version. Even
-- though it lives in the ledger migration location, it is numbered > V4027 like every new file.
--
-- PRE-FLIGHT DEDUPE (MUST run before CREATE UNIQUE INDEX). The table predates this constraint, so a
-- DB upgraded in place (application.yml has baseline-on-migrate: true -> this runs on EXISTING
-- non-empty databases) can already hold duplicate (payment_reference, description) rows from past
-- redeliveries -- exactly the pre-SEC-10 state the constraint forbids. CREATE UNIQUE INDEX does NOT
-- dedupe data; Postgres would reject the index build and Flyway would abort the WHOLE shared
-- migration set, bricking boot. So FIRST collapse pre-existing duplicates (auto-collapse, like fraud
-- V4023 -- redelivery dups are EXPECTED dirty data, not an abort-loud condition), keeping exactly
-- ONE row per group, THEN create the index. The whole migration is idempotent / safe to re-run: the
-- DELETEs are a no-op once the table is clean, and the index uses IF NOT EXISTS.
--
-- NULL payment_reference: Postgres treats NULLs as DISTINCT in a btree UNIQUE, so NULL-ref rows are
-- never deduped and never block the index -- intended (only the two fixed literals carry a non-null
-- ref via the consumer). The TEXT-in-btree limit (~2704 bytes) is a non-issue for the two short
-- literal descriptions but would fail for an arbitrarily long description -- acceptable given the
-- fixed values the consumer writes.
--
-- The winner per group is the EARLIEST posted_at (id tiebreak), so a row is only deleted by ANOTHER
-- row in the SAME (payment_reference, description) group; singletons are never touched.

-- 1) Delete the child postings of the LOSING journal entries FIRST. postings.journal_entry_id is a
--    plain REFERENCES journal_entries(id) with NO ON DELETE CASCADE (V1001 L39,44), so the parent
--    DELETE below would error on the FK unless the children are removed first. The loser set is
--    identical to the parent DELETE's predicate.
DELETE FROM postings p
      WHERE p.journal_entry_id IN (
          SELECT a.id
            FROM journal_entries a
            JOIN journal_entries b
              ON a.payment_reference = b.payment_reference
             AND a.description = b.description
             AND a.id <> b.id
           WHERE a.payment_reference IS NOT NULL
             AND (a.posted_at < b.posted_at
               OR (a.posted_at = b.posted_at AND a.id < b.id))
      );

-- 2) Collapse the duplicate journal_entries themselves, keeping ONE per (payment_reference,
--    description) group (the earliest posted_at; id tiebreak so exactly one survives deterministically).
DELETE FROM journal_entries a
      USING journal_entries b
      WHERE a.payment_reference IS NOT NULL
        AND a.payment_reference = b.payment_reference
        AND a.description = b.description
        AND a.id <> b.id
        AND (a.posted_at < b.posted_at
          OR (a.posted_at = b.posted_at AND a.id < b.id));

-- 3) Now safe on fresh AND upgraded-in-place DBs: the table holds at most one row per
--    (payment_reference, description), so the unique index builds cleanly.
CREATE UNIQUE INDEX IF NOT EXISTS uq_journal_entries_payment_ref_desc
    ON journal_entries (payment_reference, description);

-- OPEN CONCERN (tracked, NOT fixed here): pure row-dedup leaves ledger_accounts.posted_balance
-- OVER-credited by historical double-posts -- the duplicate already incremented the balance via
-- updateAccountBalance. This migration removes the duplicate ROW but does NOT unwind the balance. A
-- separate reconciliation (recompute posted_balance from surviving postings per account) is out of
-- scope for SEC-10's going-forward race fix but must be tracked for upgraded-in-place DBs.
