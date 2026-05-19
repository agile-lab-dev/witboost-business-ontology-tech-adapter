package com.witboost.ontology.application;

import com.witboost.ontology.config.KgmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class KgmClient {

  private final KgmProperties properties;
  private final RestClient restClient;

  @Autowired
  public KgmClient(KgmProperties properties) {
    this(properties, RestClient.create());
  }

  KgmClient(KgmProperties properties, RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
  }

  /**
   * Triggers the scheduled ingestion job for the configured KGM source. Errors are logged but never
   * propagated — ingestion is a best-effort side-effect.
   */
  public void triggerIngestion() {
    if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
      log.warn("KGM base URL is not configured; skipping ingestion trigger");
      return;
    }
    if (properties.getSourceId() == null || properties.getSourceId().isBlank()) {
      log.warn("KGM source ID is not configured; skipping ingestion trigger");
      return;
    }
    try {
      String url =
          properties.getBaseUrl() + "/v1/sources/" + properties.getSourceId() + "/ingestion";
      log.info("Triggering KGM ingestion for source '{}' at {}", properties.getSourceId(), url);
      restClient.post().uri(url).retrieve().toBodilessEntity();
      log.info("KGM ingestion triggered successfully for source '{}'", properties.getSourceId());
    } catch (Exception e) {
      log.error(
          "Failed to trigger KGM ingestion for source '{}': {}",
          properties.getSourceId(),
          e.getMessage());
    }
  }
}
