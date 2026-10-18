package com.ultipa.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against a real Ultipa GQLDB server.
 *
 * <p>These are <strong>skipped unless configured</strong>, via JUnit assumptions, so the ordinary
 * build does not pretend to have verified them. Credentials are never hardcoded; they come from
 * the environment:
 *
 * <pre>
 * export ULTIPA_JDBC_URL='jdbc:ultipa://host:port/graph'
 * export ULTIPA_JDBC_USER='username'
 * export ULTIPA_JDBC_PASSWORD='password'
 * mvn test
 * </pre>
 *
 * <p>Every test here is read-only. None creates, alters or deletes data, so pointing them at an
 * existing graph is safe.
 */
class UltipaIntegrationTest {

    private static final String URL = System.getenv("ULTIPA_JDBC_URL");
    private static final String USER = System.getenv("ULTIPA_JDBC_USER");
    private static final String PASSWORD = System.getenv("ULTIPA_JDBC_PASSWORD");

    @BeforeAll
    static void requireAServer() {
        assumeTrue(URL != null && !URL.isBlank(),
                "ULTIPA_JDBC_URL is not set, so the integration tests are skipped and remain "
                        + "unverified");
    }

    private Connection open() throws SQLException {
        Properties info = new Properties();
        if (USER != null) {
            info.setProperty("user", USER);
        }
        if (PASSWORD != null) {
            info.setProperty("password", PASSWORD);
        }
        return DriverManager.getConnection(URL, info);
    }

    @Test
    @DisplayName("connects through DriverManager and reports a live, valid connection")
    void connects() throws SQLException {
        try (Connection connection = open()) {
            assertAll(
                    () -> assertFalse(connection.isClosed()),
                    () -> assertTrue(connection.isValid(5)),
                    () -> assertNotNull(connection.getCatalog()));
        }
    }

    @Test
    @DisplayName("executes a native GQL read and iterates the result set")
    void executesGql() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("RETURN 1 AS one, 'two' AS two")) {

            assertTrue(rows.next());
            assertAll(
                    () -> assertEquals(1, rows.getInt("one")),
                    () -> assertEquals("two", rows.getString("two")),
                    () -> assertEquals(2, rows.getMetaData().getColumnCount()));
            assertFalse(rows.next());
        }
    }

    @Test
    @DisplayName("binds a parameter server side through a PreparedStatement")
    void bindsParameters() throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("RETURN ? AS bound")) {

            statement.setString(1, "value with ' quote");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("value with ' quote", rows.getString("bound"));
            }
        }
    }

    @Test
    @DisplayName("reports the server's product version through DatabaseMetaData")
    void reportsServerMetaData() throws SQLException {
        try (Connection connection = open()) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertAll(
                    () -> assertEquals("Ultipa GQLDB", metaData.getDatabaseProductName()),
                    () -> assertNotEquals("unknown", metaData.getDatabaseProductVersion(),
                            "a live server should report its version"),
                    () -> assertTrue(metaData.getCatalogs().next(),
                            "at least one graph should be visible"));
        }
    }

    @Test
    @DisplayName("maps a failing statement to a SQLException rather than an SDK exception")
    void mapsErrors() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {

            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, () -> statement.executeQuery("THIS IS NOT GQL"));
            assertNotNull(failure.getSQLState());
        }
    }

    @Test
    @DisplayName("rejects bad credentials with an authorization exception")
    void rejectsBadCredentials() {
        assumeTrue(USER != null, "no user configured, so authentication cannot be exercised");

        Properties info = new Properties();
        info.setProperty("user", USER);
        info.setProperty("password", "definitely-not-the-password-" + System.nanoTime());

        SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class, () -> DriverManager.getConnection(URL, info));
        assertNotNull(failure.getSQLState());
    }

    private static void assertNotEquals(String unexpected, String actual, String message) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual, message);
    }
}
