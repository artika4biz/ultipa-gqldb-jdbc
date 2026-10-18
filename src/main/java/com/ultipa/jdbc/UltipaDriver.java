package com.ultipa.jdbc;

import com.ultipa.jdbc.internal.GqlExecutor;
import com.ultipa.jdbc.internal.SdkGqlExecutor;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JDBC driver for Ultipa GQLDB.
 *
 * <p>The driver registers itself in two ways: through the JDBC 4 service-provider mechanism, via
 * {@code META-INF/services/java.sql.Driver}, which {@link DriverManager} discovers automatically
 * on the classpath; and through a static initialiser, so that an explicit
 * {@code Class.forName("com.ultipa.jdbc.UltipaDriver")} also works.
 *
 * <p>URL form:
 *
 * <pre>
 * jdbc:ultipa://host:port[,host2:port2...][/graph][?property=value&amp;...]
 * </pre>
 *
 * <p>A minimal connection:
 *
 * <pre>{@code
 * try (Connection connection = DriverManager.getConnection(
 *         "jdbc:ultipa://localhost:9000/myGraph", "username", "password");
 *      Statement statement = connection.createStatement();
 *      ResultSet rows = statement.executeQuery("MATCH (n) RETURN n LIMIT 10")) {
 *     while (rows.next()) {
 *         System.out.println(rows.getObject(1));
 *     }
 * }
 * }</pre>
 *
 * <p>The port is mandatory: the Ultipa GQLDB documentation defines no default port, and this
 * driver does not guess one. See {@link UltipaConnectionConfig} for the supported properties.
 *
 * <p>{@link #jdbcCompliant()} returns {@code false}, deliberately: the query language is GQL,
 * not SQL, so the driver does not meet the JDBC compliance definition, which requires full
 * entry-level SQL-92 support. Everything it does implement follows the JDBC contracts.
 */
public final class UltipaDriver implements Driver {

    /** The driver's major version. */
    public static final int MAJOR_VERSION = 0;

    /** The driver's minor version. */
    public static final int MINOR_VERSION = 1;

    /** The name this driver reports through {@link java.sql.DatabaseMetaData#getDriverName()}. */
    public static final String DRIVER_NAME = "Ultipa GQLDB JDBC Driver";

    /** The logger name used by every class in this driver. */
    public static final String LOGGER_NAME = "com.ultipa.jdbc";

    private static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);

    static {
        try {
            DriverManager.registerDriver(new UltipaDriver());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Could not register the Ultipa GQLDB JDBC driver", e);
        }
    }

    /** Creates a driver instance. {@link DriverManager} does this for you. */
    public UltipaDriver() {
        // Public no-argument constructor, required by the service-provider mechanism.
    }

    /**
     * Returns the driver version as {@code major.minor}.
     *
     * @return the version string
     */
    public static String version() {
        return MAJOR_VERSION + "." + MINOR_VERSION;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        if (url == null) {
            throw UltipaExceptionMapper.invalidArgument("JDBC URL must not be null");
        }
        return UltipaConnectionConfig.acceptsUrl(url);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code null} when the URL is not an Ultipa URL, as {@link Driver} requires, so
     * that {@link DriverManager} can try the next driver. A URL this driver does accept but
     * cannot parse raises a {@link SQLException} rather than being passed over silently.
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!UltipaConnectionConfig.acceptsUrl(url)) {
            return null;
        }

        UltipaConnectionConfig config = UltipaConnectionConfig.parse(url, info);
        if (LOGGER.isLoggable(Level.FINE)) {
            // config.toString() redacts the password.
            LOGGER.fine("Opening Ultipa GQLDB connection: " + config);
        }

        GqlExecutor executor = SdkGqlExecutor.open(config);
        try {
            return new UltipaConnection(executor, config);
        } catch (RuntimeException e) {
            executor.close();
            throw e;
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return UltipaConnectionConfig.describeProperties();
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code false}. JDBC compliance requires full entry-level SQL-92 support; this
     * driver speaks GQL.
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return LOGGER;
    }
}
