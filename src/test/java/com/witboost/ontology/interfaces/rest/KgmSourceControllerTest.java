package com.witboost.ontology.interfaces.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.witboost.ontology.application.GraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KgmSourceController.class)
class KgmSourceControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean GraphService graphService;

  @Test
  void getGraphJsonLd_returns200WithJsonLdContentType() throws Exception {
    when(graphService.getJsonLd()).thenReturn("{\"@context\":{},\"@graph\":[]}");

    mockMvc
        .perform(get("/v1/graph/jsonld"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/ld+json"));
  }

  @Test
  void getGraphJsonLd_withSourceId_ignored() throws Exception {
    when(graphService.getJsonLd()).thenReturn("{\"@context\":{},\"@graph\":[]}");

    mockMvc
        .perform(get("/v1/graph/jsonld").param("sourceId", "ignored-id"))
        .andExpect(status().isOk());
  }
}
