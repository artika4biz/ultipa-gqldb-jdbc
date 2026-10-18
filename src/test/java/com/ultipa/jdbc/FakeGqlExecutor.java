package com.ultipa.jdbc;

import com.gqldb.DmlStats;
import com.gqldb.QueryConfig;
import com.gqldb.Response;
import com.gqldb.Row;
import com.gqldb.types.TypedValue;
import com.ultipa.jdbc.internal.GqlExecutor;
import com.ultipa.jdbc.internal.GraphLabelDef;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link GqlExecutor} that answers from canned responses, so the whole JDBC layer can be
 * tested without a server and without a mocking framework.
 *
 * <p>It records every request it receives — query text, bound parameters, timeout, read-only
 * flag and transaction id — which is what lets the tests assert that values are bound as
 * protocol parameters rather than spliced into the query text.
 */
final class FakeGqlExecutor implements GqlExecutor {

    /** One recorded request. */
    record Executed(String gql, Map<String, Object> parameters, int timeout,
                    boolean readOnly, long transactionId, long maxPathResults) {
    }

    private final List<Executed> executed = new ArrayList<>();
    private final List<String> graphs = new ArrayList<>(List.of("socialGraph", "otherGraph"));
    private final List<GraphLabelDef> nodeLabels = new ArrayList<>();
    private final List<GraphLabelDef> edgeLabels = new ArrayList<>();
    private final List<Long> committed = new ArrayList<>();
    private final List<Long> rolledBack = new ArrayList<>();

    private Function<String, Response> responder = gql -> response(List.of(), List.of(), 0);
    private RuntimeException failWith;
    private String serverVersion = "6.2.15";
    private String currentGraph = "socialGraph";
    private boolean alive = true;
    private long nextTransactionId = 100L;
    private int beginCount;
    private boolean closed;
    private String lastUsedGraph;

    // ---------------------------------------------------------------------
    // Response construction helpers
    // ---------------------------------------------------------------------

    /**
     * Builds a response from plain Java values.
     *
     * @param columns      the column names
     * @param rowValues    one list of values per row
     * @param rowsAffected the affected-row count to report
     * @return the response
     */
    static Response response(List<String> columns, List<List<Object>> rowValues, long rowsAffected) {
        return response(columns, rowValues, rowsAffected, List.of(), null);
    }

    static Response response(List<String> columns, List<List<Object>> rowValues, long rowsAffected,
                             List<String> warnings, DmlStats dmlStats) {
        List<Row> rows = new ArrayList<>(rowValues.size());
        for (List<Object> values : rowValues) {
            List<TypedValue> typed = new ArrayList<>(values.size());
            for (Object value : values) {
                typed.add(TypedValue.fromValue(value));
            }
            rows.add(new Row(typed));
        }
        return new Response(columns, rows, rows.size(), false, warnings, rowsAffected,
                "socialGraph", 0L, 0L, 0L, dmlStats);
    }

    /** Builds a single-column, single-row response holding one already-typed value. */
    static Response singleValue(String column, TypedValue value) {
        Row row = new Row(List.of(value));
        return new Response(List.of(column), List.of(row), 1L, false, List.of(), 0L,
                "socialGraph", 0L, 0L, 0L, null);
    }

    // ---------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------

    FakeGqlExecutor respondWith(Response response) {
        this.responder = gql -> response;
        return this;
    }

    FakeGqlExecutor respondWith(Function<String, Response> responder) {
        this.responder = responder;
        return this;
    }

    FakeGqlExecutor failWith(RuntimeException failure) {
        this.failWith = failure;
        return this;
    }

    FakeGqlExecutor serverVersion(String version) {
        this.serverVersion = version;
        return this;
    }

    FakeGqlExecutor currentGraph(String graph) {
        this.currentGraph = graph;
        return this;
    }

    FakeGqlExecutor alive(boolean value) {
        this.alive = value;
        return this;
    }

    FakeGqlExecutor withNodeLabel(String name, Map<String, String> properties) {
        nodeLabels.add(label(name, properties));
        return this;
    }

    FakeGqlExecutor withEdgeLabel(String name, Map<String, String> properties) {
        edgeLabels.add(label(name, properties));
        return this;
    }

    private static GraphLabelDef label(String name, Map<String, String> properties) {
        List<com.ultipa.jdbc.internal.GraphPropertyDef> defs = new ArrayList<>();
        properties.forEach((propertyName, type) ->
                defs.add(new com.ultipa.jdbc.internal.GraphPropertyDef(propertyName, type)));
        return new GraphLabelDef(name, defs);
    }

    // ---------------------------------------------------------------------
    // Recorded state
    // ---------------------------------------------------------------------

    List<Executed> executions() {
        return List.copyOf(executed);
    }

    Executed lastExecution() {
        if (executed.isEmpty()) {
            throw new IllegalStateException("No statement has been executed");
        }
        return executed.get(executed.size() - 1);
    }

    List<Long> committedTransactions() {
        return List.copyOf(committed);
    }

    List<Long> rolledBackTransactions() {
        return List.copyOf(rolledBack);
    }

    int beginCount() {
        return beginCount;
    }

    boolean isClosed() {
        return closed;
    }

    String lastUsedGraph() {
        return lastUsedGraph;
    }

    // ---------------------------------------------------------------------
    // GqlExecutor
    // ---------------------------------------------------------------------

    @Override
    public Response execute(String gql, QueryConfig config) throws SQLException {
        Map<String, Object> parameters = config.getParameters() == null
                ? Map.of()
                : new LinkedHashMap<>(config.getParameters());
        executed.add(new Executed(gql, parameters, config.getTimeout(), config.isReadOnly(),
                config.getTransactionId(), config.getMaxPathResults()));
        if (failWith != null) {
            throw UltipaExceptionMapper.map("executing a GQL statement", failWith);
        }
        return responder.apply(gql);
    }

    @Override
    public String getServerVersion() {
        return serverVersion;
    }

    @Override
    public String getCurrentGraph() {
        return currentGraph;
    }

    @Override
    public void useGraph(String graph) {
        lastUsedGraph = graph;
        currentGraph = graph;
    }

    @Override
    public List<String> listGraphs() {
        return List.copyOf(graphs);
    }

    @Override
    public List<GraphLabelDef> listLabels(boolean wantNodeLabels) {
        return List.copyOf(wantNodeLabels ? nodeLabels : edgeLabels);
    }

    @Override
    public long beginTransaction(String graph, boolean readOnly, int timeoutSeconds)
            throws SQLException {
        if (graph == null || graph.isBlank()) {
            throw UltipaExceptionMapper.invalidTransactionState("No graph selected");
        }
        beginCount++;
        return nextTransactionId++;
    }

    @Override
    public void commit(long transactionId) {
        committed.add(transactionId);
    }

    @Override
    public void rollback(long transactionId) {
        rolledBack.add(transactionId);
    }

    @Override
    public boolean isAlive() {
        return alive && !closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
