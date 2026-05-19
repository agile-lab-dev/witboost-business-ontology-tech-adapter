package com.witboost.ontology.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.witboost.ontology.WitboostNamespaces;
import com.witboost.ontology.config.GraphProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IriDerivationServiceTest {

  private IriDerivationService service;

  @BeforeEach
  void setUp() {
    GraphProperties props = new GraphProperties();
    props.getPrefixes().getLiterals().put("wb", WitboostNamespaces.WB_NS);
    service = new IriDerivationService(props);
  }

  @Test
  void deriveRootIri_witboostUrn_stripsAndSlugsAndExpandsWb() {
    String result = service.deriveRootIri("urn:dmb:dp:domain:registry-a:0");
    assertThat(result).isEqualTo(WitboostNamespaces.WB_NS + "domain-registry-a-0");
  }

  @Test
  void deriveRootIri_genericUrn_stripsUrnPrefix() {
    String result = service.deriveRootIri("urn:some:thing:here");
    assertThat(result).isEqualTo(WitboostNamespaces.WB_NS + "some-thing-here");
  }

  @Test
  void deriveConceptIri_appendsSlugifiedName() {
    String root = WitboostNamespaces.WB_NS + "domain-registry-a-0";
    assertThat(service.deriveConceptIri(root, "Concept A")).isEqualTo(root + "-concept-a");
  }

  @Test
  void deriveConceptIri_rootEndsWithHash_noExtraHyphen() {
    // e.g. root IRI is a namespace like http://example.com/express#
    assertThat(service.deriveConceptIri("http://example.com/express#", "Company"))
        .isEqualTo("http://example.com/express#company");
  }

  @Test
  void deriveConceptIri_rootEndsWithSlash_noExtraHyphen() {
    assertThat(service.deriveConceptIri("http://example.com/onto/", "My Class"))
        .isEqualTo("http://example.com/onto/my-class");
  }

  @Test
  void deriveConceptIri_rootEndsWithColon_noExtraHyphen() {
    assertThat(service.deriveConceptIri("http://example.com/onto:", "Send"))
        .isEqualTo("http://example.com/onto:send");
  }

  @Test
  void slugify_colons_replacedWithHyphens() {
    assertThat(service.slugify("domain:registry-a:0")).isEqualTo("domain-registry-a-0");
  }

  @Test
  void slugify_spaces_replacedWithHyphens() {
    assertThat(service.slugify("My Concept Name")).isEqualTo("my-concept-name");
  }

  @Test
  void slugify_uppercase_lowercased() {
    assertThat(service.slugify("HELLO")).isEqualTo("hello");
  }

  @Test
  void slugify_leadingTrailingSpecialChars_stripped() {
    assertThat(service.slugify(":foo:")).isEqualTo("foo");
    assertThat(service.slugify("--bar--")).isEqualTo("bar");
  }

  @Test
  void slugify_consecutiveSpecialChars_collapsedToSingleHyphen() {
    assertThat(service.slugify("a::b")).isEqualTo("a-b");
  }
}
