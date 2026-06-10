-- Spring Modulith JPA event-publication registry (spring-modulith-starter-jpa).
-- Without this table, Hibernate `ddl-auto: validate` fails at startup with
-- "Schema-validation: missing table [event_publication]" (the JPA variant ships
-- no DDL of its own, unlike the JDBC variant).
--
-- Columns match the JpaEventPublication mapping in spring-modulith-events-jpa
-- 1.2.6, confirmed against Hibernate `validate` (the authority here):
--   @Table(name = "EVENT_PUBLICATION")
--   id               java.util.UUID      (@Id)            -> id               uuid
--   listenerId       java.lang.String                    -> listener_id      text
--   eventType        (event class name)                  -> event_type       text NOT NULL
--   serializedEvent  java.lang.String                    -> serialized_event text
--   publicationDate  java.time.Instant                   -> publication_date timestamptz
--   completionDate   java.time.Instant (nullable)        -> completion_date  timestamptz
-- event_type is NOT NULL: the entity constructor asserts the event type is
-- non-null, so every persisted row has one (matches Modulith's own DDL).

CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID                     NOT NULL,
    listener_id      TEXT                     NOT NULL,
    event_type       TEXT                     NOT NULL,
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
