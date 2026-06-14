-- ============================================================================
-- B-002 / C7 — FORCE ROW LEVEL SECURITY on the table owner (DORMANT by default).
--
-- WHY a repeatable (R__) and not a versioned migration:
--   FORCE binds even the table OWNER to RLS. Today the app still connects AS the
--   owner (rls.enforce=false), and at enforce=false there is no per-tx tenant GUC,
--   so an owner bound by FORCE would see ZERO rows -> total outage. FORCE must
--   therefore stay inert until the runtime cutover (app moves to the non-owner
--   nexuspay_app role, rls.enforce=true). A *versioned* migration applied while
--   dormant could never re-activate (Flyway runs each version once). A repeatable,
--   gated by the ${rlsforce} placeholder and idempotent in BOTH directions, can be
--   flipped at cutover and reverted in an incident.
--
-- DORMANT (default, application.yml -> spring.flyway.placeholders.rlsforce=false):
--   the ELSE branch runs and ensures NO public/analytics table is FORCE'd, so the
--   app-as-owner is never locked out. On a clean DB it finds nothing -> a no-op.
--
-- ACTIVATE (rls-enforce profile / prod cutover -> rlsforce=true):
--   the FORCE branch binds every RLS-enabled public+analytics table to its owner.
--   This is belt-and-suspenders on top of the primary control (app traffic on the
--   non-owner nexuspay_app role, proven by RlsEnforceIntegrationTest); it closes the
--   residual owner-bypass hole.
--
-- Re-trigger: Flyway re-runs a repeatable when its checksum changes — and with
--   placeholderReplacement=true (the default; nothing disables it here) the checksum is computed
--   over the PLACEHOLDER-REPLACED text. So flipping ${rlsforce} false↔true ALONE changes the
--   checksum and re-runs this migration at the next migrate; NO separate file edit is required.
--   (The 'rev N' marker below is redundant belt-and-suspenders, not a required step.) To make
--   re-execution depend on an explicit file edit instead, you would have to set
--   spring.flyway.placeholder-replacement=false — but that breaks the dormancy/enforce ITs which
--   rely on the placeholder taking effect, so don't. Marker: rev 1.
--
-- Owner-run, cross-schema (public + analytics), consistent with V4020. Superusers and
-- BYPASSRLS roles still bypass FORCE by design (that is what nexuspay_system relies on).
-- ============================================================================
DO $$
DECLARE
    r RECORD;
    want_force BOOLEAN := ('${rlsforce}' = 'true');
BEGIN
    IF want_force THEN
        -- Bind the owner: FORCE every RLS-enabled table not already forced (idempotent).
        FOR r IN
            SELECT n.nspname AS sch, c.relname AS tbl
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND c.relrowsecurity = true          -- RLS enabled (V2001 et al.)
              AND c.relforcerowsecurity = false    -- not yet forced -> idempotent
              AND n.nspname IN ('public', 'analytics')
        LOOP
            EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', r.sch, r.tbl);
        END LOOP;
        RAISE NOTICE 'C7: FORCE ROW LEVEL SECURITY applied (rlsforce=true).';
    ELSE
        -- Dormant / revert: un-force any forced table so the app-as-owner is never
        -- locked out while enforcement is off (idempotent; clean DB -> no-op).
        FOR r IN
            SELECT n.nspname AS sch, c.relname AS tbl
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
              AND c.relforcerowsecurity = true
              AND n.nspname IN ('public', 'analytics')
        LOOP
            EXECUTE format('ALTER TABLE %I.%I NO FORCE ROW LEVEL SECURITY', r.sch, r.tbl);
        END LOOP;
    END IF;
END
$$;
