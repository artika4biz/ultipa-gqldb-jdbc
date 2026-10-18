package com.ultipa.jdbc;

import com.gqldb.QueryConfig;
import com.gqldb.Response;
import com.ultipa.jdbc.internal.GqlExecutor;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A JDBC connection to Ultipa GQLDB: one authenticated SDK session, plus the JDBC state that
 * surrounds it.
 *
 * <h2>Graphs are catalogs</h2>
 *
 * <p>A GQLDB graph is exposed as a JDBC <em>catalog</em>, because graphs are independent,
 * switchable namespaces — which is what a catalog is. {@link #setCatalog(String)} selects one and
 * {@link #getCatalog()} reports the one in effect. Schemas do not exist in GQLDB, so
 * {@link #getSchema()} returns {@code null}.
 *
 * <h2>Transactions are real</h2>
 *
 * <p>GQLDB's protocol has an explicit transaction service — begin, commit, rollback — and queries
 * carry a transaction id, so JDBC transaction control is implemented rather than faked. In
 * auto-commit mode, the default, each statement is sent on its own. After
 * {@code setAutoCommit(false)}, a transaction is started <em>lazily</em>, when the first
 * statement actually runs, so turning auto-commit off costs nothing until it is used.
 *
 * <p>The protocol defines no isolation levels, so {@link #getTransactionIsolation()} reports
 * {@link Connection#TRANSACTION_NONE} and {@link #setTransactionIsolation(int)} accepts only that
 * value. Reporting a level the server never promised would be a false claim.
 *
 * <p>A transaction needs a graph, so {@code setAutoCommit(false)} requires a selected catalog by
 * the time the first statement runs. {@code USE GRAPH} is rejected by the server inside a
 * transaction, so {@link #setCatalog(String)} is refused while one is active.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Like most JDBC connections, this class is not designed for concurrent use by several
 * threads. Its own state is guarded so that a stray concurrent {@link #close()} cannot corrupt
 * bookkeeping, but statements on one connection should be used from one thread at a time. Several
 * connections may be used concurrently.
 */
public final class UltipaConnection implements Connection {

    private final GqlExecutor executor;
    private final UltipaConnectionConfig config;
    private final Set<Statement> openStatements =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private volatile boolean closed;
    private volatile boolean autoCommit = true;
    private volatile boolean readOnly;
    private volatile long transactionId;
    private volatile SQLWarning warnings;

    /**
     * Creates a connection over an already-authenticated executor.
     *
     * @param executor the session to use; this connection takes ownership and closes it
     * @param config   the configuration the session was opened with
     */
    UltipaConnection(GqlExecutor executor, UltipaConnectionConfig config) {
        this.executor = executor;
        this.config = config;
        this.readOnly = config.isReadOnly();
    }

    // ---------------------------------------------------------------------
    // Internal API used by the statement implementations
    // ---------------------------------------------------------------------

    /**
     * Runs one GQL statement with this connection's current settings applied.
     *
     * <p>This is the single point where a statement reaches the server, and the only place that
     * assembles a {@link QueryConfig}: bound parameters, query timeout, the connection's
     * read-only flag, the path-result cap and, when auto-commit is off, the active transaction.
     *
     * @param gql                  the GQL text, sent verbatim
     * @param parameters           named parameters to bind server side, may be {@code null}
     * @param queryTimeoutSeconds  the statement's query timeout in seconds, or {@code 0} for the
     *                             connection default
     * @return the server's response
     * @throws SQLException if the connection is closed or the server rejects the statement
     */
    Response executeGql(String gql, Map<String, Object> parameters, int queryTimeoutSeconds)
            throws SQLException {
        checkOpen();

        QueryConfig queryConfig = new QueryConfig();
        if (parameters != null && !parameters.isEmpty()) {
            queryConfig.setParameters(parameters);
        }
        if (queryTimeoutSeconds > 0) {
            queryConfig.setTimeout(queryTimeoutSeconds);
        }
        queryConfig.setReadOnly(readOnly);
        if (config.getMaxPathResults() > 0) {
            queryConfig.setMaxPathResults(config.getMaxPathResults());
        }
        if (!autoCommit) {
            queryConfig.setTransactionId(ensureTransaction());
        }

        return executor.execute(gql, queryConfig);
    }

    /** Returns the executor, for the metadata implementation. */
    GqlExecutor executor() {
        return executor;
    }

    /** Returns the configuration this connection was opened with. */
    UltipaConnectionConfig config() {
        return config;
    }

    /** Returns the default query timeout in seconds, or {@code 0} if the SDK default applies. */
    int defaultQueryTimeoutSeconds() {
        return Math.max(config.getTimeoutSeconds(), 0);
    }

    /** Records a statement so that {@link #close()} can close it. */
    void registerStatement(Statement statement) {
        openStatements.add(statement);
    }

    /** Forgets a statement that has closed itself. */
    void unregisterStatement(Statement statement) {
        openStatements.remove(statement);
    }

    /** Appends a warning to this connection's chain. */
    void addWarning(SQLWarning warning) {
        if (warning == null) {
            return;
        }
        synchronized (this) {
            if (warnings == null) {
                warnings = warning;
            } else {
                warnings.setNextWarning(warning);
            }
        }
    }

    private synchronized long ensureTransaction() throws SQLException {
        if (transactionId != 0) {
            return transactionId;
        }
        String graph = executor.getCurrentGraph();
        transactionId = executor.beginTransaction(
                graph, readOnly, config.getTransactionTimeoutSeconds());
        return transactionId;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw UltipaExceptionMapper.closed("Connection");
        }
    }

    // ---------------------------------------------------------------------
    // Statement creation
    // ---------------------------------------------------------------------

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        UltipaStatement statement = new UltipaStatement(this);
        registerStatement(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency,
                                     int resultSetHoldability) throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        checkHoldability(resultSetHoldability);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String gql) throws SQLException {
        checkOpen();
        UltipaPreparedStatement statement = new UltipaPreparedStatement(this, gql);
        registerStatement(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String gql, int resultSetType,
                                              int resultSetConcurrency) throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        return prepareStatement(gql);
    }

    @Override
    public PreparedStatement prepareStatement(String gql, int resultSetType,
                                              int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        checkResultSetOptions(resultSetType, resultSetConcurrency);
        checkHoldability(resultSetHoldability);
        return prepareStatement(gql);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generated keys are not available: GQLDB returns values a statement asks for through
     * {@code RETURN}, which arrive as an ordinary result set, and the protocol has no separate
     * generated-key channel. Use {@code RETURN} and read the result set instead.
     */
    @Override
    public PreparedStatement prepareStatement(String gql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
            return prepareStatement(gql);
        }
        throw UltipaExceptionMapper.unsupported(
                "Retrieving generated keys (use a GQL RETURN clause and read the result set)");
    }

    @Override
    public PreparedStatement prepareStatement(String gql, int[] columnIndexes) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Retrieving generated keys (use a GQL RETURN clause and read the result set)");
    }

    @Override
    public PreparedStatement prepareStatement(String gql, String[] columnNames) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Retrieving generated keys (use a GQL RETURN clause and read the result set)");
    }

    @Override
    public CallableStatement prepareCall(String gql) throws SQLException {
        throw UltipaExceptionMapper.unsupported("CallableStatement (GQLDB has no stored procedures)");
    }

    @Override
    public CallableStatement prepareCall(String gql, int resultSetType,
                                         int resultSetConcurrency) throws SQLException {
        throw UltipaExceptionMapper.unsupported("CallableStatement (GQLDB has no stored procedures)");
    }

    @Override
    public CallableStatement prepareCall(String gql, int resultSetType, int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        throw UltipaExceptionMapper.unsupported("CallableStatement (GQLDB has no stored procedures)");
    }

    private void checkResultSetOptions(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkOpen();
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
            throw UltipaExceptionMapper.unsupported(
                    "Scrollable result sets; only TYPE_FORWARD_ONLY is available");
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw UltipaExceptionMapper.unsupported(
                    "Updatable result sets; only CONCUR_READ_ONLY is available");
        }
    }

    private void checkHoldability(int holdability) throws SQLException {
        if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw UltipaExceptionMapper.unsupported(
                    "CLOSE_CURSORS_AT_COMMIT; result sets are fully materialised and always "
                            + "held over a commit");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the statement unchanged. The driver sends GQL to the server verbatim and
     * performs no translation, so there is no native form to convert to.
     */
    @Override
    public String nativeSQL(String gql) throws SQLException {
        checkOpen();
        return gql;
    }

    // ---------------------------------------------------------------------
    // Transactions
    // ---------------------------------------------------------------------

    @Override
    public void setAutoCommit(boolean newAutoCommit) throws SQLException {
        checkOpen();
        if (this.autoCommit == newAutoCommit) {
            return;
        }
        synchronized (this) {
            if (newAutoCommit && transactionId != 0) {
                // JDBC requires any work in progress to be committed when auto-commit is
                // switched back on.
                long active = transactionId;
                transactionId = 0;
                executor.commit(active);
            }
            this.autoCommit = newAutoCommit;
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw UltipaExceptionMapper.invalidTransactionState(
                    "commit() cannot be called while the connection is in auto-commit mode");
        }
        synchronized (this) {
            if (transactionId == 0) {
                // Nothing has run since the last boundary; there is no transaction to commit.
                return;
            }
            long active = transactionId;
            transactionId = 0;
            executor.commit(active);
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw UltipaExceptionMapper.invalidTransactionState(
                    "rollback() cannot be called while the connection is in auto-commit mode");
        }
        synchronized (this) {
            if (transactionId == 0) {
                return;
            }
            long active = transactionId;
            transactionId = 0;
            executor.rollback(active);
        }
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Savepoints");
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw UltipaExceptionMapper.unsupported("Savepoints");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Savepoints");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Savepoints");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only {@link Connection#TRANSACTION_NONE} is accepted. The GQLDB protocol carries no
     * isolation-level concept, so no other level can be honoured.
     */
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        if (level != TRANSACTION_NONE) {
            throw UltipaExceptionMapper.unsupported(
                    "Transaction isolation level " + level
                            + "; the GQLDB protocol does not expose isolation levels, so only "
                            + "TRANSACTION_NONE can be reported");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;

        SQLException firstFailure = null;

        // Roll back anything in flight rather than letting the server decide later.
        long active;
        synchronized (this) {
            active = transactionId;
            transactionId = 0;
        }
        if (active != 0) {
            try {
                executor.rollback(active);
            } catch (SQLException e) {
                firstFailure = e;
            }
        }

        for (Statement statement : openStatements.toArray(new Statement[0])) {
            try {
                statement.close();
            } catch (SQLException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        openStatements.clear();

        executor.close();

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validity is checked by pinging the server. As {@link Connection#isValid(int)} requires,
     * an unreachable server yields {@code false} rather than an exception.
     *
     * <p>The {@code timeout} argument is not applied: the SDK's ping uses the connection's
     * configured timeout, and the protocol offers no per-call deadline for it. A non-zero value
     * is therefore accepted and ignored; a negative one is rejected, as the contract requires.
     */
    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw UltipaExceptionMapper.invalidArgument("isValid timeout must not be negative");
        }
        if (closed) {
            return false;
        }
        return executor.isAlive();
    }

    @Override
    public void setReadOnly(boolean newReadOnly) throws SQLException {
        checkOpen();
        if (transactionId != 0) {
            throw UltipaExceptionMapper.invalidTransactionState(
                    "setReadOnly(boolean) cannot be called while a transaction is active");
        }
        this.readOnly = newReadOnly;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Selects a graph, which this driver exposes as the JDBC catalog. Refused while a
     * transaction is active, because the server rejects {@code USE GRAPH} inside one.
     */
    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        if (catalog == null || catalog.isBlank()) {
            throw UltipaExceptionMapper.invalidArgument("Catalog (graph) name must not be empty");
        }
        if (transactionId != 0) {
            throw UltipaExceptionMapper.invalidTransactionState(
                    "The graph cannot be changed while a transaction is active; commit or roll "
                            + "back first");
        }
        executor.useGraph(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return executor.getCurrentGraph();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code null}. GQLDB has no schema level; graphs are reported as catalogs.
     */
    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Schemas (GQLDB graphs are exposed as JDBC catalogs; use setCatalog)");
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new UltipaDatabaseMetaData(this);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        synchronized (this) {
            warnings = null;
        }
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        checkHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the connection. The SDK offers no way to interrupt a request already in flight,
     * so this does not return before an in-progress statement finishes; it is otherwise
     * equivalent to {@link #close()}.
     */
    @Override
    public void abort(Executor abortExecutor) throws SQLException {
        if (abortExecutor == null) {
            throw UltipaExceptionMapper.invalidArgument("abort requires an Executor");
        }
        close();
    }

    @Override
    public void setNetworkTimeout(Executor timeoutExecutor, int milliseconds) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Changing the network timeout after connecting; set the \"timeout\" connection "
                        + "property instead");
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        int timeoutSeconds = config.getTimeoutSeconds();
        return timeoutSeconds > 0 ? (int) Math.min(Integer.MAX_VALUE, timeoutSeconds * 1000L) : 0;
    }

    // ---------------------------------------------------------------------
    // Client info
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>No client-info properties are supported, so this always reports the name as unknown,
     * which is what the contract prescribes for an unrecognised property.
     */
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new SQLClientInfoException(
                "Client info properties are not supported",
                Map.of(name == null ? "" : name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY));
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Map<String, ClientInfoStatus> failures = new java.util.HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
        }
        throw new SQLClientInfoException("Client info properties are not supported", failures);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        return new Properties();
    }

    // ---------------------------------------------------------------------
    // Type factories
    // ---------------------------------------------------------------------

    @Override
    public Clob createClob() throws SQLException {
        throw UltipaExceptionMapper.unsupported("Clob");
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw UltipaExceptionMapper.unsupported("Blob (bind a byte[] instead)");
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw UltipaExceptionMapper.unsupported("NClob");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw UltipaExceptionMapper.unsupported("SQLXML");
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.Array (bind a java.util.List instead; GQLDB lists are returned as List)");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "java.sql.Struct (bind a java.util.Map instead; GQLDB maps are returned as Map)");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return Map.of();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Custom type maps");
    }

    // ---------------------------------------------------------------------
    // Wrapper
    // ---------------------------------------------------------------------

    @Override
    public <T> T unwrap(Class<T> target) throws SQLException {
        if (target != null && target.isInstance(this)) {
            return target.cast(this);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "UltipaConnection cannot be unwrapped to " + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
