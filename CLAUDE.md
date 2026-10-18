# JDBC for GQLDB

Role

Act as a senior Java database-driver engineer with deep expertise in JDBC, database connectivity, query execution, graph databases, Maven, and production-grade open-source software.

Project Objective

Design and implement a production-ready JDBC driver for Ultipa GQLDB.

The goal is to allow existing Java applications and JDBC-compatible tools to connect to Ultipa GQLDB through the standard Java SQL API.

The driver should provide a familiar JDBC interface while preserving Ultipa’s native graph-query capabilities through GQL.

Project name: ultipa-gqldb-jdbc

Suggested Maven coordinates:

* Group ID: com.ultipa
* Artifact ID: ultipa-gqldb-jdbc
* Initial version: 0.1.0-SNAPSHOT

1. Research Before Implementation

Before writing code, inspect the current repository and research the official Ultipa GQLDB documentation and Java driver implementation.

Use these resources as starting points:

* Ultipa documentation: https://www.ultipa.com/docs
* Ultipa driver documentation: https://www.ultipa.com/docs/drivers
* Official Ultipa driver repository: https://github.com/ultipa/gqldb-drivers
* Java JDBC specification: https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/module-summary.html

Verify the current GQLDB Java driver’s Maven coordinates, supported server versions, connection configuration, authentication mechanisms, query execution APIs, response structure, error handling, and resource-management behavior.

Inspect the actual source code and public APIs before choosing the implementation strategy.

Do not invent APIs, protocol details, or undocumented behavior.

Prefer reusing the official Ultipa Java driver rather than implementing a new network protocol.

Produce a short architecture document describing the findings, assumptions, dependencies, limitations, and implementation plan.

2. Architecture

Build a standalone Java library implementing the standard JDBC API.

Use a layered architecture:

1. JDBC public API layer.
2. Connection and session management.
3. Statement execution and GQL query submission.
4. Result conversion and JDBC ResultSet implementation.
5. Graph-type conversion and metadata.
6. Authentication, configuration, and error mapping.
7. Logging, diagnostics, and testing.

Use the official Ultipa Java driver for server communication wherever possible.

Keep the JDBC layer independent of Ultipa-specific classes exposed to consumers.

Use interfaces and small, testable components rather than one large implementation class.

3. JDBC Interfaces

Implement the following interfaces to the extent supported by Ultipa GQLDB.

Driver

Implement java.sql.Driver.

Requirements:

* Register the driver through JDBC’s service-provider mechanism.
* Support DriverManager.getConnection(...).
* Implement acceptsURL(), connect(), getPropertyInfo(), getMajorVersion(), getMinorVersion(), jdbcCompliant(), and getParentLogger() as appropriate.
* Use a documented JDBC URL scheme, for example:
    jdbc:ultipa://hostname:port/graphName
* Support connection properties, including username, password, connection timeout, and query timeout where applicable.
* Validate JDBC URLs and provide clear error messages.

Document any URL conventions that differ from standard relational database URLs.

Connection

Implement java.sql.Connection.

Support:

* Creating Statement and PreparedStatement instances.
* Closing connections safely.
* Tracking connection state.
* Selecting or changing the current graph where supported.
* Configuring query timeouts.
* Retrieving metadata.
* Implementing isClosed(), isValid(), getAutoCommit(), and other relevant methods.
* Correctly handling unsupported JDBC functionality.

Do not pretend to support transactions, rollback, or savepoints unless the underlying server and driver provide the required semantics.

Statement

Implement java.sql.Statement.

Support:

* Executing GQL queries.
* executeQuery(), execute(), and executeUpdate() with well-defined behavior.
* Query timeouts.
* Result retrieval.
* Update counts where meaningful.
* Resource cleanup.
* Clear exception reporting.

The initial priority is native GQL execution, not translating arbitrary SQL into GQL.

Define and document how each JDBC execution method behaves for read queries, write queries, and queries that return no result set.

PreparedStatement

Implement java.sql.PreparedStatement.

Investigate whether the underlying Ultipa driver supports parameterized query execution.

If native parameter binding is unavailable, implement a safe and explicitly documented alternative.

Never implement parameter binding by blindly concatenating user-provided strings into GQL.

Implement parameter serialization with correct escaping and type handling, or reject unsupported parameter types.

Clearly document the security and compatibility limitations of the chosen implementation.

ResultSet

Implement java.sql.ResultSet.

Support:

* Iterating over returned rows.
* Column lookup by index and label.
* getString(), getBoolean(), getInt(), getLong(), getDouble(), getBigDecimal(), getBytes(), getObject(), and relevant temporal getters.
* wasNull().
* Correct JDBC column indexing, starting at 1.
* Column labels and column names.
* Type conversions and conversion errors.
* Correct cursor behavior.
* Resource lifecycle management.

Handle graph-specific values such as nodes, edges, paths, nested properties, lists, and maps.

Define a predictable mapping from graph values to Java objects. Preserve graph information when possible instead of silently flattening or discarding it.

Provide documented behavior for values that cannot be mapped to standard JDBC types.

Initially, a read-only, forward-only ResultSet is acceptable if documented.

DatabaseMetaData

Implement java.sql.DatabaseMetaData.

Expose accurate information about:

* Driver name and version.
* Database product name and version.
* JDBC version.
* Connection capabilities.
* Supported result-set types.
* Catalogs, schemas, and graph names where meaningful.
* Tables or graph entities, columns or properties, and their types where these concepts can be mapped reliably.

Investigate Ultipa’s actual metadata capabilities before implementing metadata methods.

Return accurate capability flags. Do not claim that unsupported SQL features, catalogs, schemas, or transaction operations are supported.

4. Graph Data and Type Mapping

Create a dedicated type-mapping layer.

Define mappings for:

* Strings.
* Booleans.
* Integer and floating-point numbers.
* Decimal values.
* Null values.
* Dates and timestamps.
* Lists and maps.
* Graph nodes.
* Graph edges.
* Paths and other graph-specific objects returned by GQLDB.

Use JDBC Types constants where appropriate.

Implement ResultSetMetaData with accurate column labels, types, nullability where known, and other available metadata.

Where a reliable relational mapping is impossible, expose a documented Java representation rather than inventing one.

Keep graph-aware extensions separate from the standard JDBC API.

5. Connection and Security

Implement:

* Authentication using the supported official SDK mechanisms.
* Secure handling of credentials.
* Connection-string and property validation.
* Connection and query timeouts where supported.
* Safe resource cleanup.
* Exception translation into appropriate SQLException subclasses.
* Logging that never exposes passwords, tokens, or other credentials.

Do not store credentials in source code or log sensitive connection properties.

Do not implement a connection pool in the initial release. Ensure the driver can eventually integrate with standard JDBC connection pools.

6. SQL Compatibility Strategy

Treat native GQL as the primary query language.

Do not claim compatibility with arbitrary SQL.

Design the architecture so that a future, optional SQL-to-GQL translation layer could be introduced independently.

For the initial release:

* Accept native GQL queries.
* Return graph query results through JDBC interfaces.
* Clearly distinguish query execution from SQL compatibility.
* Document limitations for tools that expect SQL-specific syntax or relational metadata.

Do not implement a partial SQL parser unless it is explicitly justified and tested.

7. Maven Project Structure

Create a clean Maven project with an appropriate Java version and a clear dependency strategy.

Suggested structure:

src/main/java/com/ultipa/jdbc/

* UltipaDriver.java
* UltipaConnection.java
* UltipaStatement.java
* UltipaPreparedStatement.java
* UltipaResultSet.java
* UltipaResultSetMetaData.java
* UltipaDatabaseMetaData.java
* UltipaTypeMapper.java
* UltipaExceptionMapper.java
* UltipaConnectionConfig.java

src/main/resources/META-INF/services/

* java.sql.Driver

src/test/java/

* Unit tests.
* JDBC contract tests.
* Integration tests.

Adjust the package and class structure if research identifies a better design.

Use Maven dependencies with explicit versions, avoid unnecessary dependencies, and document licensing implications.

8. Testing

Use JUnit 5 or another appropriate testing framework.

Write meaningful tests for:

* JDBC driver registration and discovery.
* JDBC URL parsing.
* Configuration validation.
* Authentication failures.
* Successful and unsuccessful connections.
* GQL query execution.
* ResultSet iteration and column access.
* Null handling and type conversions.
* PreparedStatement parameter handling.
* Unsupported operations.
* Timeout behavior.
* SQL exception mapping.
* Resource cleanup.
* Multiple statements and concurrent connections where supported.

Create unit tests that do not require a running database.

Create integration tests against an actual Ultipa GQLDB instance, configurable through environment variables.

Tests must not require hardcoded credentials.

If no server is available, run the unit tests and clearly identify the integration tests that remain unverified.

Do not claim successful integration testing without actually running it.

9. Documentation and Examples

Create a README.md containing:

* Project overview.
* Architecture.
* Requirements.
* Maven installation instructions.
* JDBC URL format.
* Authentication configuration.
* Driver registration.
* Connection examples.
* Statement and PreparedStatement examples.
* ResultSet examples.
* Graph value mappings.
* Supported JDBC functionality.
* Unsupported features and limitations.
* Testing instructions.
* Troubleshooting.
* Version compatibility.
* Licensing information.

Include a minimal Java example that connects through DriverManager, executes a native GQL query, iterates over the returned ResultSet, and closes all resources.

Where relevant, show how the driver could be configured in a JDBC-compatible application.

Do not claim compatibility with BI tools until it has been tested against those tools.

10. Engineering Standards

Follow these rules:

* Use idiomatic Java.
* Add Javadoc to public classes and methods.
* Avoid unnecessary abstractions.
* Keep the implementation modular.
* Avoid swallowing exceptions.
* Avoid empty method implementations that falsely suggest functionality.
* For unsupported JDBC methods, use appropriate JDBC exceptions and document the limitations.
* Do not catch exceptions merely to return empty results.
* Do not introduce unverified APIs or dependencies.
* Do not suppress compiler warnings without justification.
* Keep secrets out of logs and tests.
* Ensure every public feature has meaningful tests.

11. Implementation Workflow

Work in incremental, verifiable stages.

Stage 1: Inspect the repository and official SDK. Produce the architecture plan and identify unknowns.

Stage 2: Create the Maven project and implement the JDBC driver registration, URL parsing, configuration, and connection lifecycle.

Stage 3: Implement native GQL execution through Statement and the initial ResultSet.

Stage 4: Implement type mapping, ResultSetMetaData, DatabaseMetaData, and exception handling.

Stage 5: Implement PreparedStatement only to the extent that parameter handling can be made safe and documented.

Stage 6: Expand tests, documentation, and integration coverage.

Stage 7: Run Maven compilation, tests, static checks, and package creation. Fix all reproducible failures.

After each stage:

* Summarize changes.
* Run the relevant tests.
* Report failures and unresolved assumptions.
* Keep the project in a buildable state.

Do not stop after generating a plan. Proceed to implementation unless a genuine blocker requires clarification.

Do not replace existing files or discard user changes without first inspecting them.

Definition of Done

The initial release is complete when:

1. The Maven project builds successfully.
2. The JDBC driver is discoverable through DriverManager.
3. A JDBC connection can be established using the official Ultipa SDK.
4. Native GQL queries can be executed through Statement.
5. Query results can be iterated through ResultSet.
6. Supported graph values are mapped predictably to Java objects.
7. Errors are exposed through appropriate JDBC exceptions.
8. Resources are closed correctly.
9. Unit tests pass.
10. Integration-test status is reported honestly.
11. Installation, configuration, examples, and limitations are documented.
12. The JAR can be packaged and used by another Java application.

Begin by inspecting the repository and official SDK. Then implement the first working end-to-end JDBC query path before expanding the feature set.

