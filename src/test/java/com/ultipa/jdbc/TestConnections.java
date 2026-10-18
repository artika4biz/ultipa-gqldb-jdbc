package com.ultipa.jdbc;

import java.sql.SQLException;
import java.util.Properties;

/** Builds connections over a {@link FakeGqlExecutor} for the unit tests. */
final class TestConnections {

    private TestConnections() {
    }

    /** The URL the tests use unless they need something specific. */
    static final String URL = "jdbc:ultipa://localhost:9000/socialGraph";

    static UltipaConnectionConfig config() throws SQLException {
        return config(URL, new Properties());
    }

    static UltipaConnectionConfig config(String url, Properties info) throws SQLException {
        return UltipaConnectionConfig.parse(url, info);
    }

    static UltipaConnection connection(FakeGqlExecutor executor) throws SQLException {
        return new UltipaConnection(executor, config());
    }

    static UltipaConnection connection(FakeGqlExecutor executor, String url) throws SQLException {
        return new UltipaConnection(executor, config(url, new Properties()));
    }
}
