package com.ultipa.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltipaDriverTest {

    @Test
    @DisplayName("is discoverable through DriverManager by the service-provider mechanism")
    void isDiscoverableByServiceLoader() {
        boolean found = Collections.list(DriverManager.getDrivers()).stream()
                .anyMatch(driver -> driver instanceof UltipaDriver);

        assertTrue(found, "the driver should be registered without an explicit Class.forName");
    }

    @Test
    @DisplayName("DriverManager resolves an Ultipa URL to this driver")
    void driverManagerResolvesTheUrl() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:ultipa://localhost:9000/socialGraph");

        assertNotNull(driver);
        assertEquals(UltipaDriver.class, driver.getClass());
    }

    @Test
    @DisplayName("an explicit Class.forName also registers it")
    void loadsByClassName() throws Exception {
        Class.forName("com.ultipa.jdbc.UltipaDriver");

        assertNotNull(DriverManager.getDriver("jdbc:ultipa://localhost:9000/g"));
    }

    @Test
    @DisplayName("accepts Ultipa URLs and no others")
    void acceptsOnlyItsOwnUrls() throws SQLException {
        UltipaDriver driver = new UltipaDriver();

        assertAll(
                () -> assertTrue(driver.acceptsURL("jdbc:ultipa://localhost:9000/g")),
                () -> assertTrue(driver.acceptsURL("jdbc:ultipa://a:1,b:2/g?user=x")),
                () -> assertFalse(driver.acceptsURL("jdbc:mysql://localhost:3306/db")),
                () -> assertThrows(SQLException.class, () -> driver.acceptsURL(null)));
    }

    @Test
    @DisplayName("connect returns null for a foreign URL, so DriverManager can try the next driver")
    void connectReturnsNullForForeignUrls() throws SQLException {
        assertNull(new UltipaDriver().connect("jdbc:postgresql://localhost:5432/db", new Properties()));
    }

    @Test
    @DisplayName("connect reports a malformed Ultipa URL instead of passing it over")
    void connectReportsMalformedUrls() {
        UltipaDriver driver = new UltipaDriver();

        SQLException failure = assertThrows(SQLException.class,
                () -> driver.connect("jdbc:ultipa://localhost/graph", new Properties()));
        assertTrue(failure.getMessage().contains("explicit port"), failure.getMessage());
    }

    @Test
    @DisplayName("reports its version, and that it is not JDBC compliant")
    void reportsVersionAndCompliance() {
        UltipaDriver driver = new UltipaDriver();

        assertAll(
                () -> assertEquals(0, driver.getMajorVersion()),
                () -> assertEquals(1, driver.getMinorVersion()),
                () -> assertEquals("0.1", UltipaDriver.version()),
                () -> assertFalse(driver.jdbcCompliant(),
                        "the driver speaks GQL, so it cannot claim JDBC compliance"),
                () -> assertNotNull(driver.getParentLogger()),
                () -> assertEquals(UltipaDriver.LOGGER_NAME, driver.getParentLogger().getName()));
    }

    @Test
    @DisplayName("describes its connection properties")
    void describesProperties() {
        java.sql.DriverPropertyInfo[] properties =
                new UltipaDriver().getPropertyInfo("jdbc:ultipa://h:1/g", new Properties());

        assertTrue(properties.length > 0);
        assertTrue(java.util.Arrays.stream(properties)
                .anyMatch(property -> property.name.equals("user")));
    }
}
