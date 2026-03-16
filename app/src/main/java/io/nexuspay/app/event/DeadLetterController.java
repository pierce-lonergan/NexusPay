package io.nexuspay.app.event;

import io.nexuspay.common.event.dlq.DeadLetterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin REST API for dead letter queue management.
 * Provides visibility into failed events and tools for manual retry/discard.
 *
 * <p>All endpoints require the 'admin' role.
 */
@RestController
@RequestMapping("/v1/admin/dead-letters")
@PreAuthorize("hasRole('admin')")
public class DeadLetterController {

    private final DeadLetterRepository repository;

    public DeadLetterController(DeadLetterRepository repository) {
        this.repository = repository;
    }

    /**
     * List dead letter entries, optionally filtered by status and/or topic.
     */
    @GetMapping
    public ResponseEntity<Page<DeadLetterEntry>> list(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DeadLetterEntry> result;

        if (status != null && topic != null) {
            result = repository.findByOriginalTopicAndStatus(topic, status, pageable);
        } else if (status != null) {
            result = repository.findByStatus(status, pageable);
        } else if (topic != null) {
            result = repository.findByOriginalTopic(topic, pageable);
        } else {
            result = repository.findAll(pageable);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get a single dead letter entry by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DeadLetterEntry> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Manually trigger retry for a single entry.
     * Sets status to RETRYING and next_retry_at to now so the reprocessor picks it up immediately.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<DeadLetterEntry> retry(@PathVariable Long id) {
        return repository.findById(id)
                .map(entry -> {
                    if (entry.getStatus() == DeadLetterStatus.RESOLVED) {
                        return ResponseEntity.badRequest().<DeadLetterEntry>build();
                    }
                    entry.setStatus(DeadLetterStatus.PENDING);
                    entry.setNextRetryAt(Instant.now());
                    repository.save(entry);
                    return ResponseEntity.ok(entry);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Mark a dead letter entry as discarded (will not be retried).
     */
    @PostMapping("/{id}/discard")
    public ResponseEntity<DeadLetterEntry> discard(@PathVariable Long id) {
        return repository.findById(id)
                .map(entry -> {
                    entry.setStatus(DeadLetterStatus.DISCARDED);
                    entry.setResolvedAt(Instant.now());
                    repository.save(entry);
                    return ResponseEntity.ok(entry);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Bulk retry all PENDING entries for a given topic.
     * Returns the count of entries queued for retry (max 1000 per call).
     */
    @PostMapping("/retry-all")
    public ResponseEntity<Map<String, Object>> retryAll(@RequestParam String topic) {
        var entries = repository.findPendingByTopic(topic, PageRequest.of(0, 1000));

        int count = 0;
        for (DeadLetterEntry entry : entries) {
            entry.setStatus(DeadLetterStatus.PENDING);
            entry.setNextRetryAt(Instant.now());
            repository.save(entry);
            count++;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("topic", topic);
        response.put("queued", count);
        return ResponseEntity.ok(response);
    }

    /**
     * Get DLQ statistics: counts by status and by topic.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> stats = new HashMap<>();

        // Counts by status
        Map<String, Long> byStatus = new HashMap<>();
        for (DeadLetterStatus status : DeadLetterStatus.values()) {
            byStatus.put(status.name(), repository.countByStatus(status));
        }
        stats.put("byStatus", byStatus);

        // Counts by topic and status
        Map<String, Map<String, Long>> byTopic = new HashMap<>();
        for (Object[] row : repository.countByTopicAndStatus()) {
            String topic = (String) row[0];
            DeadLetterStatus status = (DeadLetterStatus) row[1];
            long cnt = (long) row[2];
            byTopic.computeIfAbsent(topic, k -> new HashMap<>()).put(status.name(), cnt);
        }
        stats.put("byTopic", byTopic);

        return ResponseEntity.ok(stats);
    }
}
