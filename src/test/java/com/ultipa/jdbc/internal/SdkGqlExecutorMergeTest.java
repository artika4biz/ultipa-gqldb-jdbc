package com.ultipa.jdbc.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the merge of "labels in use" with "declared schema".
 *
 * <p>The distinction is not cosmetic: a schema-less graph reports labels but declares no
 * properties, which a live server revealed and which the first implementation got wrong by
 * reading only the schema statements.
 */
class SdkGqlExecutorMergeTest {

    private static Map<String, List<GraphPropertyDef>> declared(String label, String property,
                                                                String type) {
        Map<String, List<GraphPropertyDef>> declared = new LinkedHashMap<>();
        declared.put(label, List.of(new GraphPropertyDef(property, type)));
        return declared;
    }

    @Test
    @DisplayName("a schema-less graph still reports every label, with no properties")
    void reportsLabelsWithoutSchema() {
        List<GraphLabelDef> labels = SdkGqlExecutor.mergeLabels(
                List.of(List.of("Person"), List.of("Product")), Map.of());

        assertAll(
                () -> assertEquals(2, labels.size()),
                () -> assertEquals("Person", labels.get(0).name()),
                () -> assertTrue(labels.get(0).properties().isEmpty()),
                () -> assertEquals("Product", labels.get(1).name()));
    }

    @Test
    @DisplayName("a declared label carries its properties")
    void attachesDeclaredProperties() {
        List<GraphLabelDef> labels = SdkGqlExecutor.mergeLabels(
                List.of(List.of("Person")), declared("Person", "name", "string"));

        assertAll(
                () -> assertEquals(1, labels.size()),
                () -> assertEquals(1, labels.get(0).properties().size()),
                () -> assertEquals("name", labels.get(0).properties().get(0).name()),
                () -> assertEquals("string", labels.get(0).properties().get(0).type()));
    }

    @Test
    @DisplayName("a multi-label row is flattened, and repeats are de-duplicated in first-seen order")
    void flattensAndDeduplicates() {
        List<GraphLabelDef> labels = SdkGqlExecutor.mergeLabels(
                List.of(List.of("Person", "Customer"), List.of("Person"), List.of("Product")),
                Map.of());

        assertEquals(List.of("Person", "Customer", "Product"),
                labels.stream().map(GraphLabelDef::name).toList());
    }

    @Test
    @DisplayName("a declared label that nothing uses yet is still reported")
    void includesUnusedDeclaredLabels() {
        List<GraphLabelDef> labels = SdkGqlExecutor.mergeLabels(
                List.of(List.of("Person")), declared("Archived", "reason", "string"));

        assertEquals(List.of("Person", "Archived"),
                labels.stream().map(GraphLabelDef::name).toList());
    }

    @Test
    @DisplayName("tolerates null and blank rows from the server")
    void toleratesMissingRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(null);
        rows.add(Arrays.asList("Person", null, "  "));

        List<GraphLabelDef> labels = SdkGqlExecutor.mergeLabels(rows, Map.of());

        assertAll(
                () -> assertEquals(1, labels.size()),
                () -> assertEquals("Person", labels.get(0).name()),
                () -> assertTrue(SdkGqlExecutor.mergeLabels(null, Map.of()).isEmpty()));
    }
}
