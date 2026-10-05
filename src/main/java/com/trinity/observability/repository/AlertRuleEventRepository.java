package com.trinity.observability.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.AlertRuleEventEntity;

/**
 * Append-only audit trail for alert rules. Inserted, never updated.
 */
@Repository
public interface AlertRuleEventRepository extends JpaRepository<AlertRuleEventEntity, Long> {

    /** One rule's history, newest first. */
    List<AlertRuleEventEntity> findByAlertRuleIdOrderByOccurredAtDesc(Long alertRuleId, Pageable pageable);

    /** Recent rule activity across an org. */
    List<AlertRuleEventEntity> findByOrgOrderByOccurredAtDesc(String org, Pageable pageable);
}
