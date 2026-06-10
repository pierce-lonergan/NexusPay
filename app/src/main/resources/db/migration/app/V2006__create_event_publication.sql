-- Spring Modulith JPA event-publication registry (spring-modulith-starter-jpa).
-- Without this table, Hibernate `ddl-auto: validate` fails at startup with
-- "Schema-validation: missing table [event_publication]" (the JPA variant ships
-- no DDL of its own, unlike the JDBC variant).
--
-- Columns/types match the JpaEventPublication entity in
-- spring-modulith-events-jpa 1.2.6 EXACTLY (verified via javap against the jar):
--   @Table(name = "EVENT_PUBLICATION")
--   id               java.util.UUID      (@Id)
--   listenerId       java.lang.String    -> listener_id
--   serializedEvent  java.lang.String    -> serialized_event
--   publicationDate  java.time.Instant   -> publication_date  (timestamptz)
--   completionDate   java.time.Instant   -> completion_date   (nullable; set on completion)
-- NOTE: the JPA entity has NO event_type field (the constructor validates the
-- event type but does not persist it), so we deliberately do NOT add an
-- event_type column — a NOT NULL one would break Modulith's inserts.

CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    serialized_event TEXT                     NOT NULL,
    publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date  TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

-- Incomplete-publication scans (republish on restart) filter on completion_date IS NULL.
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);

-- Completion lookups match on the serialized event payload.
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);
