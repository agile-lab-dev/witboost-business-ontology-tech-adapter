package com.witboost.ontology.application.infrastructure;

import com.witboost.ontology.domain.graph.GraphPartition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed repository for per-system RDF graph partitions (T021).
 *
 * <p>Security: all SQL uses {@code ?} placeholders. Schema qualification is omitted because {@code
 * currentSchema} is already set in the JDBC URL, preventing SQL injection.
 */
@Repository
@Slf4j
public class GraphRepository {

  private final JdbcTemplate jdbcTemplate;

  public GraphRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts or replaces the JSON-LD content for the given system URN (PostgreSQL upsert on PK). */
  public void upsert(String systemUrn, String content) {
    String sql =
        "INSERT INTO graph_state (system_urn, content, updated_at) VALUES (?, ?, now()) "
            + "ON CONFLICT (system_urn) DO UPDATE "
            + "SET content = EXCLUDED.content, updated_at = now()";
    jdbcTemplate.update(sql, systemUrn, content);
    log.debug("Upserted graph partition for system_urn={}", systemUrn);
  }

  /** Deletes the graph partition for the given system URN (no-op if absent). */
  public void delete(String systemUrn) {
    int affected = jdbcTemplate.update("DELETE FROM graph_state WHERE system_urn = ?", systemUrn);
    log.debug("Deleted {} partition(s) for system_urn={}", affected, systemUrn);
  }

  /** Returns all stored graph partitions. */
  public List<GraphPartition> findAll() {
    return jdbcTemplate.query(
        "SELECT system_urn, content, updated_at FROM graph_state",
        (rs, rowNum) -> {
          String urn = rs.getString("system_urn");
          String content = rs.getString("content");
          Timestamp ts = rs.getTimestamp("updated_at");
          Instant updatedAt = ts != null ? ts.toInstant() : Instant.now();
          return new GraphPartition(urn, content, updatedAt);
        });
  }
}
