package com.ultipa.jdbc;

import com.gqldb.EmptyQueryException;
import com.gqldb.EngineCodes;
import com.gqldb.ErrorDetails;
import com.gqldb.ErrorReasons;
import com.gqldb.GqldbException;
import com.gqldb.GraphNotFoundException;
import com.gqldb.GraphSwitchRejectedException;
import com.gqldb.PartlyCommittedException;
import com.gqldb.TransactionNotFoundException;
import com.gqldb.TransactionSignInExpiredException;
import com.gqldb.WriteConflictException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;

/**
 * Translates failures from the Ultipa SDK and from the gRPC transport into the
 * {@link SQLException} subclass and SQLState a JDBC caller can act on.
 *
 * <p>Classification is driven by the SDK's typed exceptions, by the stable
 * {@code UPPER_SNAKE_CASE} reason tokens the server attaches to its gRPC status details (read via
 * {@link ErrorDetails}), and by engine codes from {@link EngineCodes} — never by matching on
 * message text, which is not a stable contract.
 *
 * <p>No method here copies a connection property into a message, so credentials cannot leak
 * through an exception.
 */
public final class UltipaExceptionMapper {

    /** General error. */
    private static final String STATE_GENERAL = "HY000";
    /** Unable to establish a connection. */
    private static final String STATE_CONNECTION_REJECTED = "08001";
    /** Connection failure. */
    private static final String STATE_CONNECTION_FAILURE = "08006";
    /** Invalid authorization specification. */
    private static final String STATE_INVALID_AUTHORIZATION = "28000";
    /** Insufficient privilege. */
    private static final String STATE_INSUFFICIENT_PRIVILEGE = "42501";
    /** Syntax error or access rule violation. */
    private static final String STATE_SYNTAX_ERROR = "42000";
    /** Invalid catalog (graph) name. */
    private static final String STATE_INVALID_CATALOG = "3D000";
    /** Serialization failure; retrying the transaction may succeed. */
    private static final String STATE_SERIALIZATION_FAILURE = "40001";
    /** Transaction rollback. */
    private static final String STATE_TRANSACTION_ROLLBACK = "40000";
    /** Timeout expired. */
    private static final String STATE_TIMEOUT = "HYT00";
    /** Insufficient resources. */
    private static final String STATE_INSUFFICIENT_RESOURCES = "53000";
    /** Feature not supported. */
    private static final String STATE_NOT_SUPPORTED = "0A000";
    /** Invalid parameter value. */
    private static final String STATE_INVALID_PARAMETER = "22023";
    /** Invalid cursor state. */
    private static final String STATE_INVALID_CURSOR_STATE = "24000";
    /** Invalid transaction state. */
    private static final String STATE_INVALID_TRANSACTION_STATE = "25000";
    /** Invalid connection (object already closed). */
    private static final String STATE_OBJECT_NOT_OPEN = "08003";

    private UltipaExceptionMapper() {
    }

    /**
     * Maps a failure raised while executing a statement or any other server request.
     *
     * @param context a short description of what was attempted, for example
     *                {@code "executing GQL"}; must not contain credentials
     * @param failure the failure to translate
     * @return the {@link SQLException} to throw
     */
    public static SQLException map(String context, Throwable failure) {
        if (failure instanceof SQLException sqlException) {
            return sqlException;
        }

        ErrorDetails details = ErrorDetails.fromThrowable(failure);
        String reason = details.getReason();
        int engineCode = details.getCode();
        String message = describe(context, failure, details);

        // 1. The SDK's typed exceptions are the most specific signal available.
        if (failure instanceof GraphNotFoundException) {
            return new SQLSyntaxErrorException(message, STATE_INVALID_CATALOG, engineCode, failure);
        }
        if (failure instanceof EmptyQueryException) {
            return new SQLSyntaxErrorException(message, STATE_SYNTAX_ERROR, engineCode, failure);
        }
        if (failure instanceof WriteConflictException) {
            return new SQLTransientException(message, STATE_SERIALIZATION_FAILURE, engineCode, failure);
        }
        if (failure instanceof PartlyCommittedException
                || failure instanceof TransactionNotFoundException
                || failure instanceof TransactionSignInExpiredException) {
            return new SQLTransactionRollbackException(message, STATE_TRANSACTION_ROLLBACK, engineCode, failure);
        }
        if (failure instanceof GraphSwitchRejectedException) {
            return new SQLException(message, STATE_INVALID_TRANSACTION_STATE, engineCode, failure);
        }

        // 2. Server-supplied reason tokens: stable identifiers, safe to switch on.
        if (reason != null && !reason.isEmpty()) {
            switch (reason) {
                case ErrorReasons.WRITE_CONFLICT:
                case ErrorReasons.GRAPH_BUSY:
                case ErrorReasons.LEADER_CHANGED:
                case ErrorReasons.FULLTEXT_INDEX_LOADING:
                    return new SQLTransientException(message, STATE_SERIALIZATION_FAILURE, engineCode, failure);
                case ErrorReasons.WRITES_COMMITTED:
                case ErrorReasons.WRITES_PENDING:
                    return new SQLTransactionRollbackException(message, STATE_TRANSACTION_ROLLBACK, engineCode, failure);
                default:
                    break;
            }
        }

        // 3. Engine codes for conditions that have no reason token.
        switch (engineCode) {
            case EngineCodes.PERMISSION_DENIED:
                return new SQLException(message, STATE_INSUFFICIENT_PRIVILEGE, engineCode, failure);
            case EngineCodes.READ_ONLY:
            case EngineCodes.GRAPH_READ_ONLY:
            case EngineCodes.LICENSE_READ_ONLY:
                return new SQLException(message, STATE_SYNTAX_ERROR, engineCode, failure);
            case EngineCodes.GRAPH_UNAVAILABLE:
            case EngineCodes.GRAPH_RESTORING:
                return new SQLTransientException(message, STATE_SERIALIZATION_FAILURE, engineCode, failure);
            case EngineCodes.LICENSE_NODE_LIMIT:
            case EngineCodes.LICENSE_EDGE_LIMIT:
            case EngineCodes.LICENSE_GRAPH_LIMIT:
                return new SQLException(message, STATE_INSUFFICIENT_RESOURCES, engineCode, failure);
            default:
                break;
        }

        // 4. Transport-level gRPC status, for failures that never reached the engine.
        Status.Code statusCode = statusCodeOf(failure);
        if (statusCode != null) {
            switch (statusCode) {
                case DEADLINE_EXCEEDED:
                    return new SQLTimeoutException(message, STATE_TIMEOUT, engineCode, failure);
                case UNAVAILABLE:
                    return new SQLNonTransientConnectionException(message, STATE_CONNECTION_FAILURE, engineCode, failure);
                case UNAUTHENTICATED:
                    return new SQLInvalidAuthorizationSpecException(message, STATE_INVALID_AUTHORIZATION, engineCode, failure);
                case PERMISSION_DENIED:
                    return new SQLException(message, STATE_INSUFFICIENT_PRIVILEGE, engineCode, failure);
                case INVALID_ARGUMENT:
                case FAILED_PRECONDITION:
                case OUT_OF_RANGE:
                    return new SQLSyntaxErrorException(message, STATE_SYNTAX_ERROR, engineCode, failure);
                case NOT_FOUND:
                    return new SQLSyntaxErrorException(message, STATE_INVALID_CATALOG, engineCode, failure);
                case UNIMPLEMENTED:
                    return new SQLFeatureNotSupportedException(message, STATE_NOT_SUPPORTED, engineCode, failure);
                case RESOURCE_EXHAUSTED:
                    return new SQLException(message, STATE_INSUFFICIENT_RESOURCES, engineCode, failure);
                case ABORTED:
                    return new SQLTransientException(message, STATE_SERIALIZATION_FAILURE, engineCode, failure);
                case CANCELLED:
                    return new SQLTimeoutException(message, STATE_TIMEOUT, engineCode, failure);
                default:
                    break;
            }
        }

        if (failure instanceof GqldbException gqldbException) {
            return new SQLException(message, STATE_GENERAL, gqldbException.getCode(), failure);
        }
        return new SQLException(message, STATE_GENERAL, engineCode, failure);
    }

    /**
     * Maps a failure raised while opening a connection, where the most likely causes are a bad
     * address and bad credentials.
     *
     * @param failure the failure to translate
     * @return the {@link SQLException} to throw
     */
    public static SQLException mapConnectionFailure(Throwable failure) {
        SQLException mapped = map("opening a connection", failure);
        if (mapped instanceof SQLInvalidAuthorizationSpecException
                || mapped instanceof SQLNonTransientConnectionException
                || mapped instanceof SQLTimeoutException) {
            return mapped;
        }
        // Anything else that stopped a connection from opening is still a connection failure
        // from the caller's point of view; keep the original as the cause.
        return new SQLNonTransientConnectionException(
                mapped.getMessage(), STATE_CONNECTION_REJECTED, mapped.getErrorCode(), failure);
    }

    /**
     * Builds the exception for a JDBC method this driver does not implement.
     *
     * @param feature a description of the unsupported feature
     * @return the exception to throw
     */
    public static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(
                feature + " is not supported by the Ultipa GQLDB JDBC driver", STATE_NOT_SUPPORTED);
    }

    /**
     * Builds the exception for an operation attempted on a closed object.
     *
     * @param objectName the kind of object, for example {@code "Connection"}
     * @return the exception to throw
     */
    public static SQLException closed(String objectName) {
        return new SQLException(objectName + " is closed", STATE_OBJECT_NOT_OPEN);
    }

    /**
     * Builds the exception for an invalid argument supplied by the caller.
     *
     * @param message what was wrong
     * @return the exception to throw
     */
    public static SQLException invalidArgument(String message) {
        return new SQLException(message, STATE_INVALID_PARAMETER);
    }

    /**
     * Builds the exception for a cursor used before {@code next()} or after the last row.
     *
     * @param message what was wrong
     * @return the exception to throw
     */
    public static SQLException invalidCursorState(String message) {
        return new SQLException(message, STATE_INVALID_CURSOR_STATE);
    }

    /**
     * Builds the exception for a transaction method called in the wrong state, such as
     * {@code commit()} in auto-commit mode.
     *
     * @param message what was wrong
     * @return the exception to throw
     */
    public static SQLException invalidTransactionState(String message) {
        return new SQLException(message, STATE_INVALID_TRANSACTION_STATE);
    }

    private static Status.Code statusCodeOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StatusRuntimeException statusRuntimeException) {
                return statusRuntimeException.getStatus().getCode();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private static String describe(String context, Throwable failure, ErrorDetails details) {
        StringBuilder text = new StringBuilder("Ultipa GQLDB error while ").append(context);
        String reason = details.getReason();
        if (reason != null && !reason.isEmpty()) {
            text.append(" [").append(reason).append(']');
        }
        if (details.getCode() != 0) {
            text.append(" (engine code ").append(details.getCode()).append(')');
        }
        String detail = failure.getMessage();
        if (detail != null && !detail.isBlank()) {
            text.append(": ").append(detail);
        }
        return text.toString();
    }
}
