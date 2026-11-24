package com.witboost.ontology.application;

import com.witboost.ontology.config.GraphProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jena.irix.IRIException;
import org.apache.jena.irix.IRIx;
import org.springframework.stereotype.Service;

/**
 * Builds and applies the effective prefix map for each descriptor provision request (FR-007,
 * R-005).
 *
 * <p>The effective map is assembled by merging in priority order:
 *
 * <ol>
 *   <li>{@code app.graph.prefixes.literals} — static config, applied as baseline
 *   <li>Descriptor field at {@code app.graph.prefixes.field} — field-derived entries win on
 *       conflict
 * </ol>
 *
 * <p>Prefix expansion converts {@code "skos:Concept"} → full IRI using the effective map. Values
 * starting with {@code http://}, {@code https://}, or {@code urn:} are returned as-is. Values with
 * no colon are returned as-is (treated as local names). Unrecognised prefix labels throw {@link
 * UnknownPrefixException}.
 */
@Service
public class PrefixResolver {

  private final GraphProperties graphProperties;

  public PrefixResolver(GraphProperties graphProperties) {
    this.graphProperties = graphProperties;
  }

  /**
   * Builds the effective prefix map for a descriptor by merging static config with
   * descriptor-derived {@code @context} entries (if configured).
   *
   * @param descriptorFields the root {@code extraFields} of the descriptor
   * @return merged prefix map; descriptor entries win on conflict
   */
  @SuppressWarnings("unchecked")
  public Map<String, String> resolve(Map<String, Object> descriptorFields) {
    Map<String, String> merged = new LinkedHashMap<>(graphProperties.getPrefixes().getLiterals());

    String contextField = graphProperties.getPrefixes().getField();
    if (contextField != null && !contextField.isBlank()) {
      Object contextValue = descriptorFields.get(contextField);
      if (contextValue instanceof Map<?, ?> contextEntries) {
        contextEntries.forEach(
            (entryKey, entryValue) -> {
              if (entryKey instanceof String prefixLabel
                  && entryValue instanceof String namespaceUri) {
                merged.put(prefixLabel, namespaceUri);
              }
            });
      }
    }
    return merged;
  }

  /** Checks for a valid absolute IRI, excluding blank-node identifiers. */
  public boolean isAbsoluteIri(String value) {
    try {
      IRIx iri = IRIx.create(value);
      return iri.isAbsolute() && !iri.hasViolations() && !value.startsWith("_:");
    } catch (IRIException invalidIri) {
      return false;
    }
  }

  /**
   * Expands a possibly-prefixed value to a full IRI.
   *
   * <ul>
   *   <li>Full IRIs ({@code http://…}, {@code https://…}) → returned as-is
   *   <li>Witboost URNs ({@code urn:…}) → returned as-is (handled by IriDerivationService)
   *   <li>No colon → returned as-is (local name)
   *   <li>Prefixed shorthand ({@code skos:Concept}) → looked up in prefix map; throws {@link
   *       UnknownPrefixException} if the label is unknown
   * </ul>
   *
   * @throws UnknownPrefixException if the prefix label is not in the map
   */
  public String expand(String value, Map<String, String> prefixMap) {
    if (value == null) return null;
    if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("urn:")) {
      return value;
    }
    int colon = value.indexOf(':');
    if (colon < 0) return value;

    String prefixLabel = value.substring(0, colon);
    String localName = value.substring(colon + 1);
    String namespace = prefixMap.get(prefixLabel);
    if (namespace == null) {
      throw new UnknownPrefixException(null, prefixLabel);
    }
    return namespace + localName;
  }

  /**
   * Like {@link #expand(String, Map)} but includes the configuration field name in the exception to
   * provide better operator error messages.
   */
  public String expand(String value, Map<String, String> prefixMap, String field) {
    try {
      return expand(value, prefixMap);
    } catch (UnknownPrefixException e) {
      throw new UnknownPrefixException(field, e.getPrefix());
    }
  }
}
