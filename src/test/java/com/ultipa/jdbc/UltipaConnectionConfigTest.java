package com.ultipa.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaConnectionConfigTest {

    @Test
    @DisplayName("accepts only jdbc:ultipa URLs, case-insensitively on the prefix")
    void acceptsOnlyUltipaUrls() {
        assertAll(
                () -> assertTrue(UltipaConnectionConfig.acceptsUrl("jdbc:ultipa://host:9000/g")),
                () -> assertTrue(UltipaConnectionConfig.acceptsUrl("JDBC:ULTIPA://host:9000/g")),
                () -> assertFalse(UltipaConnectionConfig.acceptsUrl("jdbc:postgresql://host:5432/db")),
                () -> assertFalse(UltipaConnectionConfig.acceptsUrl("jdbc:ultipa:host:9000")),
                () -> assertFalse(UltipaConnectionConfig.acceptsUrl(null)));
    }

    @Test
    @DisplayName("parses host, port and graph from the URL")
    void parsesBasicUrl() throws SQLException {
        UltipaConnectionConfig config =
                UltipaConnectionConfig.parse("jdbc:ultipa://db.example.com:9000/socialGraph", null);

        assertAll(
                () -> assertEquals(List.of("db.example.com:9000"), config.getHosts()),
                () -> assertEquals("socialGraph", config.getGraph()),
                () -> assertNull(config.getUser()),
                () -> assertNull(config.getPassword()));
    }

    @Test
    @DisplayName("parses several comma-separated hosts")
    void parsesMultipleHosts() throws SQLException {
        UltipaConnectionConfig config = UltipaConnectionConfig.parse(
                "jdbc:ultipa://a.example.com:9000,b.example.com:9001/g", null);

        assertEquals(List.of("a.example.com:9000", "b.example.com:9001"), config.getHosts());
    }

    @Test
    @DisplayName("works without a graph in the URL")
    void allowsMissingGraph() throws SQLException {
        assertNull(UltipaConnectionConfig.parse("jdbc:ultipa://host:9000", null).getGraph());
        assertNull(UltipaConnectionConfig.parse("jdbc:ultipa://host:9000/", null).getGraph());
    }

    @Test
    @DisplayName("requires an explicit port, because no default port is documented")
    void requiresExplicitPort() {
        SQLException failure = assertThrows(SQLException.class,
                () -> UltipaConnectionConfig.parse("jdbc:ultipa://host/graph", null));
        assertTrue(failure.getMessage().contains("explicit port"), failure.getMessage());
    }

    @ParameterizedTest
    @DisplayName("rejects malformed hosts and ports")
    @ValueSource(strings = {
            "jdbc:ultipa://",
            "jdbc:ultipa:///graph",
            "jdbc:ultipa://host:/graph",
            "jdbc:ultipa://host:abc/graph",
            "jdbc:ultipa://host:0/graph",
            "jdbc:ultipa://host:70000/graph",
            "jdbc:ultipa://a:9000,/graph"})
    void rejectsMalformedHosts(String url) {
        assertThrows(SQLException.class, () -> UltipaConnectionConfig.parse(url, null));
    }

    @Test
    @DisplayName("reads credentials from Properties and from the URL")
    void readsCredentials() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "alice");
        info.setProperty("password", "secret");

        UltipaConnectionConfig fromProperties =
                UltipaConnectionConfig.parse("jdbc:ultipa://host:9000/g", info);
        UltipaConnectionConfig fromUrl = UltipaConnectionConfig.parse(
                "jdbc:ultipa://host:9000/g?user=bob&password=hunter2", null);

        assertAll(
                () -> assertEquals("alice", fromProperties.getUser()),
                () -> assertEquals("secret", fromProperties.getPassword()),
                () -> assertEquals("bob", fromUrl.getUser()),
                () -> assertEquals("hunter2", fromUrl.getPassword()));
    }

    @Test
    @DisplayName("accepts username as an alias for user")
    void acceptsUsernameAlias() throws SQLException {
        assertEquals("carol", UltipaConnectionConfig
                .parse("jdbc:ultipa://host:9000/g?username=carol", null).getUser());
    }

    @Test
    @DisplayName("URL properties override the Properties argument")
    void urlOverridesProperties() throws SQLException {
        Properties info = new Properties();
        info.setProperty("user", "fromProperties");
        info.setProperty("timeout", "10");

        UltipaConnectionConfig config = UltipaConnectionConfig.parse(
                "jdbc:ultipa://host:9000/g?user=fromUrl&timeout=42", info);

        assertAll(
                () -> assertEquals("fromUrl", config.getUser()),
                () -> assertEquals(42, config.getTimeoutSeconds()));
    }

    @Test
    @DisplayName("the graph property overrides the graph in the URL path")
    void graphPropertyOverridesPath() throws SQLException {
        assertEquals("chosen", UltipaConnectionConfig
                .parse("jdbc:ultipa://host:9000/fromPath?graph=chosen", null).getGraph());
    }

    @Test
    @DisplayName("percent-decodes the graph name and property values")
    void percentDecodes() throws SQLException {
        UltipaConnectionConfig config = UltipaConnectionConfig.parse(
                "jdbc:ultipa://host:9000/my%20graph?password=p%40ss%26word", null);

        assertAll(
                () -> assertEquals("my graph", config.getGraph()),
                () -> assertEquals("p@ss&word", config.getPassword()));
    }

    @Test
    @DisplayName("parses boolean properties in their accepted spellings")
    void parsesBooleans() throws SQLException {
        assertAll(
                () -> assertTrue(UltipaConnectionConfig
                        .parse("jdbc:ultipa://h:1/g?readOnly=true", null).isReadOnly()),
                () -> assertTrue(UltipaConnectionConfig
                        .parse("jdbc:ultipa://h:1/g?readOnly=YES", null).isReadOnly()),
                () -> assertTrue(UltipaConnectionConfig
                        .parse("jdbc:ultipa://h:1/g?readOnly=1", null).isReadOnly()),
                () -> assertFalse(UltipaConnectionConfig
                        .parse("jdbc:ultipa://h:1/g?readOnly=off", null).isReadOnly()),
                () -> assertFalse(UltipaConnectionConfig
                        .parse("jdbc:ultipa://h:1/g", null).isReadOnly()));
    }

    @Test
    @DisplayName("rejects invalid property values with a message naming the property")
    void rejectsInvalidPropertyValues() {
        SQLException booleanFailure = assertThrows(SQLException.class, () -> UltipaConnectionConfig
                .parse("jdbc:ultipa://h:1/g?readOnly=perhaps", null));
        SQLException numberFailure = assertThrows(SQLException.class, () -> UltipaConnectionConfig
                .parse("jdbc:ultipa://h:1/g?timeout=soon", null));
        SQLException rangeFailure = assertThrows(SQLException.class, () -> UltipaConnectionConfig
                .parse("jdbc:ultipa://h:1/g?poolSize=0", null));
        SQLException missingValue = assertThrows(SQLException.class, () -> UltipaConnectionConfig
                .parse("jdbc:ultipa://h:1/g?timeout", null));

        assertAll(
                () -> assertTrue(booleanFailure.getMessage().contains("readOnly")),
                () -> assertTrue(numberFailure.getMessage().contains("timeout")),
                () -> assertTrue(rangeFailure.getMessage().contains("poolSize")),
                () -> assertTrue(missingValue.getMessage().contains("name=value")));
    }

    @Test
    @DisplayName("never puts the password in toString")
    void neverLeaksThePassword() throws SQLException {
        UltipaConnectionConfig config = UltipaConnectionConfig
                .parse("jdbc:ultipa://host:9000/g?user=alice&password=TopSecret123", null);

        String description = config.toString();
        assertAll(
                () -> assertFalse(description.contains("TopSecret123"), description),
                () -> assertTrue(description.contains("<redacted>"), description),
                () -> assertTrue(description.contains("alice"), description));
    }

    @Test
    @DisplayName("builds an SDK configuration carrying hosts, graph and credentials")
    void buildsSdkConfig() throws SQLException {
        UltipaConnectionConfig config = UltipaConnectionConfig.parse(
                "jdbc:ultipa://h1:9000,h2:9000/socialGraph?user=alice&password=pw&timeout=30", null);

        com.gqldb.GqldbConfig sdkConfig = config.toSdkConfig();

        assertAll(
                () -> assertEquals(List.of("h1:9000", "h2:9000"), sdkConfig.getHosts()),
                () -> assertEquals("socialGraph", sdkConfig.getDefaultGraph()),
                () -> assertEquals("alice", sdkConfig.getUsername()),
                () -> assertEquals(30, sdkConfig.getTimeout()));
    }

    @Test
    @DisplayName("describes every supported property, marking user and password required")
    void describesProperties() {
        java.sql.DriverPropertyInfo[] properties = UltipaConnectionConfig.describeProperties();

        assertTrue(properties.length >= 14);
        for (java.sql.DriverPropertyInfo property : properties) {
            assertAll(
                    () -> assertFalse(property.name.isBlank()),
                    () -> assertFalse(property.description == null || property.description.isBlank(),
                            "property " + property.name + " has no description"));
        }
        assertTrue(java.util.Arrays.stream(properties)
                .anyMatch(property -> property.name.equals("password") && property.required));
    }
}
