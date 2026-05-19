package com.witboost.ontology;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"spring.flyway.enabled=false"})
class OntologyTechAdapterApplicationTest {

  @MockitoBean DataSource dataSource;

  @MockitoBean JdbcTemplate jdbcTemplate;

  @Test
  void contextLoads() {}
}
