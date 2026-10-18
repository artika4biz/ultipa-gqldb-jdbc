package com.ultipa.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Describes the parameters of an {@link UltipaPreparedStatement}.
 *
 * <p>The count is exact: it comes from scanning the statement for {@code ?} placeholders. The
 * per-parameter descriptions are reported as unknown, which is the truthful answer — GQLDB binds
 * parameters as dynamically typed values and the protocol has no "describe statement" request, so
 * the server never tells the driver what type a given placeholder expects. The type a parameter
 * takes is decided by the value bound to it.
 */
public final class UltipaParameterMetaData implements ParameterMetaData {

    private final int parameterCount;

    /**
     * Creates parameter metadata.
     *
     * @param parameterCount the number of {@code ?} placeholders in the statement
     */
    UltipaParameterMetaData(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    @Override
    public int getParameterCount() {
        return parameterCount;
    }

    private void checkParameter(int parameter) throws SQLException {
        if (parameter < 1 || parameter > parameterCount) {
            throw UltipaExceptionMapper.invalidArgument(
                    "Parameter index " + parameter + " is out of range 1.." + parameterCount);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link #parameterNullableUnknown}: the server does not describe placeholders.
     */
    @Override
    public int isNullable(int parameter) throws SQLException {
        checkParameter(parameter);
        return parameterNullableUnknown;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link Types#OTHER}: a GQLDB parameter takes the type of the value bound to it.
     */
    @Override
    public int getParameterType(int parameter) throws SQLException {
        checkParameter(parameter);
        return Types.OTHER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code "unknown"}: the server does not describe placeholders.
     */
    @Override
    public String getParameterTypeName(int parameter) throws SQLException {
        checkParameter(parameter);
        return "unknown";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code java.lang.Object}: any supported Java type may be bound.
     */
    @Override
    public String getParameterClassName(int parameter) throws SQLException {
        checkParameter(parameter);
        return Object.class.getName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@link #parameterModeIn}: GQLDB has no output parameters.
     */
    @Override
    public int getParameterMode(int parameter) throws SQLException {
        checkParameter(parameter);
        return parameterModeIn;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code false}: the parameter's type is unknown, so no signedness can be
     * claimed for it.
     */
    @Override
    public boolean isSigned(int parameter) throws SQLException {
        checkParameter(parameter);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code 0}: unknown.
     */
    @Override
    public int getPrecision(int parameter) throws SQLException {
        checkParameter(parameter);
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code 0}: unknown.
     */
    @Override
    public int getScale(int parameter) throws SQLException {
        checkParameter(parameter);
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> target) throws SQLException {
        if (target != null && target.isInstance(this)) {
            return target.cast(this);
        }
        throw UltipaExceptionMapper.invalidArgument(
                "UltipaParameterMetaData cannot be unwrapped to "
                        + (target == null ? "null" : target.getName()));
    }

    @Override
    public boolean isWrapperFor(Class<?> target) {
        return target != null && target.isInstance(this);
    }
}
