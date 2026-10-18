package com.ultipa.jdbc;

import com.ultipa.jdbc.graph.GraphPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaPreparedStatementTest {

    private FakeGqlExecutor executor;
    private Connection connection;

    private PreparedStatement prepare(String gql) throws SQLException {
        executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 1));
        connection = TestConnections.connection(executor);
        return connection.prepareStatement(gql);
    }

    @Test
    @DisplayName("binds values as protocol parameters, never into the query text")
    void bindsValuesOutOfBand() throws SQLException {
        PreparedStatement statement =
                prepare("MATCH (n:Person) WHERE n.name = ? AND n.age > ? RETURN n");
        statement.setString(1, "Robert'); DROP GRAPH social; --");
        statement.setInt(2, 30);
        statement.executeUpdate();

        FakeGqlExecutor.Executed executed = executor.lastExecution();
        assertAll(
                () -> assertEquals(
                        "MATCH (n:Person) WHERE n.name = $jdbc_p1 AND n.age > $jdbc_p2 RETURN n",
                        executed.gql()),
                () -> assertFalse(executed.gql().contains("DROP GRAPH"),
                        "the value must not appear in the query text"),
                () -> assertFalse(executed.gql().contains("Robert")),
                () -> assertEquals("Robert'); DROP GRAPH social; --",
                        executed.parameters().get("jdbc_p1")),
                () -> assertEquals(30, executed.parameters().get("jdbc_p2")));
    }

    @Test
    @DisplayName("sends the parameter names without the leading dollar, as the protocol expects")
    void usesBareParameterNames() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");
        statement.setString(1, "value");
        statement.executeUpdate();

        assertAll(
                () -> assertEquals(List.of("jdbc_p1"),
                        List.copyOf(executor.lastExecution().parameters().keySet())),
                () -> assertTrue(executor.lastExecution().gql().contains("$jdbc_p1")));
    }

    @Test
    @DisplayName("binds every supported scalar type")
    void bindsScalars() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?, ?, ?, ?, ?, ?, ?, ?, ?");
        statement.setBoolean(1, true);
        statement.setByte(2, (byte) 7);
        statement.setShort(3, (short) 8);
        statement.setInt(4, 9);
        statement.setLong(5, 10L);
        statement.setFloat(6, 1.5f);
        statement.setDouble(7, 2.5d);
        statement.setBigDecimal(8, new BigDecimal("3.25"));
        statement.setString(9, "text");
        statement.setBytes(10, new byte[] {1, 2});
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        assertAll(
                () -> assertEquals(true, parameters.get("jdbc_p1")),
                () -> assertEquals(7, parameters.get("jdbc_p2"), "byte widens to int"),
                () -> assertEquals(8, parameters.get("jdbc_p3"), "short widens to int"),
                () -> assertEquals(9, parameters.get("jdbc_p4")),
                () -> assertEquals(10L, parameters.get("jdbc_p5")),
                () -> assertEquals(1.5f, parameters.get("jdbc_p6")),
                () -> assertEquals(2.5d, parameters.get("jdbc_p7")),
                () -> assertEquals(new BigDecimal("3.25"), parameters.get("jdbc_p8")),
                () -> assertEquals("text", parameters.get("jdbc_p9")),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new byte[] {1, 2}, (byte[]) parameters.get("jdbc_p10")));
    }

    @Test
    @DisplayName("maps java.sql temporals to the matching GQLDB temporal, not to a bare instant")
    void bindsTemporalsWithTheRightSemantics() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?, ?");
        statement.setDate(1, Date.valueOf(LocalDate.of(2026, 10, 18)));
        statement.setTime(2, Time.valueOf(LocalTime.of(9, 30, 15)));
        statement.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 10, 18, 9, 30, 15)));
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 10, 18), parameters.get("jdbc_p1")),
                () -> assertEquals(LocalTime.of(9, 30, 15), parameters.get("jdbc_p2")),
                () -> assertEquals(LocalDateTime.of(2026, 10, 18, 9, 30, 15),
                        parameters.get("jdbc_p3")));
    }

    @Test
    @DisplayName("binds collections, converting their elements too")
    void bindsCollections() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?");
        statement.setObject(1, List.of(1, (short) 2, "three"));
        statement.setObject(2, Map.of("key", (byte) 4));
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        assertAll(
                () -> assertEquals(List.of(1, 2, "three"), parameters.get("jdbc_p1")),
                () -> assertEquals(Map.of("key", 4), parameters.get("jdbc_p2")));
    }

    @Test
    @DisplayName("binds a spatial point and a vector")
    void bindsGraphValues() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?");
        statement.setObject(1, GraphPoint.of2D(9.0, 45.0, 4326));
        statement.setObject(2, new float[] {1.0f, 2.0f});
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        com.gqldb.types.Point point = (com.gqldb.types.Point) parameters.get("jdbc_p1");
        assertAll(
                () -> assertEquals(9.0, point.getLongitude()),
                () -> assertEquals(45.0, point.getLatitude()),
                () -> assertEquals(4326, point.getSrid()),
                () -> assertEquals(2, ((com.gqldb.types.Vector) parameters.get("jdbc_p2"))
                        .getDimensions()));
    }

    @Test
    @DisplayName("binds a null, with no type hint required")
    void bindsNull() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?");
        statement.setNull(1, Types.VARCHAR);
        statement.setObject(2, null);
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        assertAll(
                () -> assertTrue(parameters.containsKey("jdbc_p1")),
                () -> assertNull(parameters.get("jdbc_p1")),
                () -> assertNull(parameters.get("jdbc_p2")));
    }

    @Test
    @DisplayName("coerces a value to the declared target SQL type")
    void coercesToTargetType() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?, ?, ?");
        statement.setObject(1, 42, Types.BIGINT);
        statement.setObject(2, "3.5", Types.DECIMAL);
        statement.setObject(3, 1, Types.BOOLEAN);
        statement.setObject(4, "2026-10-18", Types.DATE);
        statement.executeUpdate();

        Map<String, Object> parameters = executor.lastExecution().parameters();
        assertAll(
                () -> assertEquals(42L, parameters.get("jdbc_p1")),
                () -> assertEquals(new BigDecimal("3.5"), parameters.get("jdbc_p2")),
                () -> assertEquals(true, parameters.get("jdbc_p3")),
                () -> assertEquals(LocalDate.of(2026, 10, 18), parameters.get("jdbc_p4")));
    }

    @Test
    @DisplayName("rejects a parameter type the protocol cannot encode, rather than approximating")
    void rejectsUnsupportedTypes() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");

        SQLException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> statement.setObject(1, new Object()));
        assertAll(
                () -> assertTrue(failure.getMessage().contains("java.lang.Object")),
                () -> assertTrue(failure.getMessage().contains("parameter 1")),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setObject(1, Map.of(1, "non-string key"))));
    }

    @Test
    @DisplayName("refuses to execute with a parameter left unset")
    void refusesUnsetParameters() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?, ?");
        statement.setInt(1, 1);

        SQLException failure = assertThrows(SQLException.class, statement::executeUpdate);
        assertTrue(failure.getMessage().contains("Parameter 2 of 2"), failure.getMessage());
    }

    @Test
    @DisplayName("rejects a parameter index outside the placeholder range")
    void rejectsBadParameterIndex() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");

        assertAll(
                () -> assertThrows(SQLException.class, () -> statement.setInt(0, 1)),
                () -> assertThrows(SQLException.class, () -> statement.setInt(2, 1)));
    }

    @Test
    @DisplayName("clearParameters requires the values to be set again")
    void clearsParameters() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");
        statement.setInt(1, 1);
        statement.clearParameters();

        assertThrows(SQLException.class, statement::executeUpdate);
    }

    @Test
    @DisplayName("reports an exact parameter count and honestly unknown parameter types")
    void reportsParameterMetaData() throws SQLException {
        PreparedStatement statement = prepare("MATCH (n) WHERE n.a = ? AND n.b = ? RETURN n");
        ParameterMetaData metaData = statement.getParameterMetaData();

        assertAll(
                () -> assertEquals(2, metaData.getParameterCount()),
                () -> assertEquals(Types.OTHER, metaData.getParameterType(1)),
                () -> assertEquals("unknown", metaData.getParameterTypeName(1)),
                () -> assertEquals(ParameterMetaData.parameterModeIn, metaData.getParameterMode(1)),
                () -> assertEquals(ParameterMetaData.parameterNullableUnknown,
                        metaData.isNullable(1)),
                () -> assertThrows(SQLException.class, () -> metaData.getParameterType(3)));
    }

    @Test
    @DisplayName("getMetaData is null before execution and describes the result after it")
    void reportsResultMetaDataAfterExecution() throws SQLException {
        executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of("name"), List.of(List.of("Alice")), 0));
        connection = TestConnections.connection(executor);
        PreparedStatement statement = connection.prepareStatement("MATCH (n) RETURN n.name AS name");

        assertNull(statement.getMetaData());

        statement.executeQuery();
        assertAll(
                () -> assertEquals(1, statement.getMetaData().getColumnCount()),
                () -> assertEquals("name", statement.getMetaData().getColumnLabel(1)));
    }

    @Test
    @DisplayName("runs a parameter batch, one execution per queued parameter set")
    void runsParameterBatch() throws SQLException {
        PreparedStatement statement = prepare("INSERT (:Person {name: ?})");
        statement.setString(1, "Alice");
        statement.addBatch();
        statement.setString(1, "Bob");
        statement.addBatch();

        int[] counts = statement.executeBatch();

        assertAll(
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {1, 1}, counts),
                () -> assertEquals(2, executor.executions().size()),
                () -> assertEquals("Alice", executor.executions().get(0).parameters().get("jdbc_p1")),
                () -> assertEquals("Bob", executor.executions().get(1).parameters().get("jdbc_p1")),
                () -> assertEquals(0, statement.executeBatch().length));
    }

    @Test
    @DisplayName("refuses the Statement methods that would supply different GQL text")
    void refusesNewGqlText() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");

        assertAll(
                () -> assertThrows(SQLException.class, () -> statement.execute("MATCH (n) RETURN n")),
                () -> assertThrows(SQLException.class,
                        () -> statement.executeQuery("MATCH (n) RETURN n")),
                () -> assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE n")),
                () -> assertThrows(SQLException.class, () -> statement.addBatch("DELETE n")));
    }

    @Test
    @DisplayName("refuses to bind streams and SQL large objects, suggesting what to use instead")
    void refusesStreamsAndLobs() throws SQLException {
        PreparedStatement statement = prepare("RETURN ?");

        assertAll(
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setBinaryStream(1, new java.io.ByteArrayInputStream(new byte[0]))),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setCharacterStream(1, new java.io.StringReader(""))),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setBlob(1, (java.sql.Blob) null)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setClob(1, (java.sql.Clob) null)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setArray(1, null)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setRowId(1, null)));
    }

    @Test
    @DisplayName("exposes both the original and the rewritten text for diagnostics")
    void exposesBothTexts() throws SQLException {
        PreparedStatement statement = prepare("MATCH (n) WHERE n.a = ? RETURN n");
        UltipaPreparedStatement ultipa = statement.unwrap(UltipaPreparedStatement.class);

        assertAll(
                () -> assertEquals("MATCH (n) WHERE n.a = ? RETURN n", ultipa.getOriginalGql()),
                () -> assertEquals("MATCH (n) WHERE n.a = $jdbc_p1 RETURN n",
                        ultipa.getRewrittenGql()));
    }

    @Test
    @DisplayName("executeQuery and executeUpdate keep their contracts on a prepared statement")
    void keepsExecutionContracts() throws SQLException {
        FakeGqlExecutor reading = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of("n"), List.of(List.of(1)), 0));
        PreparedStatement read = TestConnections.connection(reading).prepareStatement("RETURN ?");
        read.setInt(1, 1);
        ResultSet rows = read.executeQuery();

        assertAll(
                () -> assertTrue(rows.next()),
                () -> assertEquals(1, rows.getInt(1)));

        FakeGqlExecutor writing = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 4));
        PreparedStatement write = TestConnections.connection(writing).prepareStatement("DELETE ?");
        write.setInt(1, 1);

        assertAll(
                () -> assertEquals(4, write.executeUpdate()),
                () -> assertThrows(SQLException.class, write::executeQuery));
    }

    @Test
    @DisplayName("rejects null or blank GQL at prepare time")
    void rejectsEmptyGql() throws SQLException {
        Connection open = TestConnections.connection(new FakeGqlExecutor());

        assertAll(
                () -> assertThrows(SQLException.class, () -> open.prepareStatement(null)),
                () -> assertThrows(SQLException.class, () -> open.prepareStatement("  ")));
    }
}
