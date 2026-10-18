package com.ultipa.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaConnectionTest {

    private FakeGqlExecutor writingExecutor() {
        return new FakeGqlExecutor().respondWith(
                FakeGqlExecutor.response(List.of(), List.of(), 1));
    }

    @Test
    @DisplayName("starts open, in auto-commit mode, with the URL's graph as the catalog")
    void startsInAutoCommit() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertAll(
                () -> assertFalse(connection.isClosed()),
                () -> assertTrue(connection.getAutoCommit()),
                () -> assertFalse(connection.isReadOnly()),
                () -> assertEquals("socialGraph", connection.getCatalog()),
                () -> assertNull(connection.getSchema()),
                () -> assertEquals(Connection.TRANSACTION_NONE, connection.getTransactionIsolation()),
                () -> assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, connection.getHoldability()));
    }

    @Test
    @DisplayName("in auto-commit mode no transaction is started")
    void autoCommitSendsNoTransaction() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        connection.createStatement().executeUpdate("DELETE n");

        assertAll(
                () -> assertEquals(0, executor.beginCount()),
                () -> assertEquals(0L, executor.lastExecution().transactionId()));
    }

    @Test
    @DisplayName("setAutoCommit(false) starts a transaction lazily, on the first statement")
    void startsTransactionLazily() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        connection.setAutoCommit(false);
        assertEquals(0, executor.beginCount(), "nothing is sent until a statement runs");

        connection.createStatement().executeUpdate("DELETE n");
        assertAll(
                () -> assertEquals(1, executor.beginCount()),
                () -> assertEquals(100L, executor.lastExecution().transactionId()));
    }

    @Test
    @DisplayName("statements in one transaction share its id until commit")
    void reusesTheTransactionUntilCommit() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();

        statement.executeUpdate("DELETE a");
        statement.executeUpdate("DELETE b");
        connection.commit();
        statement.executeUpdate("DELETE c");

        assertAll(
                () -> assertEquals(2, executor.beginCount(), "a second transaction after commit"),
                () -> assertEquals(100L, executor.executions().get(0).transactionId()),
                () -> assertEquals(100L, executor.executions().get(1).transactionId()),
                () -> assertEquals(List.of(100L), executor.committedTransactions()),
                () -> assertEquals(101L, executor.executions().get(2).transactionId()));
    }

    @Test
    @DisplayName("rollback rolls the active transaction back")
    void rollsBack() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("DELETE n");

        connection.rollback();

        assertAll(
                () -> assertEquals(List.of(100L), executor.rolledBackTransactions()),
                () -> assertEquals(List.of(), executor.committedTransactions()));
    }

    @Test
    @DisplayName("commit and rollback do nothing when no statement has run")
    void commitWithoutWorkIsHarmless() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);
        connection.setAutoCommit(false);

        connection.commit();
        connection.rollback();

        assertAll(
                () -> assertEquals(List.of(), executor.committedTransactions()),
                () -> assertEquals(List.of(), executor.rolledBackTransactions()));
    }

    @Test
    @DisplayName("turning auto-commit back on commits the work in progress")
    void reEnablingAutoCommitCommits() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("DELETE n");

        connection.setAutoCommit(true);

        assertAll(
                () -> assertEquals(List.of(100L), executor.committedTransactions()),
                () -> assertTrue(connection.getAutoCommit()));
    }

    @Test
    @DisplayName("commit and rollback are refused in auto-commit mode")
    void refusesCommitInAutoCommit() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertAll(
                () -> assertThrows(SQLException.class, connection::commit),
                () -> assertThrows(SQLException.class, connection::rollback));
    }

    @Test
    @DisplayName("closing rolls back an open transaction instead of leaving it to expire")
    void closeRollsBack() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("DELETE n");

        connection.close();

        assertAll(
                () -> assertEquals(List.of(100L), executor.rolledBackTransactions()),
                () -> assertTrue(executor.isClosed()));
    }

    @Test
    @DisplayName("closing closes the open statements and the session, and is idempotent")
    void closesEverything() throws SQLException {
        FakeGqlExecutor executor = new FakeGqlExecutor().respondWith(
                FakeGqlExecutor.response(List.of("n"), List.of(List.of(1)), 0));
        Connection connection = TestConnections.connection(executor);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("MATCH (n) RETURN n");

        connection.close();
        connection.close();

        assertAll(
                () -> assertTrue(connection.isClosed()),
                () -> assertTrue(statement.isClosed()),
                () -> assertTrue(rows.isClosed()),
                () -> assertTrue(executor.isClosed()),
                () -> assertThrows(SQLException.class, connection::createStatement),
                () -> assertThrows(SQLException.class, connection::getCatalog),
                () -> assertThrows(SQLException.class, connection::getMetaData));
    }

    @Test
    @DisplayName("isValid pings, returns false when closed, and rejects a negative timeout")
    void reportsValidity() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        assertTrue(connection.isValid(1));

        executor.alive(false);
        assertFalse(connection.isValid(1), "an unreachable server yields false, not an exception");

        assertThrows(SQLException.class, () -> connection.isValid(-1));

        connection.close();
        assertFalse(connection.isValid(0));
    }

    @Test
    @DisplayName("setCatalog selects a graph; getCatalog reports the one in effect")
    void selectsGraph() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        connection.setCatalog("otherGraph");

        assertAll(
                () -> assertEquals("otherGraph", executor.lastUsedGraph()),
                () -> assertEquals("otherGraph", connection.getCatalog()),
                () -> assertThrows(SQLException.class, () -> connection.setCatalog(null)),
                () -> assertThrows(SQLException.class, () -> connection.setCatalog("  ")));
    }

    @Test
    @DisplayName("the graph cannot be changed while a transaction is active")
    void refusesGraphChangeInTransaction() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("DELETE n");

        SQLException failure =
                assertThrows(SQLException.class, () -> connection.setCatalog("otherGraph"));
        assertTrue(failure.getMessage().contains("transaction is active"), failure.getMessage());
    }

    @Test
    @DisplayName("a read-only connection marks each statement read-only on the server")
    void propagatesReadOnly() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        connection.setReadOnly(true);
        connection.createStatement().executeUpdate("DELETE n");

        assertAll(
                () -> assertTrue(connection.isReadOnly()),
                () -> assertTrue(executor.lastExecution().readOnly()));
    }

    @Test
    @DisplayName("the readOnly connection property opens the connection read-only")
    void honoursReadOnlyProperty() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor,
                "jdbc:ultipa://localhost:9000/socialGraph?readOnly=true");

        connection.createStatement().executeUpdate("MATCH (n) RETURN n");

        assertAll(
                () -> assertTrue(connection.isReadOnly()),
                () -> assertTrue(executor.lastExecution().readOnly()));
    }

    @Test
    @DisplayName("read-only cannot be changed while a transaction is active")
    void refusesReadOnlyChangeInTransaction() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("DELETE n");

        assertThrows(SQLException.class, () -> connection.setReadOnly(true));
    }

    @Test
    @DisplayName("passes the maxPathResults property through to each statement")
    void propagatesMaxPathResults() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor,
                "jdbc:ultipa://localhost:9000/socialGraph?maxPathResults=500");

        connection.createStatement().executeUpdate("MATCH p = () -[]-> () RETURN p");

        assertEquals(500L, executor.lastExecution().maxPathResults());
    }

    @Test
    @DisplayName("only TRANSACTION_NONE is accepted, since the protocol has no isolation levels")
    void acceptsOnlyTransactionNone() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        connection.setTransactionIsolation(Connection.TRANSACTION_NONE);
        assertAll(
                () -> assertEquals(Connection.TRANSACTION_NONE, connection.getTransactionIsolation()),
                () -> assertThrows(SQLFeatureNotSupportedException.class, () -> connection
                        .setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)),
                () -> assertThrows(SQLFeatureNotSupportedException.class, () -> connection
                        .setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)));
    }

    @Test
    @DisplayName("nativeSQL returns the statement unchanged, because nothing is translated")
    void nativeSqlIsAPassThrough() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());
        String gql = "MATCH (n) RETURN n";

        assertEquals(gql, connection.nativeSQL(gql));
    }

    @Test
    @DisplayName("accepts the result set options it supports and refuses the rest")
    void validatesResultSetOptions() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertNotNull(connection.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertNotNull(connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT));

        assertAll(
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                                ResultSet.CONCUR_READ_ONLY)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_UPDATABLE)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT)));
    }

    @Test
    @DisplayName("refuses the features the driver does not implement, each with a reason")
    void refusesUnsupportedFeatures() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertAll(
                () -> assertThrows(SQLFeatureNotSupportedException.class, connection::setSavepoint),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.setSavepoint("s")),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.prepareCall("CALL x()")),
                () -> assertThrows(SQLFeatureNotSupportedException.class, connection::createClob),
                () -> assertThrows(SQLFeatureNotSupportedException.class, connection::createBlob),
                () -> assertThrows(SQLFeatureNotSupportedException.class, connection::createNClob),
                () -> assertThrows(SQLFeatureNotSupportedException.class, connection::createSQLXML),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.createArrayOf("int", new Object[0])),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.createStruct("t", new Object[0])),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.setSchema("public")),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.setNetworkTimeout(Runnable::run, 1000)),
                () -> assertThrows(SQLFeatureNotSupportedException.class,
                        () -> connection.prepareStatement("INSERT (:P)",
                                Statement.RETURN_GENERATED_KEYS)),
                () -> assertThrows(SQLClientInfoException.class,
                        () -> connection.setClientInfo("ApplicationName", "test")));
    }

    @Test
    @DisplayName("reports no client info, as it supports none")
    void reportsNoClientInfo() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertAll(
                () -> assertTrue(connection.getClientInfo().isEmpty()),
                () -> assertNull(connection.getClientInfo("ApplicationName")),
                () -> assertTrue(connection.getTypeMap().isEmpty()));
    }

    @Test
    @DisplayName("abort closes the connection")
    void abortCloses() throws SQLException {
        FakeGqlExecutor executor = writingExecutor();
        Connection connection = TestConnections.connection(executor);

        connection.abort(Runnable::run);

        assertAll(
                () -> assertTrue(connection.isClosed()),
                () -> assertTrue(executor.isClosed()),
                () -> assertThrows(SQLException.class, () -> connection.abort(null)));
    }

    @Test
    @DisplayName("unwraps to its own type and refuses anything else")
    void unwraps() throws SQLException {
        Connection connection = TestConnections.connection(writingExecutor());

        assertAll(
                () -> assertTrue(connection.isWrapperFor(UltipaConnection.class)),
                () -> assertEquals(connection, connection.unwrap(UltipaConnection.class)),
                () -> assertThrows(SQLException.class, () -> connection.unwrap(String.class)));
    }
}
