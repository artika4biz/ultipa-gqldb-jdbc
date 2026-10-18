package com.ultipa.jdbc;

import com.gqldb.EmptyQueryException;
import com.gqldb.GqldbException;
import com.gqldb.GraphNotFoundException;
import com.gqldb.TransactionNotFoundException;
import com.gqldb.WriteConflictException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaExceptionMapperTest {

    @Test
    @DisplayName("maps a missing graph to an invalid-catalog syntax error")
    void mapsGraphNotFound() {
        SQLException mapped = UltipaExceptionMapper.map(
                "executing a GQL statement", new GraphNotFoundException("no such graph"));

        assertAll(
                () -> assertInstanceOf(SQLSyntaxErrorException.class, mapped),
                () -> assertEquals("3D000", mapped.getSQLState()));
    }

    @Test
    @DisplayName("maps an empty query to a syntax error")
    void mapsEmptyQuery() {
        SQLException mapped =
                UltipaExceptionMapper.map("executing a GQL statement", new EmptyQueryException());

        assertAll(
                () -> assertInstanceOf(SQLSyntaxErrorException.class, mapped),
                () -> assertEquals("42000", mapped.getSQLState()));
    }

    @Test
    @DisplayName("maps a write conflict to a transient serialization failure, so a retry is signalled")
    void mapsWriteConflict() {
        SQLException mapped = UltipaExceptionMapper.map(
                "executing a GQL statement", new WriteConflictException("conflict"));

        assertAll(
                () -> assertInstanceOf(SQLTransientException.class, mapped),
                () -> assertEquals("40001", mapped.getSQLState()));
    }

    @Test
    @DisplayName("maps a lost transaction to a rollback exception")
    void mapsTransactionNotFound() {
        SQLException mapped = UltipaExceptionMapper.map(
                "committing a transaction", new TransactionNotFoundException());

        assertAll(
                () -> assertInstanceOf(SQLTransactionRollbackException.class, mapped),
                () -> assertEquals("40000", mapped.getSQLState()));
    }

    @Test
    @DisplayName("maps gRPC transport statuses to the matching JDBC exceptions")
    void mapsGrpcStatuses() {
        assertAll(
                () -> assertInstanceOf(SQLTimeoutException.class, UltipaExceptionMapper.map(
                        "executing", new StatusRuntimeException(Status.DEADLINE_EXCEEDED))),
                () -> assertInstanceOf(SQLNonTransientConnectionException.class,
                        UltipaExceptionMapper.map("executing",
                                new StatusRuntimeException(Status.UNAVAILABLE))),
                () -> assertInstanceOf(SQLInvalidAuthorizationSpecException.class,
                        UltipaExceptionMapper.map("executing",
                                new StatusRuntimeException(Status.UNAUTHENTICATED))),
                () -> assertInstanceOf(SQLSyntaxErrorException.class, UltipaExceptionMapper.map(
                        "executing", new StatusRuntimeException(Status.INVALID_ARGUMENT))),
                () -> assertInstanceOf(SQLFeatureNotSupportedException.class,
                        UltipaExceptionMapper.map("executing",
                                new StatusRuntimeException(Status.UNIMPLEMENTED))),
                () -> assertInstanceOf(SQLTransientException.class, UltipaExceptionMapper.map(
                        "executing", new StatusRuntimeException(Status.ABORTED))));
    }

    @Test
    @DisplayName("finds a gRPC status wrapped inside another exception")
    void unwrapsNestedGrpcStatus() {
        SQLException mapped = UltipaExceptionMapper.map("executing",
                new GqldbException("wrapped", new StatusRuntimeException(Status.UNAVAILABLE)));

        assertInstanceOf(SQLNonTransientConnectionException.class, mapped);
    }

    @Test
    @DisplayName("falls back to a general SQLException, keeping the engine code and the cause")
    void fallsBackToGeneralException() {
        GqldbException failure = new GqldbException("something went wrong", 1234);

        SQLException mapped = UltipaExceptionMapper.map("executing a GQL statement", failure);

        assertAll(
                () -> assertEquals("HY000", mapped.getSQLState()),
                () -> assertEquals(1234, mapped.getErrorCode()),
                () -> assertSame(failure, mapped.getCause()),
                () -> assertTrue(mapped.getMessage().contains("something went wrong")),
                () -> assertTrue(mapped.getMessage().contains("executing a GQL statement")));
    }

    @Test
    @DisplayName("passes an SQLException through unchanged")
    void passesSqlExceptionsThrough() {
        SQLException original = new SQLException("already mapped");

        assertSame(original, UltipaExceptionMapper.map("executing", original));
    }

    @Test
    @DisplayName("a failure while connecting is reported as a connection failure")
    void mapsConnectionFailures() {
        SQLException authFailure = UltipaExceptionMapper.mapConnectionFailure(
                new StatusRuntimeException(Status.UNAUTHENTICATED));
        SQLException otherFailure = UltipaExceptionMapper.mapConnectionFailure(
                new GqldbException("boom"));

        assertAll(
                () -> assertInstanceOf(SQLInvalidAuthorizationSpecException.class, authFailure),
                () -> assertEquals("28000", authFailure.getSQLState()),
                () -> assertInstanceOf(SQLNonTransientConnectionException.class, otherFailure),
                () -> assertEquals("08001", otherFailure.getSQLState()));
    }

    @Test
    @DisplayName("builds the standard exceptions the driver uses for its own checks")
    void buildsDriverExceptions() {
        assertAll(
                () -> assertEquals("0A000",
                        UltipaExceptionMapper.unsupported("Savepoints").getSQLState()),
                () -> assertTrue(UltipaExceptionMapper.unsupported("Savepoints").getMessage()
                        .contains("Savepoints")),
                () -> assertEquals("08003",
                        UltipaExceptionMapper.closed("Connection").getSQLState()),
                () -> assertEquals("22023",
                        UltipaExceptionMapper.invalidArgument("bad").getSQLState()),
                () -> assertEquals("24000",
                        UltipaExceptionMapper.invalidCursorState("bad").getSQLState()),
                () -> assertEquals("25000",
                        UltipaExceptionMapper.invalidTransactionState("bad").getSQLState()));
    }

    @Test
    @DisplayName("never copies a password into an exception message")
    void doesNotLeakCredentials() {
        SQLException mapped = UltipaExceptionMapper.mapConnectionFailure(
                new GqldbException("login rejected for user alice"));

        assertFalse(mapped.getMessage().contains("password"), mapped.getMessage());
    }
}
