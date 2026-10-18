package com.ultipa.jdbc.graph;

import java.util.Objects;

/**
 * An error carried as a <em>value</em> rather than thrown: GQLDB's {@code ERROR} type, produced
 * by the engine's {@code TRY}/{@code CATCH} handling.
 *
 * <p>Such a value means the query itself succeeded while one cell holds an error, so the driver
 * surfaces it as data. A failed query raises a {@link java.sql.SQLException} instead.
 */
public final class GraphError {

    private final int code;
    private final String message;

    /**
     * Creates an error value.
     *
     * @param code    the engine error code
     * @param message the engine error message, may be {@code null}
     */
    public GraphError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** Returns the engine error code. */
    public int getCode() {
        return code;
    }

    /** Returns the engine error message, or {@code null}. */
    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphError other)) {
            return false;
        }
        return code == other.code && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }

    @Override
    public String toString() {
        return "GraphError{code=" + code + ", message=" + message + '}';
    }
}
