package io.nexuspay.app.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for the {@code event_log} table.
 */
@Repository
public interface JpaEventLogRepository extends JpaRepository<EventLogEntity, Long> {

    List<EventLogEntity> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, String aggregateId);

    @Query("SELECT e FROM EventLogEntity e WHERE e.eventType = :eventType " +
           "AND e.createdAt > :after ORDER BY e.createdAt ASC")
    List<EventLogEntity> findByEventTypeAfter(
            @Param("eventType") String eventType,
            @Param("after") Instant after,
            org.springframework.data.domain.Pageable pageable);

    boolean existsByEventId(String eventId);
}
