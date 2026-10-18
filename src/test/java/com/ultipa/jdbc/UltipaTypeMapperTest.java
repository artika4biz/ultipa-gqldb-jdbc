package com.ultipa.jdbc;

import com.gqldb.types.GqldbDate;
import com.gqldb.types.GqldbDecimal;
import com.gqldb.types.GqldbEdge;
import com.gqldb.types.GqldbError;
import com.gqldb.types.GqldbLocalDateTime;
import com.gqldb.types.GqldbLocalTime;
import com.gqldb.types.GqldbNode;
import com.gqldb.types.GqldbPath;
import com.gqldb.types.GqldbTable;
import com.gqldb.types.GqldbZonedDateTime;
import com.gqldb.types.GqldbZonedTime;
import com.gqldb.types.Point;
import com.gqldb.types.Point3D;
import com.gqldb.types.PropertyType;
import com.gqldb.types.TypedValue;
import com.gqldb.types.Vector;
import com.ultipa.jdbc.graph.GraphEdge;
import com.ultipa.jdbc.graph.GraphError;
import com.ultipa.jdbc.graph.GraphNode;
import com.ultipa.jdbc.graph.GraphPath;
import com.ultipa.jdbc.graph.GraphPoint;
import com.ultipa.jdbc.graph.GraphTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaTypeMapperTest {

    @ParameterizedTest
    @DisplayName("maps every GQLDB type to the documented java.sql.Types constant")
    @CsvSource({
            "BOOL,            16",   // BOOLEAN
            "INT32,            4",   // INTEGER
            "UINT32,          -5",   // BIGINT
            "INT64,           -5",
            "UINT64,          -5",
            "FLOAT,            7",   // REAL
            "DOUBLE,           8",
            "DECIMAL,          3",
            "STRING,          12",   // VARCHAR
            "TEXT,            -1",   // LONGVARCHAR
            "BLOB,            -3",   // VARBINARY
            "DATE,            91",
            "LOCAL_TIME,      92",   // TIME
            "ZONED_TIME,    2013",   // TIME_WITH_TIMEZONE
            "LOCAL_DATETIME,  93",   // TIMESTAMP
            "DATETIME,        93",
            "TIMESTAMP,       93",
            "ZONED_DATETIME,2014",   // TIMESTAMP_WITH_TIMEZONE
            "NULL,             0",
            "LIST,          1111",   // OTHER
            "SET,           1111",
            "MAP,           1111",
            "RECORD,        1111",
            "VECTOR,        1111",
            "POINT,         1111",
            "POINT3D,       1111",
            "NODE,          1111",
            "EDGE,          1111",
            "PATH,          1111",
            "TABLE,         1111",
            "ERROR,         1111",
            "YEAR_TO_MONTH, 1111",
            "DAY_TO_SECOND, 1111"})
    void mapsJdbcTypes(PropertyType type, int expectedJdbcType) {
        assertEquals(expectedJdbcType, UltipaTypeMapper.toJdbcType(type),
                "unexpected JDBC type for " + type);
    }

    @Test
    @DisplayName("an unknown type maps to OTHER rather than throwing")
    void unknownTypeMapsToOther() {
        assertEquals(Types.OTHER, UltipaTypeMapper.toJdbcType(null));
        assertEquals(Types.OTHER, UltipaTypeMapper.toJdbcType(PropertyType.UNSET));
    }

    @Test
    @DisplayName("reports the type name in the server's lower-case spelling")
    void reportsTypeNames() {
        assertAll(
                () -> assertEquals("int64", UltipaTypeMapper.typeName(PropertyType.INT64)),
                () -> assertEquals("zoned_datetime",
                        UltipaTypeMapper.typeName(PropertyType.ZONED_DATETIME)),
                () -> assertEquals("unset", UltipaTypeMapper.typeName(null)));
    }

    @Test
    @DisplayName("converts scalar values through the wire encoding")
    void convertsScalars() {
        assertAll(
                () -> assertEquals(true, UltipaTypeMapper.toJavaObject(TypedValue.fromValue(true))),
                () -> assertEquals(42, UltipaTypeMapper.toJavaObject(TypedValue.fromValue(42))),
                () -> assertEquals(42L, UltipaTypeMapper.toJavaObject(TypedValue.fromValue(42L))),
                () -> assertEquals(1.5f, UltipaTypeMapper.toJavaObject(TypedValue.fromValue(1.5f))),
                () -> assertEquals(2.5d, UltipaTypeMapper.toJavaObject(TypedValue.fromValue(2.5d))),
                () -> assertEquals("hello",
                        UltipaTypeMapper.toJavaObject(TypedValue.fromValue("hello"))));
    }

    @Test
    @DisplayName("returns null for a null value, and for a NULL-typed value")
    void convertsNulls() {
        assertAll(
                () -> assertNull(UltipaTypeMapper.toJavaObject(null)),
                () -> assertNull(UltipaTypeMapper.toJavaObject(TypedValue.fromValue(null))));
    }

    @Test
    @DisplayName("maps DECIMAL to BigDecimal without going through a double")
    void mapsDecimalExactly() {
        BigDecimal precise = new BigDecimal("12345678901234567890.123456789");
        Object converted = UltipaTypeMapper.toJavaObject(TypedValue.fromValue(precise));

        assertAll(
                () -> assertInstanceOf(BigDecimal.class, converted),
                () -> assertEquals(0, precise.compareTo((BigDecimal) converted)));
    }

    @Test
    @DisplayName("maps every temporal type to its java.time counterpart")
    void mapsTemporals() {
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 10, 18),
                        UltipaTypeMapper.convert(new GqldbDate(LocalDate.of(2026, 10, 18)))),
                () -> assertEquals(LocalDateTime.of(2026, 10, 18, 12, 30, 15),
                        UltipaTypeMapper.convert(new GqldbLocalDateTime(
                                LocalDateTime.of(2026, 10, 18, 12, 30, 15)))),
                () -> assertEquals(LocalTime.of(12, 30, 15),
                        UltipaTypeMapper.convert(new GqldbLocalTime(LocalTime.of(12, 30, 15)))),
                () -> assertEquals(OffsetTime.parse("12:30:15+02:00"),
                        UltipaTypeMapper.convert(
                                new GqldbZonedTime(OffsetTime.parse("12:30:15+02:00")))),
                () -> assertEquals(OffsetDateTime.parse("2026-10-18T12:30:15+02:00"),
                        UltipaTypeMapper.convert(new GqldbZonedDateTime(
                                OffsetDateTime.parse("2026-10-18T12:30:15+02:00")))),
                () -> assertEquals(new BigDecimal("1.25"),
                        UltipaTypeMapper.convert(new GqldbDecimal("1.25"))),
                () -> assertEquals(Duration.ofSeconds(90),
                        UltipaTypeMapper.convert(
                                new com.gqldb.types.DayToSecond(Duration.ofSeconds(90)))),
                () -> assertEquals(Period.ofMonths(14).normalized(),
                        ((Period) UltipaTypeMapper.convert(
                                new com.gqldb.types.YearToMonth(Period.ofMonths(14)))).normalized()));
    }

    @Test
    @DisplayName("maps a node to GraphNode, keeping id, labels and properties")
    void mapsNode() {
        GqldbNode source = new GqldbNode("P1", "uuid-1", List.of("Person", "Customer"),
                Map.of("name", "Alice"));

        Object converted = UltipaTypeMapper.convert(source);

        GraphNode node = assertInstanceOf(GraphNode.class, converted);
        assertAll(
                () -> assertEquals("P1", node.getId()),
                () -> assertEquals("uuid-1", node.getUuid()),
                () -> assertEquals(List.of("Person", "Customer"), node.getLabels()),
                () -> assertEquals("Alice", node.getProperty("name")));
    }

    @Test
    @DisplayName("maps an edge to GraphEdge, keeping its endpoints")
    void mapsEdge() {
        GqldbEdge source = new GqldbEdge("E1", "uuid-e", "KNOWS", "P1", "P2", Map.of("since", 2020));

        GraphEdge edge = assertInstanceOf(GraphEdge.class, UltipaTypeMapper.convert(source));
        assertAll(
                () -> assertEquals("E1", edge.getId()),
                () -> assertEquals("KNOWS", edge.getLabel()),
                () -> assertEquals("P1", edge.getFromNodeId()),
                () -> assertEquals("P2", edge.getToNodeId()),
                () -> assertEquals(2020, edge.getProperty("since")));
    }

    @Test
    @DisplayName("maps a path, converting its nodes and edges and preserving order")
    void mapsPath() {
        GqldbNode first = new GqldbNode("P1", List.of("Person"), Map.of());
        GqldbNode second = new GqldbNode("P2", List.of("Person"), Map.of());
        GqldbEdge edge = new GqldbEdge("E1", "KNOWS", "P1", "P2", Map.of());

        GraphPath path = assertInstanceOf(GraphPath.class,
                UltipaTypeMapper.convert(new GqldbPath(List.of(first, second), List.of(edge))));

        assertAll(
                () -> assertEquals(2, path.getNodes().size()),
                () -> assertEquals(1, path.length()),
                () -> assertEquals("P1", path.getStartNode().getId()),
                () -> assertEquals("P2", path.getEndNode().getId()),
                () -> assertEquals("KNOWS", path.getEdges().get(0).getLabel()));
    }

    @Test
    @DisplayName("maps a 2D point with longitude on x, as the SDK defines it")
    void maps2dPoint() {
        GraphPoint point = assertInstanceOf(GraphPoint.class,
                UltipaTypeMapper.convert(new Point(45.0, 9.0, 4326)));

        assertAll(
                () -> assertFalse(point.is3D()),
                () -> assertEquals(9.0, point.getLongitude()),
                () -> assertEquals(9.0, point.getX()),
                () -> assertEquals(45.0, point.getLatitude()),
                () -> assertEquals(45.0, point.getY()),
                () -> assertEquals(4326, point.getSrid()),
                () -> assertTrue(point.getZ().isEmpty()));
    }

    @Test
    @DisplayName("maps a 3D point, keeping the z ordinate")
    void maps3dPoint() {
        GraphPoint point = assertInstanceOf(GraphPoint.class,
                UltipaTypeMapper.convert(new Point3D(1.0, 2.0, 3.0, 0)));

        assertAll(
                () -> assertTrue(point.is3D()),
                () -> assertEquals(1.0, point.getX()),
                () -> assertEquals(2.0, point.getY()),
                () -> assertEquals(3.0, point.getZ().orElseThrow()));
    }

    @Test
    @DisplayName("maps a nested table and an error value")
    void mapsTableAndError() {
        GraphTable table = assertInstanceOf(GraphTable.class, UltipaTypeMapper.convert(
                new GqldbTable(List.of("a", "b"), List.of(List.of(1, 2), List.of(3, 4)))));
        GraphError error = assertInstanceOf(GraphError.class,
                UltipaTypeMapper.convert(new GqldbError(5020, "index loading")));

        assertAll(
                () -> assertEquals(List.of("a", "b"), table.getColumns()),
                () -> assertEquals(2, table.getRowCount()),
                () -> assertEquals(5020, error.getCode()),
                () -> assertEquals("index loading", error.getMessage()));
    }

    @Test
    @DisplayName("maps a vector to float[]")
    void mapsVector() {
        Object converted = UltipaTypeMapper.convert(new Vector(new float[] {1.0f, 2.0f}));
        assertArrayEquals(new float[] {1.0f, 2.0f}, (float[]) converted);
    }

    @Test
    @DisplayName("converts recursively through lists, sets and maps")
    void convertsRecursively() {
        Object converted = UltipaTypeMapper.convert(List.of(
                new GqldbDate(LocalDate.of(2026, 1, 1)),
                Map.of("inner", new GqldbDecimal("2.50")),
                Set.of(new GqldbDate(LocalDate.of(2026, 1, 2)))));

        List<?> list = assertInstanceOf(List.class, converted);
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 1, 1), list.get(0)),
                () -> assertEquals(new BigDecimal("2.50"),
                        ((Map<?, ?>) list.get(1)).get("inner")),
                () -> assertEquals(Set.of(LocalDate.of(2026, 1, 2)), list.get(2)));
    }

    @Test
    @DisplayName("converts a node's nested property values too, so no SDK type escapes")
    void convertsNestedNodeProperties() {
        GqldbNode source = new GqldbNode("P1", List.of("Person"),
                Map.of("born", new GqldbDate(LocalDate.of(1990, 5, 1)),
                        "scores", List.of(new GqldbDecimal("1.5"))));

        GraphNode node = (GraphNode) UltipaTypeMapper.convert(source);

        assertAll(
                () -> assertEquals(LocalDate.of(1990, 5, 1), node.getProperty("born")),
                () -> assertEquals(List.of(new BigDecimal("1.5")), node.getProperty("scores")));
    }

    @Test
    @DisplayName("resolves server property type names, and does not guess at unknown ones")
    void resolvesServerTypeNames() {
        assertAll(
                () -> assertEquals(PropertyType.STRING,
                        UltipaTypeMapper.fromServerTypeName("string")),
                () -> assertEquals(PropertyType.INT64, UltipaTypeMapper.fromServerTypeName("INT64")),
                () -> assertEquals(PropertyType.INT32, UltipaTypeMapper.fromServerTypeName("int")),
                () -> assertEquals(PropertyType.DOUBLE,
                        UltipaTypeMapper.fromServerTypeName("float64")),
                () -> assertEquals(PropertyType.LOCAL_DATETIME,
                        UltipaTypeMapper.fromServerTypeName("datetime")),
                () -> assertEquals(PropertyType.UNSET,
                        UltipaTypeMapper.fromServerTypeName("something_new")),
                () -> assertEquals(PropertyType.UNSET, UltipaTypeMapper.fromServerTypeName(null)));
    }

    @Test
    @DisplayName("renders values as text predictably, with Base64 for binary")
    void rendersAsString() {
        assertAll(
                () -> assertNull(UltipaTypeMapper.renderAsString(null)),
                () -> assertEquals("abc", UltipaTypeMapper.renderAsString("abc")),
                () -> assertEquals("2026-10-18",
                        UltipaTypeMapper.renderAsString(LocalDate.of(2026, 10, 18))),
                () -> assertEquals("AQID",
                        UltipaTypeMapper.renderAsString(new byte[] {1, 2, 3})),
                () -> assertEquals("[1.0, 2.0]",
                        UltipaTypeMapper.renderAsString(new float[] {1.0f, 2.0f})));
    }

    @Test
    @DisplayName("identifies the graph-specific types")
    void identifiesGraphSpecificTypes() {
        assertAll(
                () -> assertTrue(UltipaTypeMapper.isGraphSpecific(PropertyType.NODE)),
                () -> assertTrue(UltipaTypeMapper.isGraphSpecific(PropertyType.PATH)),
                () -> assertTrue(UltipaTypeMapper.isGraphSpecific(PropertyType.POINT)),
                () -> assertFalse(UltipaTypeMapper.isGraphSpecific(PropertyType.STRING)),
                () -> assertFalse(UltipaTypeMapper.isGraphSpecific(PropertyType.LIST)),
                () -> assertFalse(UltipaTypeMapper.isGraphSpecific(null)));
    }

    @Test
    @DisplayName("names the Java class each type is returned as")
    void namesJavaClasses() {
        assertAll(
                () -> assertEquals("java.lang.String",
                        UltipaTypeMapper.javaClassName(PropertyType.STRING)),
                () -> assertEquals("java.time.LocalDate",
                        UltipaTypeMapper.javaClassName(PropertyType.DATE)),
                () -> assertEquals("java.time.Instant",
                        UltipaTypeMapper.javaClassName(PropertyType.TIMESTAMP)),
                () -> assertEquals(GraphNode.class.getName(),
                        UltipaTypeMapper.javaClassName(PropertyType.NODE)),
                () -> assertEquals("[B", UltipaTypeMapper.javaClassName(PropertyType.BLOB)),
                () -> assertEquals("java.lang.Object", UltipaTypeMapper.javaClassName(null)));
    }
}
