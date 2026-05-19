package com.witboost.ontology.domain.descriptor;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DataProductDescriptor {

  private String id;
  private String domain;

  private Map<String, Object> extraFields = new LinkedHashMap<>();

  @JsonAnySetter
  public void setExtraField(String key, Object value) {
    extraFields.put(key, value);
  }

  @JsonAnyGetter
  public Map<String, Object> getExtraFields() {
    return extraFields;
  }
}
