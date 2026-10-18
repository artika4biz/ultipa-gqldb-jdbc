package com.ultipa.jdbc;

import com.gqldb.DmlStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaStatementTest {

    private static final List<String> ONE_COLUMN = List.of("n");

    @Test
    @DisplayName("sends the GQL text to the server verbatim")
    void sendsGqlVerbatim() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        String gql = "MATCH (n:Person) WHERE n.name = 'O''Brien' RETURN n";

        TestConnections.connection(executor).createStatement().executeQuery(gql);

        assertEquals(gql, executor.lastExecution().gql());
    }

    @Test
    @DisplayName("executeQuery returns a result set for a read")
    void executeQueryReturnsRows() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(
                FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(7)), 0));

        ResultSet rows = TestConnections.connection(executor)
                .createStatement().executeQuery("MATCH (n) RETURN n");

        assertAll(
                () -> assertNotNull(rows),
                () -> assertTrue(rows.next()),
                () -> assertEquals(7, rows.getInt(1)));
    }

    @Test
    @DisplayName("executeQuery fails rather than returning an empty result for a write")
    void executeQueryRejectsWrites() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 3));
        Statement statement = TestConnections.connection(executor).createStatement();

        SQLException failure = assertThrows(SQLException.class,
                () -> statement.executeQuery("INSERT (:Person {name: 'Alice'})"));
        assertTrue(failure.getMessage().contains("executeQuery"), failure.getMessage());
    }

    @Test
    @DisplayName("executeUpdate reports the affected-row count for a write")
    void executeUpdateReportsCount() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 5));
        Statement statement = TestConnections.connection(executor).createStatement();

        assertAll(
                () -> assertEquals(5, statement.executeUpdate("DELETE n")),
                () -> assertEquals(5L, statement.getLargeUpdateCount()),
                () -> assertNull(statement.getResultSet()));
    }

    @Test
    @DisplayName("executeUpdate fails rather than discarding a result set")
    void executeUpdateRejectsReads() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();

        SQLException failure = assertThrows(SQLException.class,
                () -> statement.executeUpdate("MATCH (n) RETURN n"));
        assertTrue(failure.getMessage().contains("executeUpdate"), failure.getMessage());
    }

    @Test
    @DisplayName("execute distinguishes a read from a write, and sets the update count")
    void executeReportsWhichOutcome() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(gql ->
                gql.startsWith("MATCH")
                        ? FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0)
                        : FakeGqlExecutor.response(List.of(), List.of(), 2));
        Statement statement = TestConnections.connection(executor).createStatement();

        assertTrue(statement.execute("MATCH (n) RETURN n"));
        assertAll(
                () -> assertNotNull(statement.getResultSet()),
                () -> assertEquals(-1, statement.getUpdateCount(),
                        "JDBC requires -1 while the current result is a result set"));

        assertFalse(statement.execute("DELETE n"));
        assertAll(
                () -> assertNull(statement.getResultSet()),
                () -> assertEquals(2, statement.getUpdateCount()));
    }

    @Test
    @DisplayName("exposes the server's DML breakdown as a graph-aware extension")
    void exposesDmlStats() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(FakeGqlExecutor.response(
                List.of(), List.of(), 7, List.of(), new DmlStats(3, 4, 0, 0, 0, 0)));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.executeUpdate("INSERT ...");

        UltipaStatement ultipa = statement.unwrap(UltipaStatement.class);
        assertAll(
                () -> assertEquals(3, ultipa.getDmlStats().insertedNodes()),
                () -> assertEquals(4, ultipa.getDmlStats().insertedEdges()),
                () -> assertEquals(7, ultipa.getDmlStats().total()),
                () -> assertTrue(ultipa.getDmlStats().isReported()),
                () -> assertEquals(7L, ultipa.getLastRowsAffected()));
    }

    @Test
    @DisplayName("reports the affected count after a write that also returned rows")
    void reportsCountForWriteWithReturn() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(
                FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 1));
        Statement statement = TestConnections.connection(executor).createStatement();

        assertTrue(statement.execute("INSERT (n:Person) RETURN n"));
        assertAll(
                () -> assertEquals(-1, statement.getUpdateCount()),
                () -> assertEquals(1L, statement.unwrap(UltipaStatement.class).getLastRowsAffected()));
    }

    @Test
    @DisplayName("turns server warnings into SQLWarnings on the statement")
    void collectsServerWarnings() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(FakeGqlExecutor.response(
                ONE_COLUMN, List.of(List.of(1)), 0, List.of("index not used", "result truncated"),
                null));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.executeQuery("MATCH (n) RETURN n");

        SQLWarning warning = statement.getWarnings();
        assertAll(
                () -> assertNotNull(warning),
                () -> assertEquals("index not used", warning.getMessage()),
                () -> assertEquals("result truncated", warning.getNextWarning().getMessage()));

        statement.clearWarnings();
        assertNull(statement.getWarnings());
    }

    @Test
    @DisplayName("passes the query timeout to the server and rejects a negative one")
    void appliesQueryTimeout() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 0));
        Statement statement = TestConnections.connection(executor).createStatement();

        statement.setQueryTimeout(42);
        statement.executeUpdate("DELETE n");

        assertAll(
                () -> assertEquals(42, statement.getQueryTimeout()),
                () -> assertEquals(42, executor.lastExecution().timeout()),
                () -> assertThrows(SQLException.class, () -> statement.setQueryTimeout(-1)));
    }

    @Test
    @DisplayName("takes its default query timeout from the connection's timeout property")
    void defaultsQueryTimeoutFromConnection() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 0));
        Connection connection = TestConnections.connection(executor,
                "jdbc:ultipa://localhost:9000/socialGraph?timeout=25");

        Statement statement = connection.createStatement();
        statement.executeUpdate("DELETE n");

        assertAll(
                () -> assertEquals(25, statement.getQueryTimeout()),
                () -> assertEquals(25, executor.lastExecution().timeout()));
    }

    @Test
    @DisplayName("runs a batch statement by statement and returns one count each")
    void runsBatches() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 1));
        Statement statement = TestConnections.connection(executor).createStatement();

        statement.addBatch("INSERT (:Person {name: 'a'})");
        statement.addBatch("INSERT (:Person {name: 'b'})");
        int[] counts = statement.executeBatch();

        assertAll(
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {1, 1}, counts),
                () -> assertEquals(2, executor.executions().size()),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                        new int[0], statement.executeBatch(), "the batch is cleared after running"));
    }

    @Test
    @DisplayName("reports a batch entry that returns rows as a batch failure")
    void rejectsBatchEntryReturningRows() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.addBatch("MATCH (n) RETURN n");

        BatchUpdateException failure =
                assertThrows(BatchUpdateException.class, statement::executeBatch);
        assertAll(
                () -> assertTrue(failure.getMessage().contains("Batch entry 1")),
                () -> assertEquals(Statement.EXECUTE_FAILED, failure.getUpdateCounts()[0]));
    }

    @Test
    @DisplayName("clearBatch discards the queued statements")
    void clearsBatch() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(List.of(), List.of(), 1));
        Statement statement = TestConnections.connection(executor).createStatement();

        statement.addBatch("DELETE n");
        statement.clearBatch();

        assertAll(
                () -> assertEquals(0, statement.executeBatch().length),
                () -> assertEquals(0, executor.executions().size()));
    }

    @Test
    @DisplayName("getMoreResults reports no further results and closes the current one")
    void hasNoFurtherResults() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        ResultSet rows = statement.executeQuery("MATCH (n) RETURN n");

        assertAll(
                () -> assertFalse(statement.getMoreResults()),
                () -> assertTrue(rows.isClosed()),
                () -> assertEquals(-1, statement.getUpdateCount()));
    }

    @Test
    @DisplayName("rejects null or blank GQL")
    void rejectsEmptyGql() throws SQLException {
        Statement statement = TestConnections.connection(new FakeGqlExecutor()).createStatement();

        assertAll(
                () -> assertThrows(SQLException.class, () -> statement.execute(null)),
                () -> assertThrows(SQLException.class, () -> statement.execute("   ")),
                () -> assertThrows(SQLException.class, () -> statement.addBatch(null)));
    }

    @Test
    @DisplayName("refuses the features the driver does not implement, with a reason")
    void refusesUnsupportedFeatures() throws SQLException {
        Statement statement = TestConnections.connection(new FakeGqlExecutor()).createStatement();

        assertAll(
                () -> assertThrows(SQLFeatureNotSupportedException.class, statement::cancel),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        statement::getGeneratedKeys),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setCursorName("c")),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setEscapeProcessing(true)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setMaxFieldSize(100)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.setFetchDirection(ResultSet.FETCH_REVERSE)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> statement.execute("MATCH (n) RETURN n", Statement.RETURN_GENERATED_KEYS)));

        // Escape processing off is the driver's only mode, so asking for it is accepted.
        statement.setEscapeProcessing(false);
        statement.setMaxFieldSize(0);
    }

    @Test
    @DisplayName("closing is idempotent, closes the result set, and blocks further use")
    void closesCleanly() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        ResultSet rows = statement.executeQuery("MATCH (n) RETURN n");

        statement.close();
        statement.close();

        assertAll(
                () -> assertTrue(statement.isClosed()),
                () -> assertTrue(rows.isClosed()),
                () -> assertThrows(SQLException.class, () -> statement.execute("MATCH (n) RETURN n")),
                () -> assertThrows(SQLException.class, statement::getResultSet),
                () -> assertThrows(SQLException.class, statement::getWarnings));
    }

    @Test
    @DisplayName("closeOnCompletion closes the statement when its result set closes")
    void honoursCloseOnCompletion() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.closeOnCompletion();

        ResultSet rows = statement.executeQuery("MATCH (n) RETURN n");
        assertFalse(statement.isClosed());

        rows.close();
        assertTrue(statement.isClosed());
    }

    @Test
    @DisplayName("re-executing closes the previous result set without closing the statement")
    void reExecutionKeepsStatementOpen() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor()
                .respondWith(FakeGqlExecutor.response(ONE_COLUMN, List.of(List.of(1)), 0));
        Statement statement = TestConnections.connection(executor).createStatement();
        statement.closeOnCompletion();

        ResultSet first = statement.executeQuery("MATCH (n) RETURN n");
        ResultSet second = statement.executeQuery("MATCH (n) RETURN n");

        assertAll(
                () -> assertTrue(first.isClosed()),
                () -> assertFalse(second.isClosed()),
                () -> assertFalse(statement.isClosed()));
    }
}
