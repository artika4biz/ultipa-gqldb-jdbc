package com.ultipa.jdbc.internal;

import com.gqldb.QueryConfig;
import com.gqldb.Response;

import java.sql.SQLException;
import java.util.List;

/**
 * The seam between this JDBC driver and the official Ultipa GQLDB SDK.
 *
 * <p>Everything the JDBC layer needs from a server is expressed here, so that
 * {@link com.ultipa.jdbc.UltipaConnection} and the statement and metadata implementations can be
 * exercised in unit tests against a fake, with no server and no mocking framework.
 * {@link SdkGqlExecutor} is the only production implementation and the only class in the driver
 * that constructs SDK clients.
 *
 * <p><strong>Not public API.</strong> This interface mentions SDK types ({@link Response},
 * {@link QueryConfig}) and may change in any release. Applications use {@code java.sql} and
 * {@link com.ultipa.jdbc.graph} instead.
 *
 * <p>Implementations translate SDK and transport failures into {@link SQLException} via
 * {@link com.ultipa.jdbc.UltipaExceptionMapper}, so callers never see an SDK exception.
 */
public interface GqlExecutor extends AutoCloseable {

    /**
     * Executes one GQL request.
     *
     * @param gql    the GQL text, passed to the server verbatim
     * @param config per-request settings: graph, bound parameters, timeout, read-only flag and
     *               transaction id
     * @return the server's response
     * @throws SQLException if the server rejects the request or the transport fails
     */
    Response execute(String gql, QueryConfig config) throws SQLException;

    /**
     * Returns the server version reported at login, or {@code null} if the server did not
     * report one.
     */
    String getServerVersion();

    /**
     * Returns the graph currently selected for this session, or {@code null} if none is
     * selected.
     */
    String getCurrentGraph();

    /**
     * Selects a graph for subsequent requests on this session.
     *
     * @param graph the graph name
     * @throws SQLException if the graph does not exist or cannot be selected
     */
    void useGraph(String graph) throws SQLException;

    /**
     * Lists the graphs visible to the authenticated user.
     *
     * @return the graph names
     * @throws SQLException if the request fails
     */
    List<String> listGraphs() throws SQLException;

    /**
     * Lists the node or edge labels of the current graph, each with the properties declared for
     * it.
     *
     * <p>Labels and their properties come from a single server introspection call, so
     * {@code DatabaseMetaData.getTables} and {@code getColumns} do not need separate round trips.
     *
     * @param nodeLabels {@code true} for node labels, {@code false} for edge labels
     * @return the label definitions
     * @throws SQLException if the request fails
     */
    List<GraphLabelDef> listLabels(boolean nodeLabels) throws SQLException;

    /**
     * Begins an explicit transaction.
     *
     * @param graph          the graph the transaction runs against
     * @param readOnly       whether the transaction is read-only
     * @param timeoutSeconds the transaction timeout in seconds; {@code 0} leaves it to the server
     * @return the server-assigned transaction id, to be passed to
     *         {@link QueryConfig#setTransactionId(long)}
     * @throws SQLException if the transaction cannot be started
     */
    long beginTransaction(String graph, boolean readOnly, int timeoutSeconds) throws SQLException;

    /**
     * Commits a transaction.
     *
     * @param transactionId the id returned by {@link #beginTransaction}
     * @throws SQLException if the commit fails
     */
    void commit(long transactionId) throws SQLException;

    /**
     * Rolls a transaction back.
     *
     * @param transactionId the id returned by {@link #beginTransaction}
     * @throws SQLException if the rollback fails
     */
    void rollback(long transactionId) throws SQLException;

    /**
     * Checks liveness without throwing.
     *
     * @return {@code true} if the server answered
     */
    boolean isAlive();

    /** Releases the session and the underlying transport. Idempotent. */
    @Override
    void close();
}
