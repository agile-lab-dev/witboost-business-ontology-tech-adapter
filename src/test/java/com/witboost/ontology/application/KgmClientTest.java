package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.witboost.ontology.config.KgmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KgmClientTest {

  private KgmProperties properties;
  private RestClient restClient;

  // Mockito chain stubs for RestClient fluent API
  private RestClient.RequestBodyUriSpec requestBodyUriSpec;
  private RestClient.RequestBodySpec requestBodySpec;
  private RestClient.ResponseSpec responseSpec;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    properties = new KgmProperties();
    restClient = mock(RestClient.class);
    requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    requestBodySpec = mock(RestClient.RequestBodySpec.class);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(restClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity()).thenReturn(null);
  }

  private KgmClient client() {
    return new KgmClient(properties, restClient);
  }

  // ── guard: base URL absent ────────────────────────────────────────────────

  @Test
  void triggerIngestion_nullBaseUrl_logsWarnAndReturnsWithoutHttpCall() {
    properties.setBaseUrl(null);
    properties.setSourceId("ontology_ta");

    client().triggerIngestion();

    verify(restClient, never()).post();
  }

  @Test
  void triggerIngestion_blankBaseUrl_logsWarnAndReturnsWithoutHttpCall() {
    properties.setBaseUrl("   ");
    properties.setSourceId("ontology_ta");

    client().triggerIngestion();

    verify(restClient, never()).post();
  }

  // ── guard: source ID absent ───────────────────────────────────────────────

  @Test
  void triggerIngestion_nullSourceId_logsWarnAndReturnsWithoutHttpCall() {
    properties.setBaseUrl("http://kgm:9006");
    properties.setSourceId(null);

    client().triggerIngestion();

    verify(restClient, never()).post();
  }

  @Test
  void triggerIngestion_blankSourceId_logsWarnAndReturnsWithoutHttpCall() {
    properties.setBaseUrl("http://kgm:9006");
    properties.setSourceId("");

    client().triggerIngestion();

    verify(restClient, never()).post();
  }

  // ── happy path: both configured → POST called ─────────────────────────────

  @Test
  void triggerIngestion_bothConfigured_postsToKgmIngestionUrl() {
    properties.setBaseUrl("http://kgm:9006");
    properties.setSourceId("ontology_ta");

    client().triggerIngestion();

    verify(restClient).post();
    verify(requestBodyUriSpec).uri("http://kgm:9006/v1/sources/ontology_ta/ingestion");
  }

  // ── error path: HTTP call fails → exception swallowed, no propagation ─────

  @Test
  void triggerIngestion_httpError_doesNotPropagateException() {
    properties.setBaseUrl("http://kgm:9006");
    properties.setSourceId("ontology_ta");
    when(requestBodySpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));

    assertThatCode(() -> client().triggerIngestion()).doesNotThrowAnyException();
  }
}
