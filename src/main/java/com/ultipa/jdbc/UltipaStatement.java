package com.ultipa.jdbc;

import com.gqldb.DmlStats;
import com.gqldb.Response;
import com.ultipa.jdbc.graph.DmlStatistics;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link Statement} that sends GQL to the server verbatim.
 *
 * <h2>What each execution method does</h2>
 *
 * <p>JDBC leaves the mapping between its three execution methods and a non-SQL language to the
 * driver, so this driver defines it explicitly. Per ISO/IEC 39075, a GQL write without a
 * {@code RETURN} clause produces no rows, and the server reports what it changed through
 * {@code rows_affected} and a {@code DmlStats} breakdown. Accordingly:
 *
 * <table class="striped">
 *   <caption>Execution semantics</caption>
 *   <thead><tr><th>Method<th>Statement that returns columns<th>Statement that returns none</tr></thead>
 *   <tbody>
 *     <tr><td>{@link #executeQuery(String)}<td>the result set<td>{@link SQLException}</tr>
 *     <tr><td>{@link #executeUpdate(String)}<td>{@link SQLException}<td>the affected-row count</tr>
 *     <tr><td>{@link #execute(String)}<td>{@code true}<td>{@code false}</tr>
 *   </tbody>
 * </table>
 *
 * <p>The mismatched cases raise an exception rather than returning an empty result set or a
 * count of zero, so a caller is never quietly given a result that does not describe what
 * happened.
 *
 * <p>A write <em>with</em> a {@code RETURN} clause produces both rows and affected counts.
 * {@link #execute(String)} then reports {@code true} and {@link #getUpdateCount()} returns
 * {@code -1}, as JDBC requires when the current result is a result set; the counts remain
 * available from {@link #getDmlStats()} and {@link #getLastRowsAffected()}.
 *
 * <h2>Batches</h2>
 *
 * <p>{@link #executeBatch()} sends each queued statement in turn: the protocol has no
 * multi-statement batch request, so there is no round-trip saving, only the convenience of the
 * JDBC idiom. Grouping a batch into one transaction is the caller's choice, via
 * {@link Connection#setAutoCommit(boolean)}.
 */
public class UltipaStatement implements Statement {

    private final UltipaConnection connection;

    private UltipaResultSet currentResultSet;
    private long updateCount = -1L;
    private long lastRowsAffected;
    private DmlStatistics lastDmlStats = DmlStatistics.NONE;
    private int queryTimeoutSeconds;
    private int maxRows;
    private boolean poolable = true;
    private boolean closeOnCompletion;
    private boolean closed;
    private SQLWarning warnings;
    private final List<String> batch = new ArrayList<>();

    /**
     * Creates a statement.
     *
     * @param connection the owning connection
     */
    UltipaStatement(UltipaConnection connection) {
        this.connection = connection;
        this.queryTimeoutSeconds = connection.defaultQueryTimeoutSeconds();
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    /**
     * Runs one statement and installs its outcome as the current result.
     *
     * @param gql        the GQL text
     * @param parameters bound parameters, may be {@code null}
     * @return {@code true} if the statement produced a result set
     */
    final boolean executeInternal(String gql, Map<String, Object> parameters) throws SQLException {
        checkOpen();
        if (gql == null || gql.isBlank()) {
            throw UltipaExceptionMapper.invalidArgument("GQL text must not be null or blank");
        }
        closeCurrentResultSet();

        Response response = connection.executeGql(gql, parameters, queryTimeoutSeconds);
        collectWarnings(response);

        lastRowsAffected = response.getRowsAffected();
        lastDmlStats = toDmlStatistics(response.getDmlStats());

        List<String> columns = response.getColumns();
        if (columns != null && !columns.isEmpty()) {
            currentResultSet = new UltipaResultSet(this, response, maxRows);
            updateCount = -1L;
            return true;
        }
        currentResultSet = null;
        updateCount = Math.max(response.getRowsAffected(), 0L);
        return false;
    }

    private static DmlStatistics toDmlStatistics(DmlStats stats) {
        if (stats == null) {
            return DmlStatistics.NONE;
        }
        return new DmlStatistics(
                stats.getInsertedNodes(), stats.getInsertedEdges(),
                stats.getDeletedNodes(), stats.getDeletedEdges(),
                stats.getSetNodes(), stats.getSetEdges());
    }

    private void collectWarnings(Response response) {
        List<String> serverWarnings = response.getWarnings();
        if (serverWarnings == null || serverWarnings.isEmpty()) {
            return;
        }
        for (String text : serverWarnings) {
            if (text == null || text.isBlank()) {
                continue;
            }
            addWarning(new SQLWarning(text));
        }
    }

    @Override
    public ResultSet executeQuery(String gql) throws SQLException {
        if (!executeInternal(gql, null)) {
            throw UltipaExceptionMapper.invalidArgument(
                    "The statement returned no result set, so executeQuery cannot satisfy its "
                            + "contract of never returning null. Use executeUpdate for a write "
                            + "with no RETURN clause, or execute() if either outcome is possible");
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String gql) throws SQLException {
        long count = executeLargeUpdate(gql);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    @Override
    public long executeLargeUpdate(String gql) throws SQLException {
        if (executeInternal(gql, null)) {
            closeCurrentResultSet();
            throw UltipaExceptionMapper.invalidArgument(
                    "The statement returned a result set, so executeUpdate cannot report it. "
                            + "Use executeQuery, or execute() if either outcome is possible");
        }
        return updateCount;
    }

    @Override
    public boolean execute(String gql) throws SQLException {
        return executeInternal(gql, null);
    }

    @Override
    public int executeUpdate(String gql, int autoGeneratedKeys) throws SQLException {
        checkNoGeneratedKeys(autoGeneratedKeys);
        return executeUpdate(gql);
    }

    @Override
    public int executeUpdate(String gql, int[] columnIndexes) throws SQLException {
        throw generatedKeysUnsupported();
    }

    @Override
    public int executeUpdate(String gql, String[] columnNames) throws SQLException {
        throw generatedKeysUnsupported();
    }

    @Override
    public long executeLargeUpdate(String gql, int autoGeneratedKeys) throws SQLException {
        checkNoGeneratedKeys(autoGeneratedKeys);
        return executeLargeUpdate(gql);
    }

    @Override
    public long executeLargeUpdate(String gql, int[] columnIndexes) throws SQLException {
        throw generatedKeysUnsupported();
    }

    @Override
    public long executeLargeUpdate(String gql, String[] columnNames) throws SQLException {
        throw generatedKeysUnsupported();
    }

    @Override
    public boolean execute(String gql, int autoGeneratedKeys) throws SQLException {
        checkNoGeneratedKeys(autoGeneratedKeys);
        return execute(gql);
    }

    @Override
    public boolean execute(String gql, int[] columnIndexes) throws SQLException {
        throw generatedKeysUnsupported();
    }

    @Override
    public boolean execute(String gql, String[] columnNames) throws SQLException {
        throw generatedKeysUnsupported();
    }

    private void checkNoGeneratedKeys(int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != NO_GENERATED_KEYS) {
            throw generatedKeysUnsupported();
        }
    }

    private static SQLException generatedKeysUnsupported() {
        return UltipaExceptionMapper.unsupported(
                "Generated keys; add a RETURN clause to the GQL statement and read the result set");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not supported. GQLDB has no separate generated-key channel: a statement returns what it
     * is asked to return, through a {@code RETURN} clause, which arrives as an ordinary result
     * set.
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw generatedKeysUnsupported();
    }

    // ---------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return (int) Math.min(updateCount, Integer.MAX_VALUE);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code false}: one GQL request yields at most one result. The current result set
     * is closed, as the contract requires.
     */
    @Override
    public boolean getMoreResults() throws SQLException {
        checkOpen();
        closeCurrentResultSet();
        updateCount = -1L;
        return false;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();
        switch (current) {
            case CLOSE_CURRENT_RESULT:
                closeCurrentResultSet();
                break;
            case KEEP_CURRENT_RESULT:
            case CLOSE_ALL_RESULTS:
                // There is never more than one result, so keeping or closing all is the same as
                // having nothing further to report.
                break;
            default:
                throw UltipaExceptionMapper.invalidArgument(
                        "Unknown argument to getMoreResults(int): " + current);
        }
        updateCount = -1L;
        return false;
    }

    /**
     * Returns the per-category breakdown of what the last statement changed.
     *
     * <p>A graph-aware extension, outside the JDBC API: JDBC has a single update count, while a
     * graph write can touch nodes and edges separately. Use
     * {@code statement.unwrap(UltipaStatement.class).getDmlStats()} when the statement is held
     * as a {@link Statement}.
     *
     * @return the statistics for the last execution, never {@code null}
     * @throws SQLException if this statement is closed
     */
    public DmlStatistics getDmlStats() throws SQLException {
        checkOpen();
        return lastDmlStats;
    }

    /**
     * Returns the server's affected-row total for the last statement, even when that statement
     * also returned a result set.
     *
     * <p>A graph-aware extension, outside the JDBC API. Unlike
     * {@link #getUpdateCount()}, which JDBC requires to be {@code -1} while the current result
     * is a result set, this always reports what the server said.
     *
     * @return the affected-row count of the last execution
     * @throws SQLException if this statement is closed
     */
    public long getLastRowsAffected() throws SQLException {
        checkOpen();
        return lastRowsAffected;
    }

    // ---------------------------------------------------------------------
    // Batches
    // ---------------------------------------------------------------------

    @Override
    public void addBatch(String gql) throws SQLException {
        checkOpen();
        if (gql == null || gql.isBlank()) {
            throw UltipaExceptionMapper.invalidArgument("GQL text must not be null or blank");
        }
        batch.add(gql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        long[] counts = executeLargeBatch();
        int[] narrowed = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            narrowed[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
        }
        return narrowed;
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        List<String> queued = List.copyOf(batch);
        batch.clear();

        long[] counts = new long[queued.size()];
        for (int i = 0; i < queued.size(); i++) {
            try {
                if (executeInternal(queued.get(i), null)) {
                    closeCurrentResultSet();
                    counts[i] = Statement.EXECUTE_FAILED;
                    throw new BatchUpdateException(
                            "Batch entry " + (i + 1) + " returned a result set; a batched "
                                    + "statement must not return rows",
                            "0A000", 0, java.util.Arrays.copyOf(counts, i + 1), null);
                }
                counts[i] = updateCount;
            } catch (BatchUpdateException e) {
                throw e;
            } catch (SQLException e) {
                counts[i] = Statement.EXECUTE_FAILED;
                throw new BatchUpdateException(
                        "Batch entry " + (i + 1) + " failed: " + e.getMessage(),
                        e.getSQLState(), e.getErrorCode(),
                        java.util.Arrays.copyOf(counts, i + 1), e);
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------------
    // Options
    // ---------------------------------------------------------------------

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw UltipaExceptionMapper.invalidArgument("Query timeout must not be negative");
        }
        this.queryTimeoutSeconds = seconds;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeoutSeconds;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw UltipaExceptionMapper.invalidArgument("Max rows must not be negative");
        }
        this.maxRows = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        if (max > Integer.MAX_VALUE) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Max rows above Integer.MAX_VALUE is not supported; responses are "
                            + "materialised in memory");
        }
        setMaxRows((int) max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        return getMaxRows();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only {@code 0}, meaning no limit, is accepted. Truncating a value to a byte or
     * character budget would silently corrupt it, and the protocol has no server-side field
     * limit to delegate to.
     */
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max != 0) {
            throw UltipaExceptionMapper.unsupported(
                    "A non-zero max field size; values are never silently truncated");
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Accepted and ignored: a response arrives with its rows already materialised, so there
     * is no fetch size to tune.
     */
    @Override
    public void setFetchSize(int size) throws SQLException {
        checkOpen();
        if (size < 0) {
            throw UltipaExceptionMapper.invalidArgument("Fetch size must not be negative");
        }
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw UltipaExceptionMapper.unsupported(
                    "Fetch direction other than FETCH_FORWARD; result sets are forward-only");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not supported: the SDK offers no way to interrupt a request already in flight. Bound a
     * long-running statement with {@link #setQueryTimeout(int)} instead, which the server
     * enforces.
     */
    @Override
    public void cancel() throws SQLException {
        throw UltipaExceptionMapper.unsupported(
                "Cancelling a running statement; use setQueryTimeout to bound it instead");
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw UltipaExceptionMapper.unsupported("Named cursors");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only {@code false} is accepted. JDBC escape processing is a SQL-level rewriting of
     * {@code {fn ...}} and {@code {d ...}} sequences; applying it to GQL would alter a query
     * this driver promises to pass through verbatim. Escape processing is therefore always off,
     * and asking for it on is refused rather than quietly ignored.
     */
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
        if (enable) {
            throw UltipaExceptionMapper.unsupported(
                    "JDBC escape processing; GQL statements are sent to the server verbatim");
        }
    }

    @Override
    public void setPoolable(boolean newPoolable) throws SQLException {
        checkOpen();
        this.poolable = newPoolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        this.closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    final void addWarning(SQLWarning warning) {
        if (warning == null) {
            return;
        }
        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        UltipaResultSet open = currentResultSet;
        currentResultSet = null;
        if (open != null) {
            open.close();
        }
        batch.clear();
        connection.unregisterStatement(this);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** Called by a result set as it closes, to honour {@link #closeOnCompletion()}. */
    void resultSetClosed(UltipaResultSet resultSet) {
        if (currentResultSet == resultSet) {
            currentResultSet = null;
        }
        if (closeOnCompletion && !closed) {
            try {
                close();
            } catch (SQLException e) {
                // close() only fails if the result set does, and it has already closed.
                addWarning(new SQLWarning("Closing the statement on completion failed", e.getSQLState(), e));
            }
        }
    }

    private void closeCurrentResultSet() {
        UltipaResultSet open = currentResultSet;
        if (open != null) {
            currentResultSet = null;
            // Avoid the closeOnCompletion path: the statement is being reused, not finished.
            boolean previous = closeOnCompletion;
            closeOnCompletion = false;
            try {
                open.close();
            } finally {
                closeOnCompletion = previous;
            }
        }
    }

    /** Throws if this statement has been closed. */
    final void checkOpen() throws SQLException {
        if (closed) {
            throw UltipaExceptionMapper.closed("Statement");
        }
    }

    /** Returns the owning connection, for subclasses. */
    final UltipaConnection ultipaConnection() {
        return connection;
    }

    @Override
    public <T> T unwrap(Class<T> target) throws SQLException {
        if (target != null && target.isInstance(this)) {
            return target.cast(this);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "UltipaStatement cannot be unwrapped to " + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
