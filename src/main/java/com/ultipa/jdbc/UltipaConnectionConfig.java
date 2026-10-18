package com.ultipa.jdbc;

import com.gqldb.GqldbConfig;

import javax.net.ssl.SSLContext;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A parsed and validated JDBC connection configuration.
 *
 * <p>URL form:
 *
 * <pre>
 * jdbc:ultipa://host:port[,host2:port2...][/graph][?property=value&amp;...]
 * </pre>
 *
 * <p>Every {@code host:port} pair is passed to the SDK, which handles multi-host clusters.
 * The path segment selects the default graph and may be omitted, in which case a graph must be
 * chosen later with {@link java.sql.Connection#setCatalog(String)} or by a {@code USE GRAPH}
 * statement.
 *
 * <p><strong>Ports are required.</strong> The Ultipa documentation does not define a default
 * port for GQLDB, so the driver asks for one rather than guessing.
 *
 * <p>Where a property appears both in the URL query string and in the {@link Properties} passed
 * to {@link java.sql.DriverManager}, the URL wins, being the more specific source. {@code user}
 * and {@code password} may come from either.
 *
 * <p>This class never puts the password in {@link #toString()}.
 */
public final class UltipaConnectionConfig {

    /** The prefix every URL this driver accepts must start with. */
    public static final String URL_PREFIX = "jdbc:ultipa://";

    // Property names.
    static final String PROP_USER = "user";
    static final String PROP_PASSWORD = "password";
    static final String PROP_GRAPH = "graph";
    static final String PROP_TIMEOUT = "timeout";
    static final String PROP_MAX_RECV_SIZE = "maxRecvSize";
    static final String PROP_POOL_SIZE = "poolSize";
    static final String PROP_RETRY_COUNT = "retryCount";
    static final String PROP_RETRY_DELAY_MILLIS = "retryDelayMillis";
    static final String PROP_HEALTH_CHECK_INTERVAL_MILLIS = "healthCheckIntervalMillis";
    static final String PROP_SSL = "ssl";
    static final String PROP_DISABLE_USE_GRAPH = "disableUseGraph";
    static final String PROP_MAX_PATH_RESULTS = "maxPathResults";
    static final String PROP_READ_ONLY = "readOnly";
    static final String PROP_TRANSACTION_TIMEOUT = "transactionTimeout";

    /** Accepted alias for {@link #PROP_USER}, for callers used to other drivers. */
    private static final String PROP_USERNAME_ALIAS = "username";

    private static final int UNSET_INT = -1;

    private final List<String> hosts;
    private final String graph;
    private final String user;
    private final String password;
    private final int timeoutSeconds;
    private final int maxRecvSize;
    private final int poolSize;
    private final int retryCount;
    private final long retryDelayMillis;
    private final long healthCheckIntervalMillis;
    private final boolean ssl;
    private final boolean disableUseGraph;
    private final long maxPathResults;
    private final boolean readOnly;
    private final int transactionTimeoutSeconds;

    private UltipaConnectionConfig(List<String> hosts, String graph, String user, String password,
                                   int timeoutSeconds, int maxRecvSize, int poolSize,
                                   int retryCount, long retryDelayMillis,
                                   long healthCheckIntervalMillis, boolean ssl,
                                   boolean disableUseGraph, long maxPathResults,
                                   boolean readOnly, int transactionTimeoutSeconds) {
        this.hosts = List.copyOf(hosts);
        this.graph = graph;
        this.user = user;
        this.password = password;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRecvSize = maxRecvSize;
        this.poolSize = poolSize;
        this.retryCount = retryCount;
        this.retryDelayMillis = retryDelayMillis;
        this.healthCheckIntervalMillis = healthCheckIntervalMillis;
        this.ssl = ssl;
        this.disableUseGraph = disableUseGraph;
        this.maxPathResults = maxPathResults;
        this.readOnly = readOnly;
        this.transactionTimeoutSeconds = transactionTimeoutSeconds;
    }

    /**
     * Reports whether a URL is one this driver handles. Does not validate the rest of the URL.
     *
     * @param url the URL to test, may be {@code null}
     * @return {@code true} if the URL starts with {@value #URL_PREFIX}
     */
    public static boolean acceptsUrl(String url) {
        return url != null && url.regionMatches(true, 0, URL_PREFIX, 0, URL_PREFIX.length());
    }

    /**
     * Parses and validates a JDBC URL together with the caller's properties.
     *
     * @param url  the JDBC URL
     * @param info the properties passed to {@link java.sql.DriverManager}, may be {@code null}
     * @return the parsed configuration
     * @throws SQLException if the URL is not an Ultipa URL, is malformed, or a property value is
     *                      not valid
     */
    public static UltipaConnectionConfig parse(String url, Properties info) throws SQLException {
        if (!acceptsUrl(url)) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Not an Ultipa GQLDB JDBC URL; expected one starting with \"" + URL_PREFIX + "\"");
        }

        String remainder = url.substring(URL_PREFIX.length());

        String queryString = null;
        int questionMark = remainder.indexOf('?');
        if (questionMark >= 0) {
            queryString = remainder.substring(questionMark + 1);
            remainder = remainder.substring(0, questionMark);
        }

        String pathGraph = null;
        int slash = remainder.indexOf('/');
        if (slash >= 0) {
            pathGraph = decode(remainder.substring(slash + 1), "graph name");
            remainder = remainder.substring(0, slash);
        }

        List<String> hosts = parseHosts(remainder);

        // Properties first, then URL query parameters on top: the URL is more specific.
        Map<String, String> properties = new LinkedHashMap<>();
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                properties.put(name, info.getProperty(name));
            }
        }
        properties.putAll(parseQueryString(queryString));

        String user = firstNonNull(properties.get(PROP_USER), properties.get(PROP_USERNAME_ALIAS));
        String password = properties.get(PROP_PASSWORD);

        String graph = properties.get(PROP_GRAPH);
        if (graph == null || graph.isBlank()) {
            graph = pathGraph;
        }
        if (graph != null && graph.isBlank()) {
            graph = null;
        }

        return new UltipaConnectionConfig(
                hosts,
                graph,
                user,
                password,
                intProperty(properties, PROP_TIMEOUT, UNSET_INT, 0),
                intProperty(properties, PROP_MAX_RECV_SIZE, UNSET_INT, 1),
                intProperty(properties, PROP_POOL_SIZE, UNSET_INT, 1),
                intProperty(properties, PROP_RETRY_COUNT, UNSET_INT, 0),
                longProperty(properties, PROP_RETRY_DELAY_MILLIS, UNSET_INT, 0),
                longProperty(properties, PROP_HEALTH_CHECK_INTERVAL_MILLIS, UNSET_INT, 0),
                booleanProperty(properties, PROP_SSL, false),
                booleanProperty(properties, PROP_DISABLE_USE_GRAPH, false),
                longProperty(properties, PROP_MAX_PATH_RESULTS, 0, 0),
                booleanProperty(properties, PROP_READ_ONLY, false),
                intProperty(properties, PROP_TRANSACTION_TIMEOUT, 0, 0));
    }

    private static List<String> parseHosts(String hostSection) throws SQLException {
        if (hostSection.isBlank()) {
            throw UltipaExceptionMapper.invalidArgument(
                    "JDBC URL is missing the host section; expected \"" + URL_PREFIX + "host:port/graph\"");
        }
        List<String> hosts = new ArrayList<>();
        for (String candidate : hostSection.split(",", -1)) {
            String host = candidate.trim();
            if (host.isEmpty()) {
                throw UltipaExceptionMapper.invalidArgument(
                        "JDBC URL contains an empty host entry in \"" + hostSection + "\"");
            }
            int colon = host.lastIndexOf(':');
            if (colon <= 0 || colon == host.length() - 1) {
                throw UltipaExceptionMapper.invalidArgument(
                        "JDBC URL host \"" + host + "\" must include an explicit port, as in \"host:port\"; "
                                + "the Ultipa GQLDB documentation defines no default port");
            }
            String portText = host.substring(colon + 1);
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw UltipaExceptionMapper.invalidArgument(
                        "JDBC URL host \"" + host + "\" has a non-numeric port \"" + portText + "\"");
            }
            if (port < 1 || port > 65535) {
                throw UltipaExceptionMapper.invalidArgument(
                        "JDBC URL host \"" + host + "\" has port " + port + ", outside 1-65535");
            }
            hosts.add(host);
        }
        return hosts;
    }

    private static Map<String, String> parseQueryString(String queryString) throws SQLException {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return parsed;
        }
        for (String pair : queryString.split("&", -1)) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals < 0) {
                throw UltipaExceptionMapper.invalidArgument(
                        "JDBC URL property \"" + pair + "\" is missing a value; expected name=value");
            }
            String name = decode(pair.substring(0, equals), "property name");
            String value = decode(pair.substring(equals + 1), "value of property " + name);
            if (name.isBlank()) {
                throw UltipaExceptionMapper.invalidArgument("JDBC URL contains a property with an empty name");
            }
            parsed.put(name, value);
        }
        return parsed;
    }

    private static String decode(String text, String what) throws SQLException {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            throw UltipaExceptionMapper.invalidArgument(
                    "JDBC URL has an invalid percent-encoded " + what);
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private static int intProperty(Map<String, String> properties, String name,
                                   int defaultValue, int minimum) throws SQLException {
        long value = longProperty(properties, name, defaultValue, minimum);
        if (value > Integer.MAX_VALUE) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Connection property " + name + " is too large: " + value);
        }
        return (int) value;
    }

    private static long longProperty(Map<String, String> properties, String name,
                                     long defaultValue, long minimum) throws SQLException {
        String text = properties.get(name);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        long value;
        try {
            value = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Connection property " + name + " must be an integer, but was \"" + text + "\"");
        }
        if (value < minimum) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Connection property " + name + " must be at least " + minimum + ", but was " + value);
        }
        return value;
    }

    private static boolean booleanProperty(Map<String, String> properties, String name,
                                           boolean defaultValue) throws SQLException {
        String text = properties.get(name);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        String normalised = text.trim().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw UltipaExceptionMapper.invalidArgument(
                    "Connection property " + name + " must be true or false, but was \"" + text + "\"");
        };
    }

    /**
     * Builds the SDK configuration this JDBC configuration describes.
     *
     * @return the SDK configuration
     * @throws SQLException if TLS was requested but no default {@link SSLContext} is available,
     *                      or the SDK rejects the configuration
     */
    public GqldbConfig toSdkConfig() throws SQLException {
        GqldbConfig.Builder builder = GqldbConfig.builder().hosts(hosts);
        if (user != null) {
            builder.username(user);
        }
        if (password != null) {
            builder.password(password);
        }
        if (graph != null) {
            builder.defaultGraph(graph);
        }
        if (timeoutSeconds >= 0) {
            builder.timeout(timeoutSeconds);
        }
        if (maxRecvSize >= 0) {
            builder.maxRecvSize(maxRecvSize);
        }
        if (poolSize >= 0) {
            builder.poolSize(poolSize);
        }
        if (retryCount >= 0) {
            builder.retryCount(retryCount);
        }
        if (retryDelayMillis >= 0) {
            builder.retryDelay(java.time.Duration.ofMillis(retryDelayMillis));
        }
        if (healthCheckIntervalMillis >= 0) {
            builder.healthCheckInterval(java.time.Duration.ofMillis(healthCheckIntervalMillis));
        }
        builder.disableUseGraph(disableUseGraph);
        if (ssl) {
            try {
                builder.sslContext(SSLContext.getDefault());
            } catch (java.security.NoSuchAlgorithmException e) {
                throw UltipaExceptionMapper.invalidArgument(
                        "TLS was requested with ssl=true but no default SSLContext is available");
            }
        }

        GqldbConfig config = builder.build();
        try {
            config.validate();
        } catch (RuntimeException e) {
            throw UltipaExceptionMapper.invalidArgument(
                    "The Ultipa SDK rejected the connection configuration: " + e.getMessage());
        }
        return config;
    }

    /**
     * Describes the properties this driver understands, for
     * {@link java.sql.Driver#getPropertyInfo(String, Properties)}.
     *
     * @return one entry per supported property
     */
    public static DriverPropertyInfo[] describeProperties() {
        List<DriverPropertyInfo> properties = new ArrayList<>();
        properties.add(info(PROP_USER, "User name to authenticate with.", true));
        properties.add(info(PROP_PASSWORD, "Password to authenticate with.", true));
        properties.add(info(PROP_GRAPH,
                "Default graph; overrides the graph in the URL path.", false));
        properties.add(info(PROP_TIMEOUT,
                "Default request timeout in seconds. Also the default Statement query timeout.", false));
        properties.add(info(PROP_MAX_RECV_SIZE,
                "Maximum gRPC response size in bytes.", false));
        properties.add(info(PROP_POOL_SIZE,
                "Number of gRPC channels the SDK opens per host.", false));
        properties.add(info(PROP_RETRY_COUNT,
                "How many times the SDK retries a retryable read.", false));
        properties.add(info(PROP_RETRY_DELAY_MILLIS,
                "Delay between SDK retries, in milliseconds.", false));
        properties.add(info(PROP_HEALTH_CHECK_INTERVAL_MILLIS,
                "Interval of the SDK's background health check, in milliseconds.", false));
        DriverPropertyInfo ssl = info(PROP_SSL,
                "Use TLS with the JVM default SSLContext.", false);
        ssl.choices = new String[] {"true", "false"};
        properties.add(ssl);
        DriverPropertyInfo disableUseGraph = info(PROP_DISABLE_USE_GRAPH,
                "Stop the SDK from sending USE GRAPH when selecting a graph.", false);
        disableUseGraph.choices = new String[] {"true", "false"};
        properties.add(disableUseGraph);
        properties.add(info(PROP_MAX_PATH_RESULTS,
                "Maximum number of paths a path query may return; 0 means unlimited.", false));
        DriverPropertyInfo readOnly = info(PROP_READ_ONLY,
                "Open the connection read-only, marking every statement read-only on the server.", false);
        readOnly.choices = new String[] {"true", "false"};
        properties.add(readOnly);
        properties.add(info(PROP_TRANSACTION_TIMEOUT,
                "Timeout in seconds for transactions started by setAutoCommit(false); 0 leaves it to the server.",
                false));
        return properties.toArray(new DriverPropertyInfo[0]);
    }

    private static DriverPropertyInfo info(String name, String description, boolean required) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, null);
        property.description = description;
        property.required = required;
        return property;
    }

    /** Returns the {@code host:port} entries, in URL order. */
    public List<String> getHosts() {
        return hosts;
    }

    /** Returns the default graph, or {@code null} if the URL did not name one. */
    public String getGraph() {
        return graph;
    }

    /** Returns the user name, or {@code null} if none was supplied. */
    public String getUser() {
        return user;
    }

    /**
     * Returns the password, or {@code null} if none was supplied. Callers must not log the
     * result.
     */
    public String getPassword() {
        return password;
    }

    /**
     * Returns the configured default timeout in seconds, or {@code -1} if the SDK default
     * applies.
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** Returns {@code true} if the connection was asked to be read-only. */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** Returns the maximum number of paths a path query may return; {@code 0} means unlimited. */
    public long getMaxPathResults() {
        return maxPathResults;
    }

    /**
     * Returns the timeout in seconds for transactions this driver starts, or {@code 0} to leave
     * it to the server.
     */
    public int getTransactionTimeoutSeconds() {
        return transactionTimeoutSeconds;
    }

    /** Returns {@code true} if TLS was requested. */
    public boolean isSsl() {
        return ssl;
    }

    /**
     * Returns a description that is safe to log: it contains the hosts and graph but never the
     * password.
     */
    @Override
    public String toString() {
        return "UltipaConnectionConfig{hosts=" + hosts
                + ", graph=" + graph
                + ", user=" + (user == null ? "<none>" : user)
                + ", password=" + (password == null ? "<none>" : "<redacted>")
                + ", ssl=" + ssl
                + ", timeoutSeconds=" + timeoutSeconds
                + ", readOnly=" + readOnly
                + '}';
    }

    /**
     * Returns the property names this driver understands, for diagnostics.
     *
     * @return an unmodifiable list of property names
     */
    static List<String> supportedPropertyNames() {
        return Arrays.stream(describeProperties()).map(property -> property.name).toList();
    }
}
