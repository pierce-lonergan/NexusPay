package io.nexuspay.app.event;

import io.nexuspay.common.event.dlq.DeadLetterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for the {@code dead_letter_queue} table.
 */
@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetterEntry, Long> {

    Page<DeadLetterEntry> findByStatus(DeadLetterStatus status, Pageable pageable);

    Page<DeadLetterEntry> findByOriginalTopicAndStatus(String originalTopic, DeadLetterStatus status, Pageable pageable);

    Page<DeadLetterEntry> findByOriginalTopic(String originalTopic, Pageable pageable);

    @Query("SELECT d FROM DeadLetterEntry d WHERE d.status = 'PENDING' " +
           "AND d.nextRetryAt <= :now AND d.retryCount < d.maxRetries " +
           "ORDER BY d.nextRetryAt ASC")
    List<DeadLetterEntry> findRetryable(@Param("now") Instant now, Pageable pageable);

    long countByStatus(DeadLetterStatus status);

    @Query("SELECT d.originalTopic, d.status, COUNT(d) FROM DeadLetterEntry d " +
           "GROUP BY d.originalTopic, d.status")
    List<Object[]> countByTopicAndStatus();

    @Query("SELECT d FROM DeadLetterEntry d WHERE d.originalTopic = :topic " +
           "AND d.status = 'PENDING' ORDER BY d.createdAt ASC")
    List<DeadLetterEntry> findPendingByTopic(@Param("topic") String topic, Pageable pageable);
}
