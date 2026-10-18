package com.ultipa.jdbc;

import com.gqldb.types.GqldbNode;
import com.ultipa.jdbc.graph.GraphNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaResultSetTest {

    private ResultSet query(List<String> columns, List<List<Object>> rows) throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(columns, rows, 0));
        Connection connection = TestConnections.connection(executor);
        return connection.createStatement().executeQuery("MATCH (n) RETURN n");
    }

    @Test
    @DisplayName("the cursor starts before the first row and advances with next()")
    void iteratesRows() throws SQLException {
        ResultSet rows = query(List.of("name"),
                List.of(List.of("Alice"), List.of("Bob")));

        assertAll(
                () -> assertTrue(rows.isBeforeFirst()),
                () -> assertEquals(0, rows.getRow()));

        assertTrue(rows.next());
        assertAll(
                () -> assertEquals("Alice", rows.getString(1)),
                () -> assertEquals(1, rows.getRow()),
                () -> assertTrue(rows.isFirst()));

        assertTrue(rows.next());
        assertAll(
                () -> assertEquals("Bob", rows.getString(1)),
                () -> assertEquals(2, rows.getRow()),
                () -> assertTrue(rows.isLast()));

        assertFalse(rows.next());
        assertAll(
                () -> assertTrue(rows.isAfterLast()),
                () -> assertFalse(rows.next(), "next() past the end stays false"));
    }

    @Test
    @DisplayName("an empty result reports no rows without pretending to be positioned")
    void handlesEmptyResult() throws SQLException {
        ResultSet rows = query(List.of("name"), List.of());

        assertAll(
                () -> assertFalse(rows.next()),
                () -> assertFalse(rows.isBeforeFirst()),
                () -> assertFalse(rows.isAfterLast()),
                () -> assertFalse(rows.isFirst()),
                () -> assertFalse(rows.isLast()));
    }

    @Test
    @DisplayName("reading before next() or after the last row is an error, not a silent null")
    void rejectsReadingOffCursor() throws SQLException {
        ResultSet rows = query(List.of("name"), List.of(List.of("Alice")));

        SQLException beforeFirst = assertThrows(SQLException.class, () -> rows.getString(1));
        assertTrue(beforeFirst.getMessage().contains("before the first row"));

        rows.next();
        rows.next();
        SQLException afterLast = assertThrows(SQLException.class, () -> rows.getString(1));
        assertTrue(afterLast.getMessage().contains("after the last row"));
    }

    @Test
    @DisplayName("columns are 1-based and out-of-range indexes are rejected")
    void usesOneBasedIndexes() throws SQLException {
        ResultSet rows = query(List.of("a", "b"), List.of(Arrays.asList("first", "second")));
        rows.next();

        assertAll(
                () -> assertEquals("first", rows.getString(1)),
                () -> assertEquals("second", rows.getString(2)),
                () -> assertThrows(SQLException.class, () -> rows.getString(0)),
                () -> assertThrows(SQLException.class, () -> rows.getString(3)));
    }

    @Test
    @DisplayName("finds columns by label, case-insensitively, with the first duplicate winning")
    void findsColumnsByLabel() throws SQLException {
        ResultSet rows = query(List.of("name", "age", "name"),
                List.of(Arrays.asList("Alice", 30, "Bob")));
        rows.next();

        assertAll(
                () -> assertEquals(1, rows.findColumn("name")),
                () -> assertEquals(1, rows.findColumn("NAME")),
                () -> assertEquals(2, rows.findColumn("age")),
                () -> assertEquals("Alice", rows.getString("name")),
                () -> assertEquals(30, rows.getInt("AGE")),
                () -> assertThrows(SQLException.class, () -> rows.findColumn("missing")),
                () -> assertThrows(SQLException.class, () -> rows.findColumn(null)));
    }

    @Test
    @DisplayName("wasNull reflects the last column read")
    void tracksWasNull() throws SQLException {
        ResultSet rows = query(List.of("a", "b"), List.of(Arrays.asList(null, "value")));
        rows.next();

        assertAll(
                () -> assertNull(rows.getString(1)),
                () -> assertTrue(rows.wasNull()),
                () -> assertEquals("value", rows.getString(2)),
                () -> assertFalse(rows.wasNull()));
    }

    @Test
    @DisplayName("a null numeric column reads as zero with wasNull set, as JDBC requires")
    void readsNullNumericAsZero() throws SQLException {
        ResultSet rows = query(List.of("n"), List.of(Arrays.asList((Object) null)));
        rows.next();

        assertAll(
                () -> assertEquals(0, rows.getInt(1)),
                () -> assertTrue(rows.wasNull()),
                () -> assertEquals(0L, rows.getLong(1)),
                () -> assertEquals(0.0d, rows.getDouble(1)),
                () -> assertFalse(rows.getBoolean(1)),
                () -> assertNull(rows.getBigDecimal(1)),
                () -> assertNull(rows.getObject(1)));
    }

    @Test
    @DisplayName("converts between numeric types, range-checking rather than wrapping")
    void convertsNumbers() throws SQLException {
        ResultSet rows = query(List.of("small", "big", "text", "real"),
                List.of(Arrays.asList(42, 9_000_000_000L, "123", 2.75d)));
        rows.next();

        assertAll(
                () -> assertEquals(42, rows.getInt(1)),
                () -> assertEquals(42L, rows.getLong(1)),
                () -> assertEquals((short) 42, rows.getShort(1)),
                () -> assertEquals((byte) 42, rows.getByte(1)),
                () -> assertEquals(42.0d, rows.getDouble(1)),
                () -> assertEquals(9_000_000_000L, rows.getLong(2)),
                () -> assertEquals(123, rows.getInt(3), "a numeric string converts"),
                () -> assertEquals(new BigDecimal("2.75"), rows.getBigDecimal(4)),
                () -> assertEquals(2, rows.getInt(4), "a fractional value truncates toward zero"));

        SQLException overflow = assertThrows(SQLException.class, () -> rows.getInt(2));
        assertTrue(overflow.getMessage().contains("out of range"), overflow.getMessage());
    }

    @Test
    @DisplayName("refuses a conversion that is not well defined, naming both types")
    void refusesUndefinedConversions() throws SQLException {
        ResultSet rows = query(List.of("word"), List.of(List.of("not a number")));
        rows.next();

        SQLException failure = assertThrows(SQLException.class, () -> rows.getInt(1));
        assertAll(
                () -> assertTrue(failure.getMessage().contains("Column 1")),
                () -> assertTrue(failure.getMessage().contains("string")),
                () -> assertTrue(failure.getMessage().contains("int")));
    }

    @Test
    @DisplayName("converts booleans from numbers and from the usual spellings")
    void convertsBooleans() throws SQLException {
        ResultSet rows = query(List.of("a", "b", "c", "d", "e"),
                List.of(Arrays.asList(true, 1, 0, "yes", "false")));
        rows.next();

        assertAll(
                () -> assertTrue(rows.getBoolean(1)),
                () -> assertTrue(rows.getBoolean(2)),
                () -> assertFalse(rows.getBoolean(3)),
                () -> assertTrue(rows.getBoolean(4)),
                () -> assertFalse(rows.getBoolean(5)));
    }

    @Test
    @DisplayName("reads temporal columns as java.sql and java.time types")
    void readsTemporals() throws SQLException {
        ResultSet rows = query(List.of("d", "ts"),
                List.of(Arrays.asList(LocalDate.of(2026, 10, 18),
                        LocalDateTime.of(2026, 10, 18, 9, 30))));
        rows.next();

        assertAll(
                () -> assertEquals("2026-10-18", rows.getDate(1).toString()),
                () -> assertEquals(LocalDate.of(2026, 10, 18), rows.getObject(1, LocalDate.class)),
                () -> assertEquals("2026-10-18 09:30:00.0", rows.getTimestamp(2).toString()),
                () -> assertEquals(LocalDateTime.of(2026, 10, 18, 9, 30),
                        rows.getObject(2, LocalDateTime.class)),
                () -> assertEquals("2026-10-18", rows.getString(1), "ISO-8601 text"));
    }

    @Test
    @DisplayName("returns a graph node as GraphNode, keeping its structure")
    void readsGraphValues() throws SQLException {
        com.gqldb.types.TypedValue node = com.gqldb.types.TypedValue.fromObject(
                com.gqldb.types.PropertyType.NODE,
                new GqldbNode("P1", List.of("Person"), java.util.Map.of("name", "Alice")));

        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.singleValue("n", node));
        ResultSet rows = TestConnections.connection(executor)
                .createStatement().executeQuery("MATCH (n) RETURN n");
        rows.next();

        GraphNode graphNode = assertInstanceOf(GraphNode.class, rows.getObject(1));
        assertAll(
                () -> assertEquals("P1", graphNode.getId()),
                () -> assertEquals(List.of("Person"), graphNode.getLabels()),
                () -> assertEquals("Alice", graphNode.getProperty("name")),
                () -> assertTrue(rows.getString(1).contains("Alice"),
                        "getString keeps the structure visible"));
    }

    @Test
    @DisplayName("reads binary columns as byte[], and as a defensive copy")
    void readsBinary() throws SQLException {
        ResultSet rows = query(List.of("data"), List.of(List.of(new byte[] {1, 2, 3})));
        rows.next();

        byte[] first = rows.getBytes(1);
        first[0] = 99;

        assertAll(
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new byte[] {1, 2, 3}, rows.getBytes(1), "the stored value is unchanged"),
                () -> assertEquals("AQID", rows.getString(1), "Base64 text"));
    }

    @Test
    @DisplayName("infers column metadata from the first row")
    void infersMetadataFromFirstRow() throws SQLException {
        ResultSet rows = query(List.of("name", "age", "score"),
                List.of(Arrays.asList("Alice", 30L, 1.5d)));
        ResultSetMetaData metaData = rows.getMetaData();

        assertAll(
                () -> assertEquals(3, metaData.getColumnCount()),
                () -> assertEquals("name", metaData.getColumnLabel(1)),
                () -> assertEquals("name", metaData.getColumnName(1)),
                () -> assertEquals(Types.VARCHAR, metaData.getColumnType(1)),
                () -> assertEquals("string", metaData.getColumnTypeName(1)),
                () -> assertEquals("java.lang.String", metaData.getColumnClassName(1)),
                () -> assertEquals(Types.BIGINT, metaData.getColumnType(2)),
                () -> assertEquals(Types.DOUBLE, metaData.getColumnType(3)),
                () -> assertTrue(metaData.isSigned(2)),
                () -> assertFalse(metaData.isSigned(1)),
                () -> assertTrue(metaData.isCaseSensitive(1)),
                () -> assertTrue(metaData.isReadOnly(1)),
                () -> assertFalse(metaData.isWritable(1)),
                () -> assertEquals(ResultSetMetaData.columnNullableUnknown, metaData.isNullable(1)),
                () -> assertThrows(SQLException.class, () -> metaData.getColumnLabel(4)));
    }

    @Test
    @DisplayName("an empty result reports its columns as NULL-typed rather than guessing")
    void reportsUnknownTypesForEmptyResult() throws SQLException {
        ResultSetMetaData metaData = query(List.of("name"), List.of()).getMetaData();

        assertAll(
                () -> assertEquals(1, metaData.getColumnCount()),
                () -> assertEquals("name", metaData.getColumnLabel(1)),
                () -> assertEquals(Types.NULL, metaData.getColumnType(1)),
                () -> assertEquals("null", metaData.getColumnTypeName(1)));
    }

    @Test
    @DisplayName("honours Statement.setMaxRows by not exposing the extra rows")
    void honoursMaxRows() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(FakeGqlExecutor.response(
                List.of("n"), List.of(List.of(1), List.of(2), List.of(3)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.setMaxRows(2);

        ResultSet rows = statement.executeQuery("MATCH (n) RETURN n");

        int count = 0;
        while (rows.next()) {
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    @DisplayName("is forward-only and read-only, and says so")
    void isForwardOnlyAndReadOnly() throws SQLException {
        ResultSet rows = query(List.of("n"), List.of(List.of(1)));

        assertAll(
                () -> assertEquals(ResultSet.TYPE_FORWARD_ONLY, rows.getType()),
                () -> assertEquals(ResultSet.CONCUR_READ_ONLY, rows.getConcurrency()),
                () -> assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, rows.getHoldability()),
                () -> assertEquals(ResultSet.FETCH_FORWARD, rows.getFetchDirection()),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::previous),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::first),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::last),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::beforeFirst),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::afterLast),
                () -> assertThrows(SQLFeatureNotSupportedException.class, () -> rows.absolute(1)),
                () -> assertThrows(SQLFeatureNotSupportedException.class, () -> rows.relative(1)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> rows.updateString(1, "x")),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::insertRow),
                () -> assertThrows(SQLFeatureNotSupportedException.class, rows::deleteRow));
    }

    @Test
    @DisplayName("accepts a fetch size and rejects a negative one")
    void acceptsFetchSize() throws SQLException {
        ResultSet rows = query(List.of("n"), List.of(List.of(1)));

        rows.setFetchSize(100);
        assertAll(
                () -> assertEquals(0, rows.getFetchSize()),
                () -> assertThrows(SQLException.class, () -> rows.setFetchSize(-1)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> rows.setFetchDirection(ResultSet.FETCH_REVERSE)));
    }

    @Test
    @DisplayName("every operation on a closed result set fails, and close is idempotent")
    void rejectsUseAfterClose() throws SQLException {
        ResultSet rows = query(List.of("n"), List.of(List.of(1)));
        rows.next();
        rows.close();
        rows.close();

        assertAll(
                () -> assertTrue(rows.isClosed()),
                () -> assertThrows(SQLException.class, rows::next),
                () -> assertThrows(SQLException.class, () -> rows.getString(1)),
                () -> assertThrows(SQLException.class, rows::getMetaData),
                () -> assertThrows(SQLException.class, rows::wasNull));
    }

    @Test
    @DisplayName("getObject with a target type accepts a primitive class literal")
    void getObjectAcceptsPrimitiveClasses() throws SQLException {
        ResultSet rows = query(List.of("n", "flag"), List.of(Arrays.asList(7, true)));
        rows.next();

        assertAll(
                () -> assertEquals(7, rows.getObject(1, int.class)),
                () -> assertEquals(7L, rows.getObject(1, long.class)),
                () -> assertEquals(7.0d, rows.getObject(1, double.class)),
                () -> assertEquals(true, rows.getObject(2, boolean.class)),
                () -> assertEquals(Integer.valueOf(7), rows.getObject(1, Integer.class)),
                () -> assertThrows(SQLException.class,
                        () -> rows.getObject(1, (Class<?>) null)));
    }

    @Test
    @DisplayName("unwraps to its own type and refuses anything else")
    void unwraps() throws SQLException {
        ResultSet rows = query(List.of("n"), List.of(List.of(1)));

        assertAll(
                () -> assertTrue(rows.isWrapperFor(UltipaResultSet.class)),
                () -> assertEquals(rows, rows.unwrap(UltipaResultSet.class)),
                () -> assertFalse(rows.isWrapperFor(String.class)),
                () -> assertThrows(SQLException.class, () -> rows.unwrap(String.class)));
    }
}
