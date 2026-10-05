package com.trinity.observability.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.AlertIngestCursorEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert poller's watermarks, one per (org, stream).
 */
@Repository
public interface AlertIngestCursorRepository extends JpaRepository<AlertIngestCursorEntity, Long> {

    Optional<AlertIngestCursorEntity> findByOrgAndStreamName(String org, String streamName);

    /** Every cursor, for the ingest-health endpoint. */
    List<AlertIngestCursorEntity> findAllByOrderByOrgAsc();

    @Modifying
    @Transactional
    @Query("DELETE FROM AlertIngestCursorEntity c WHERE LOWER(c.streamName) != LOWER(:validStreamName)")
    void deleteObsoleteStreams(@Param("validStreamName") String validStreamName);
}
