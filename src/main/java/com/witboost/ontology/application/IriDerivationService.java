package com.witboost.ontology.application;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.config.GraphProperties;
import org.springframework.stereotype.Service;

/**
 * Derives RDF IRIs from Witboost system URNs and concept names using a deterministic slugification
 * algorithm (FR-005, R-004).
 *
 * <p>The {@code wb:} namespace is read from {@code app.graph.prefixes.literals.wb} (applied by
 * {@link PresetMergeService} at startup) with a fallback to the canonical Witboost namespace.
 */
@Service
public class IriDerivationService {

  private final GraphProperties graphProperties;

  public IriDerivationService(GraphProperties graphProperties) {
    this.graphProperties = graphProperties;
  }

  /**
   * Derives the root IRI for a data-product system.
   *
   * <p>Algorithm:
   *
   * <ol>
   *   <li>Strip {@code urn:dmb:dp:} prefix (or generic {@code urn:} prefix) from the system URN.
   *   <li>Slugify the remainder.
   *   <li>Prepend the configured {@code wb:} namespace.
   * </ol>
   *
   * <p>Example: {@code urn:dmb:dp:domain:registry-a:0} → {@code
   * https://witboost.com/type#domain-registry-a-0}
   */
  public String deriveRootIri(String systemUrn) {
    String urnRemainder = systemUrn;
    if (urnRemainder.startsWith("urn:dmb:dp:")) {
      urnRemainder = urnRemainder.substring("urn:dmb:dp:".length());
    } else if (urnRemainder.startsWith("urn:")) {
      urnRemainder = urnRemainder.substring(4);
    }
    String slugifiedRemainder = slugify(urnRemainder);
    String witboostNamespace =
        graphProperties.getPrefixes().getLiterals().getOrDefault("wb", WitboostNamespaces.WB_NS);
    return witboostNamespace + slugifiedRemainder;
  }

  /**
   * Derives a concept IRI by appending the slugified concept name to the root IRI.
   *
   * <p>When the root IRI already ends with a namespace separator ({@code #}, {@code /}, or {@code
   * :}), the slug is appended directly. Otherwise a {@code -} delimiter is inserted.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>Root {@code https://witboost.com/type#domain-registry-a-0}, name {@code "Concept A"} →
   *       {@code https://witboost.com/type#domain-registry-a-0-concept-a}
   *   <li>Root {@code http://example.com/express#} (namespace IRI), name {@code "Company"} → {@code
   *       http://example.com/express#Company} (no extra {@code -})
   * </ul>
   */
  public String deriveConceptIri(String rootIri, String conceptName) {
    boolean endsWithSeparator =
        rootIri.endsWith("#") || rootIri.endsWith("/") || rootIri.endsWith(":");
    return rootIri + (endsWithSeparator ? "" : "-") + slugify(conceptName);
  }

  /**
   * Converts an arbitrary string to a URL-safe slug:
   *
   * <ol>
   *   <li>Lowercase
   *   <li>Replace non-{@code [a-z0-9-]} with {@code -}
   *   <li>Collapse consecutive hyphens
   *   <li>Strip leading/trailing hyphens
   * </ol>
   */
  public String slugify(String input) {
    if (input == null || input.isBlank()) return "";
    return input
        .toLowerCase()
        .replaceAll("[^a-z0-9-]", "-")
        .replaceAll("-{2,}", "-")
        .replaceAll("^-|-$", "");
  }
}
