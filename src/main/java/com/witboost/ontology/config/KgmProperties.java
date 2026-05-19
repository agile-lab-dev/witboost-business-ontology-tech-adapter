package com.witboost.ontology.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.witboost.kgm")
@Data
public class KgmProperties {

  /** Base URL of the KGM instance (e.g. {@code http://kgm-service:8080}). */
  private String baseUrl;

  /** KGM source identifier used to trigger ingestion. */
  private String sourceId;
}
