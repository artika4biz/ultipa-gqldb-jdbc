package com.ultipa.jdbc.graph;

/**
 * The per-category breakdown of what a data-modifying statement changed, as GQLDB reports it
 * alongside a response.
 *
 * <p>This is a graph-aware extension, available from
 * {@link com.ultipa.jdbc.UltipaStatement#getDmlStats()}, and deliberately kept off the standard
 * JDBC API. JDBC has only a single update count, which the driver reports from the server's
 * {@code rows_affected} total; these counts say <em>what</em> that total was made of, which
 * matters for a graph write that touches both nodes and edges.
 *
 * <p>Per ISO/IEC 39075 a write without a {@code RETURN} clause yields no rows, so for such a
 * statement these counts are the only description of its effect. All counts are zero for a pure
 * read, and also when an older server does not report them — {@link #isReported()} tells the two
 * cases apart only insofar as a server that reports nothing looks like a statement that changed
 * nothing.
 *
 * @param insertedNodes nodes created
 * @param insertedEdges edges created
 * @param deletedNodes  nodes removed
 * @param deletedEdges  edges removed
 * @param setNodes      nodes touched by {@code SET}, {@code REMOVE} or a matching {@code MERGE}
 * @param setEdges      edges touched by {@code SET}, {@code REMOVE} or a matching {@code MERGE}
 */
public record DmlStatistics(long insertedNodes, long insertedEdges,
                            long deletedNodes, long deletedEdges,
                            long setNodes, long setEdges) {

    /** Every count zero: a pure read, or a server that does not report the breakdown. */
    public static final DmlStatistics NONE = new DmlStatistics(0, 0, 0, 0, 0, 0);

    /**
     * Returns {@code true} if any count is non-zero, meaning the server reported a change.
     *
     * @return whether anything was reported as changed
     */
    public boolean isReported() {
        return insertedNodes != 0 || insertedEdges != 0
                || deletedNodes != 0 || deletedEdges != 0
                || setNodes != 0 || setEdges != 0;
    }

    /** Returns the total of every count. */
    public long total() {
        return insertedNodes + insertedEdges + deletedNodes + deletedEdges + setNodes + setEdges;
    }
}
