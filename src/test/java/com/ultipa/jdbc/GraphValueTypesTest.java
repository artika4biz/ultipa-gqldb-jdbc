package com.ultipa.jdbc;

import com.ultipa.jdbc.graph.DmlStatistics;
import com.ultipa.jdbc.graph.GraphEdge;
import com.ultipa.jdbc.graph.GraphNode;
import com.ultipa.jdbc.graph.GraphPath;
import com.ultipa.jdbc.graph.GraphPoint;
import com.ultipa.jdbc.graph.GraphTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValueTypesTest {

    @Test
    @DisplayName("a node is immutable, and copies the collections it is given")
    void nodeIsImmutable() {
        List<String> labels = new ArrayList<>(List.of("Person"));
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("name", "Alice"));

        GraphNode node = new GraphNode("P1", "uuid", labels, properties);
        labels.add("Mutated");
        properties.put("injected", "value");

        assertAll(
                () -> assertEquals(List.of("Person"), node.getLabels()),
                () -> assertEquals(1, node.getProperties().size()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> node.getLabels().add("x")),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> node.getProperties().put("x", "y")));
    }

    @Test
    @DisplayName("a node tolerates missing identity and absent collections")
    void nodeToleratesMissingParts() {
        GraphNode node = new GraphNode(null, null, null, null);

        assertAll(
                () -> assertNull(node.getId()),
                () -> assertNull(node.getUuid()),
                () -> assertTrue(node.getLabels().isEmpty()),
                () -> assertTrue(node.getProperties().isEmpty()),
                () -> assertNull(node.getProperty("anything")));
    }

    @Test
    @DisplayName("nodes and edges compare by value")
    void comparesByValue() {
        GraphNode first = new GraphNode("P1", "u", List.of("Person"), Map.of("a", 1));
        GraphNode second = new GraphNode("P1", "u", List.of("Person"), Map.of("a", 1));
        GraphEdge edge = new GraphEdge("E1", "u", "KNOWS", "P1", "P2", Map.of());

        assertAll(
                () -> assertEquals(first, second),
                () -> assertEquals(first.hashCode(), second.hashCode()),
                () -> assertNotEquals(first, new GraphNode("P2", "u", List.of("Person"), Map.of("a", 1))),
                () -> assertNotEquals(first, edge),
                () -> assertEquals(edge, new GraphEdge("E1", "u", "KNOWS", "P1", "P2", Map.of())));
    }

    @Test
    @DisplayName("a path reports its endpoints and its length in edges")
    void pathReportsEndpointsAndLength() {
        GraphNode start = new GraphNode("P1", null, List.of(), Map.of());
        GraphNode end = new GraphNode("P2", null, List.of(), Map.of());
        GraphEdge edge = new GraphEdge("E1", null, "KNOWS", "P1", "P2", Map.of());

        GraphPath path = new GraphPath(List.of(start, end), List.of(edge));
        GraphPath empty = new GraphPath(null, null);

        assertAll(
                () -> assertEquals(start, path.getStartNode()),
                () -> assertEquals(end, path.getEndNode()),
                () -> assertEquals(1, path.length()),
                () -> assertNull(empty.getStartNode()),
                () -> assertNull(empty.getEndNode()),
                () -> assertEquals(0, empty.length()));
    }

    @Test
    @DisplayName("a 2D point has no z, a 3D point does")
    void pointDistinguishesDimensions() {
        GraphPoint flat = GraphPoint.of2D(9.0, 45.0, 4326);
        GraphPoint solid = GraphPoint.of3D(1.0, 2.0, 3.0, 0);

        assertAll(
                () -> assertFalse(flat.is3D()),
                () -> assertTrue(flat.getZ().isEmpty()),
                () -> assertTrue(flat.toString().contains("longitude")),
                () -> assertTrue(solid.is3D()),
                () -> assertEquals(3.0, solid.getZ().orElseThrow()),
                () -> assertTrue(solid.toString().contains("z=")),
                () -> assertNotEquals(flat, solid));
    }

    @Test
    @DisplayName("a nested table copies its rows and reports its shape")
    void tableIsImmutable() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of(1, 2)));

        GraphTable table = new GraphTable(List.of("a", "b"), rows);
        rows.add(new ArrayList<>(List.of(3, 4)));

        assertAll(
                () -> assertEquals(2, table.getColumnCount()),
                () -> assertEquals(1, table.getRowCount()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> table.getRows().get(0).add(99)));
    }

    @Test
    @DisplayName("DML statistics total their counts and report whether anything changed")
    void dmlStatisticsSummarise() {
        DmlStatistics stats = new DmlStatistics(1, 2, 3, 4, 5, 6);

        assertAll(
                () -> assertEquals(21, stats.total()),
                () -> assertTrue(stats.isReported()),
                () -> assertEquals(0, DmlStatistics.NONE.total()),
                () -> assertFalse(DmlStatistics.NONE.isReported()));
    }
}
