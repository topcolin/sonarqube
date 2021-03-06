/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule.index;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.Settings;
import org.sonar.process.ProcessProperties;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.IndexDefinition;
import org.sonar.server.es.NewIndex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.server.rule.index.RuleIndexDefinition.FIELD_RULE_HTML_DESCRIPTION;
import static org.sonar.server.rule.index.RuleIndexDefinition.FIELD_RULE_KEY;
import static org.sonar.server.rule.index.RuleIndexDefinition.FIELD_RULE_REPOSITORY;
import static org.sonar.server.rule.index.RuleIndexDefinition.INDEX_TYPE_RULE;;

public class RuleIndexDefinitionTest {

  Settings settings = new MapSettings();
  RuleIndexDefinition underTest = new RuleIndexDefinition(settings);

  @Rule
  public EsTester tester = new EsTester(underTest);

  @Test
  public void test_definition_of_index() {
    IndexDefinition.IndexDefinitionContext context = new IndexDefinition.IndexDefinitionContext();
    underTest.define(context);

    assertThat(context.getIndices()).hasSize(1);
    NewIndex ruleIndex = context.getIndices().get("rules");
    assertThat(ruleIndex).isNotNull();
    assertThat(ruleIndex.getTypes().keySet()).containsOnly("rule", "activeRule");

    // no cluster by default
    assertThat(ruleIndex.getSettings().get("index.number_of_shards")).isEqualTo("1");
    assertThat(ruleIndex.getSettings().get("index.number_of_replicas")).isEqualTo("0");
  }

  @Test
  public void enable_replica_if_clustering_is_enabled() {
    settings.setProperty(ProcessProperties.CLUSTER_ENABLED, true);
    IndexDefinition.IndexDefinitionContext context = new IndexDefinition.IndexDefinitionContext();
    underTest.define(context);

    NewIndex ruleIndex = context.getIndices().get("rules");
    assertThat(ruleIndex.getSettings().get("index.number_of_replicas")).isEqualTo("1");
  }

  @Test
  public void support_long_html_description() throws Exception {
    String longText = StringUtils.repeat("hello  ", 10_000);
    // the following method fails if PUT fails
    tester.putDocuments(INDEX_TYPE_RULE, new RuleDoc(ImmutableMap.of(
      FIELD_RULE_HTML_DESCRIPTION, longText,
      FIELD_RULE_REPOSITORY, "squid",
      FIELD_RULE_KEY, "squid:S001")));
    assertThat(tester.countDocuments(INDEX_TYPE_RULE)).isEqualTo(1);

    List<AnalyzeResponse.AnalyzeToken> tokens = analyzeIndexedTokens(longText);
    for (AnalyzeResponse.AnalyzeToken token : tokens) {
      assertThat(token.getTerm().length()).isEqualTo("hello".length());
    }
  }

  @Test
  public void remove_html_characters_of_html_description() {
    String text = "<p>html <i>line</i></p>";
    List<AnalyzeResponse.AnalyzeToken> tokens = analyzeIndexedTokens(text);

    assertThat(tokens).extracting("term").containsOnly("html", "line");
  }

  @Test
  public void sanitize_html_description_as_it_is_english() {
    String text = "this is a small list of words";
    // "this", "is", "a" and "of" are not indexed.
    // Plural "words" is converted to singular "word"
    List<AnalyzeResponse.AnalyzeToken> tokens = analyzeIndexedTokens(text);
    assertThat(tokens).extracting("term").containsOnly("small", "list", "word");
  }

  private List<AnalyzeResponse.AnalyzeToken> analyzeIndexedTokens(String text) {
    return tester.client().nativeClient().admin().indices().prepareAnalyze(INDEX_TYPE_RULE.getIndex(),
      text)
      .setField(FIELD_RULE_HTML_DESCRIPTION)
      .execute().actionGet().getTokens();
  }
}
