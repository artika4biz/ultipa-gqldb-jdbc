package com.ultipa.jdbc.graph;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * An immutable spatial point, covering both the 2D ({@code POINT}) and 3D ({@code POINT3D})
 * GQLDB types.
 *
 * <p>GQLDB's 2D point is geographic — the protocol carries longitude and latitude — while its
 * 3D point is cartesian, carrying x, y and z. Both forms are represented here, and
 * {@link #is3D()} distinguishes them. For a 2D point, {@link #getX()} is the longitude and
 * {@link #getY()} the latitude, matching the SDK's own accessors.
 */
public final class GraphPoint {

    private final double x;
    private final double y;
    private final Double z;
    private final int srid;

    private GraphPoint(double x, double y, Double z, int srid) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.srid = srid;
    }

    /**
     * Creates a 2D geographic point.
     *
     * @param longitude the longitude
     * @param latitude  the latitude
     * @param srid      the spatial reference identifier
     * @return a 2D point
     */
    public static GraphPoint of2D(double longitude, double latitude, int srid) {
        return new GraphPoint(longitude, latitude, null, srid);
    }

    /**
     * Creates a 3D cartesian point.
     *
     * @param x    the x ordinate
     * @param y    the y ordinate
     * @param z    the z ordinate
     * @param srid the spatial reference identifier
     * @return a 3D point
     */
    public static GraphPoint of3D(double x, double y, double z, int srid) {
        return new GraphPoint(x, y, z, srid);
    }

    /** Returns the x ordinate; for a 2D point this is the longitude. */
    public double getX() {
        return x;
    }

    /** Returns the y ordinate; for a 2D point this is the latitude. */
    public double getY() {
        return y;
    }

    /** Returns the z ordinate, empty for a 2D point. */
    public OptionalDouble getZ() {
        return z == null ? OptionalDouble.empty() : OptionalDouble.of(z);
    }

    /** Returns the longitude of a 2D point; for a 3D point this is the x ordinate. */
    public double getLongitude() {
        return x;
    }

    /** Returns the latitude of a 2D point; for a 3D point this is the y ordinate. */
    public double getLatitude() {
        return y;
    }

    /** Returns the spatial reference identifier reported by the server. */
    public int getSrid() {
        return srid;
    }

    /** Returns {@code true} if this point carries a z ordinate. */
    public boolean is3D() {
        return z != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GraphPoint other)) {
            return false;
        }
        return Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Objects.equals(z, other.z)
                && srid == other.srid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, srid);
    }

    @Override
    public String toString() {
        return is3D()
                ? "GraphPoint{x=" + x + ", y=" + y + ", z=" + z + ", srid=" + srid + '}'
                : "GraphPoint{longitude=" + x + ", latitude=" + y + ", srid=" + srid + '}';
    }
}
