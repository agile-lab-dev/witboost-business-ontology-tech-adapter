package com.witboost.ontology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OntologyTechAdapterApplication {

  public static void main(String[] args) {
    SpringApplication.run(OntologyTechAdapterApplication.class, args);
  }
}
