# ultipa-gqldb-jdbc

A JDBC driver for **Ultipa GQLDB**, built on Ultipa's official GQLDB Java driver.

It lets existing Java applications and JDBC-compatible tools talk to GQLDB through
`java.sql`, while keeping GQL as the query language and preserving graph values —
nodes, edges, paths — instead of flattening them into strings.

```java
try (Connection connection = DriverManager.getConnection(
        "jdbc:ultipa://localhost:9000/socialGraph", "username", "password");
     Statement statement = connection.createStatement();
     ResultSet rows = statement.executeQuery("MATCH (n:Person) RETURN n.name AS name LIMIT 10")) {

    while (rows.next()) {
        System.out.println(rows.getString("name"));
    }
}
```

## Project status

Version `0.1.0-SNAPSHOT`. Read this section before relying on anything below it.

| | |
|---|---|
| Builds | Yes — `mvn package` succeeds on JDK 17+ with no compiler warnings under `-Xlint:all` |
| Unit tests | **230 tests, all passing**, no server required |
| Integration tests | **6 tests, all passing** against a live server |
| GUI SQL client | Connects and browses metadata in DBeaver CE; **generated SQL does not work** — see [Trying it with a GUI client](#trying-it-with-a-gui-client) |
| BI tool compatibility | **Not tested.** No claim is made |

**Verified against a live server:** GQLDB Community Edition `gqldb-grpc 6.2.151`
(core `v1.1.415`) on macOS, over gRPC on port 60061 with RBAC enabled. Exercised end to end:
connection and authentication; `CREATE GRAPH`; graph selection via `setCatalog`; native GQL
reads and writes; server-side parameter binding through `PreparedStatement`; decoding of real
`NODE`, `EDGE` and `PATH` wire values; scalars, `DECIMAL`, `DATE`, `LIST`, `MAP`, `NULL` and
`wasNull`; commit and rollback, each confirmed by re-querying; `DmlStatistics`;
`DatabaseMetaData` against both a schema-less and a schema-enforced graph; and error mapping
for syntax errors, a missing graph and a licence limit.

**Not verified:** clustering and multi-host failover, TLS, LDAP, anything beyond the Community
Edition's limits (1M nodes / 1M edges / 2 graphs), and any BI tool.

## Requirements

- **Java 17** or later (the driver targets release 17).
- An Ultipa GQLDB server reachable over gRPC.
- Ultipa's official Java driver, `com.ultipa:ultipa-gqldb-driver` (pulled in transitively).

## Installation

```xml
<dependency>
  <groupId>com.ultipa</groupId>
  <artifactId>ultipa-gqldb-jdbc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Until this artifact is published, build and install it locally:

```bash
mvn clean install
```

For a GUI SQL client or any tool whose driver manager wants a single file, build the
self-contained bundle instead:

```bash
mvn -Pbundle package
# -> target/ultipa-gqldb-jdbc-0.1.0-SNAPSHOT-all.jar
```

It carries the driver, the Ultipa SDK, gRPC and protobuf in one archive, with the
`java.sql.Driver` service descriptor merged so the driver stays discoverable. Use the plain
JAR for a Maven or Gradle dependency, and the bundle only where one file is required.

The driver's only runtime dependency is `com.ultipa:ultipa-gqldb-driver` (version `6.2.15`),
which brings in gRPC and protobuf. This project adds nothing else.

## Architecture

```
     your application  ──►  java.sql.*  (Driver, Connection, Statement, ResultSet, …)
                                 │
   com.ultipa.jdbc          the JDBC implementation + type and error mapping
                                 │
   com.ultipa.jdbc.graph    GraphNode · GraphEdge · GraphPath · GraphPoint · GraphTable · GraphError
                                 │
   com.ultipa.jdbc.internal GqlExecutor ──► SdkGqlExecutor
                                 │
                            com.ultipa:ultipa-gqldb-driver  (gRPC)
```

Three properties of this layout are worth knowing:

1. **No SDK type ever reaches your code.** Every `com.gqldb.*` value is translated at the
   boundary, recursively through lists, sets, maps and node properties.
2. **One seam to the SDK.** `SdkGqlExecutor` is the only class that touches the SDK, which is
   what makes the 230 unit tests possible without a server.
3. **Graph values live in their own package.** An application that only reads scalar columns
   never imports `com.ultipa.jdbc.graph`.

`ARCHITECTURE.md` has the full design, including the research that it rests on.

## JDBC URL format

```
jdbc:ultipa://host:port[,host2:port2…][/graph][?property=value&…]
```

```
jdbc:ultipa://localhost:9000/socialGraph
jdbc:ultipa://node1:9000,node2:9000/socialGraph?ssl=true&timeout=60
jdbc:ultipa://localhost:9000
```

Two things differ from a typical relational JDBC URL:

- **The port is mandatory.** The Ultipa documentation defines no default GQLDB port, so the
  driver asks for one instead of guessing. A URL without a port is rejected with a message
  saying so.
- **The path segment is a graph, which the driver exposes as a JDBC *catalog*** — see
  [Graphs are catalogs](#graphs-are-catalogs). It may be omitted and chosen later.

Several `host:port` pairs are passed to the SDK, which handles multi-host clusters.

### Connection properties

Properties may be given in the URL query string or in the `Properties` passed to
`DriverManager`. **The URL wins** where both set the same property, being the more specific
source.

| Property | Meaning |
|---|---|
| `user` (or `username`) | User name to authenticate with |
| `password` | Password to authenticate with |
| `graph` | Default graph; overrides the graph in the URL path |
| `timeout` | Default request timeout in seconds; also the default `Statement` query timeout |
| `readOnly` | Open read-only, marking every statement read-only on the server |
| `ssl` | Use TLS with the JVM's default `SSLContext` |
| `maxPathResults` | Cap on paths returned by a path query; `0` is unlimited |
| `transactionTimeout` | Timeout in seconds for transactions started by `setAutoCommit(false)`; `0` leaves it to the server |
| `maxRecvSize` | Maximum gRPC response size in bytes |
| `poolSize` | gRPC channels the SDK opens per host |
| `retryCount`, `retryDelayMillis` | SDK retry behaviour for retryable reads |
| `healthCheckIntervalMillis` | Interval of the SDK's background health check |
| `disableUseGraph` | Stop the SDK sending `USE GRAPH` when selecting a graph |

`DatabaseMetaData` and `Driver.getPropertyInfo` report the same list at runtime.

## Authentication and driver registration

Credentials come from `DriverManager`, from the URL, or from a `Properties` object:

```java
Properties info = new Properties();
info.setProperty("user", System.getenv("ULTIPA_USER"));
info.setProperty("password", System.getenv("ULTIPA_PASSWORD"));

Connection connection = DriverManager.getConnection(
        "jdbc:ultipa://localhost:9000/socialGraph", info);
```

The driver registers itself through the JDBC 4 service-provider mechanism
(`META-INF/services/java.sql.Driver`), so putting the JAR on the classpath is enough. An
explicit `Class.forName("com.ultipa.jdbc.UltipaDriver")` also works, for frameworks that
require a driver class name.

**Credentials never appear in a log record, an exception message, or any `toString()`.**
`UltipaConnectionConfig.toString()` prints `password=<redacted>`, and the exception mapper
never copies a connection property into a message.

## Running statements

### Which execution method to use

JDBC leaves the mapping between its three execution methods and a non-SQL language to the
driver, so this driver defines it explicitly. Per ISO/IEC 39075, a GQL write without a
`RETURN` clause produces no rows.

| Method | Statement that returns columns | Statement that returns none |
|---|---|---|
| `executeQuery` | the `ResultSet` | **`SQLException`** |
| `executeUpdate` | **`SQLException`** | the affected-row count |
| `execute` | `true` | `false` |

The mismatched cases raise an exception rather than handing back an empty result set or a
count of zero, so you are never quietly given a result that does not describe what happened.
When either outcome is possible, use `execute` and then `getResultSet()` / `getUpdateCount()`.

```java
// Read
try (ResultSet rows = statement.executeQuery("MATCH (n:Person) RETURN n LIMIT 5")) { … }

// Write with no RETURN
int affected = statement.executeUpdate("INSERT (:Person {name: 'Alice'})");

// Either
if (statement.execute(gql)) {
    try (ResultSet rows = statement.getResultSet()) { … }
} else {
    System.out.println(statement.getUpdateCount());
}
```

A write *with* a `RETURN` clause produces both rows and affected counts. `execute` then
reports `true` and `getUpdateCount()` returns `-1`, as JDBC requires; the counts stay
available through the graph-aware extensions:

```java
UltipaStatement ultipa = statement.unwrap(UltipaStatement.class);
DmlStatistics stats = ultipa.getDmlStats();   // inserted/deleted/set, nodes and edges
long affected = ultipa.getLastRowsAffected();
```

### PreparedStatement: parameters are bound server side

GQLDB's protocol carries parameters separately from the query text — a typed parameter list,
referenced in the query as `$name`. This driver rewrites JDBC's positional `?` into those
named parameters and sends each value as a typed protocol value.

**No parameter value is ever serialised into the query text.** The driver performs no quoting,
no escaping and no concatenation of your data into GQL, so parameter binding has no injection
surface.

```java
try (PreparedStatement statement = connection.prepareStatement(
        "MATCH (n:Person) WHERE n.name = ? AND n.age > ? RETURN n")) {

    statement.setString(1, "Robert'); DROP GRAPH social; --");  // harmless: sent as a value
    statement.setInt(2, 30);

    try (ResultSet rows = statement.executeQuery()) { … }
}
```

The statement that reaches the server is
`MATCH (n:Person) WHERE n.name = $jdbc_p1 AND n.age > $jdbc_p2 RETURN n`, with the values in
the protocol's parameter list. You can see both forms for yourself:

```java
UltipaPreparedStatement ultipa = statement.unwrap(UltipaPreparedStatement.class);
ultipa.getOriginalGql();    // as you wrote it, with ?
ultipa.getRewrittenGql();   // as sent, with $jdbc_pN — never contains values
```

**Supported parameter types.** Anything else is rejected with
`SQLFeatureNotSupportedException` rather than coerced into a lossy approximation:
`null`, `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `BigInteger`, `Float`, `Double`,
`BigDecimal`, `String`, `Character`, `byte[]`, `java.sql.Date`/`Time`/`Timestamp`,
`LocalDate`, `LocalTime`, `OffsetTime`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`,
`Instant`, `java.util.Date`, `Duration`, `Period`, `List`, `Set`, `Map` (string keys),
`float[]` for a vector, and `GraphPoint`.

`setObject(index, value, targetSqlType)` coerces the value to the declared type, so your
stated intent decides the GQLDB type.

### Placeholder detection: the one limitation to know

The `?` scanner is lexical, not a GQL parser. It correctly skips every literal and comment
form Ultipa GQL documents — single, double and backtick quotes, all three triple-quote forms,
raw `@'…'` strings, `//` and `--` line comments, and `/* … */` blocks — so a `?` inside any
of those is left alone.

What it cannot do is tell a parameter placeholder from a `?` that some future GQL construct
gives its own meaning in the same position. If you ever need such a query, send it through a
plain `Statement`, which passes text through untouched.

Generated names use the reserved `jdbc_p` prefix, so do not name your own parameters
`$jdbc_p1`, `$jdbc_p2`, … in a statement that also uses `?`.

## Reading results

`ResultSet` is **read-only and forward-only**: `next()` advances, column indexes start at 1,
and lookup by label is case-insensitive. Rows arrive already materialised from the SDK, so
`setFetchSize` has nothing to tune and is accepted and ignored; `Statement.setMaxRows` is
honoured.

Reading a value before the first `next()` or after the last row raises a `SQLException` rather
than returning `null`. Conversions happen where they are well defined — a numeric string to a
number, a number to a wider number, a temporal to its `java.sql` counterpart — and raise a
`SQLException` naming both types where they are not. Numeric conversions are range-checked, so
reading a value too large for the requested type fails rather than wrapping around.

```java
ResultSet rows = statement.executeQuery(
        "MATCH (n:Person) RETURN n.name AS name, n.age AS age, n.joined AS joined");

while (rows.next()) {
    String name = rows.getString("name");
    int age = rows.getInt("age");
    boolean ageWasNull = rows.wasNull();
    LocalDateTime joined = rows.getObject("joined", LocalDateTime.class);
}
```

### Graph value mappings

| GQLDB type | `getObject` returns | `java.sql.Types` |
|---|---|---|
| `BOOL` | `Boolean` | `BOOLEAN` |
| `INT32` | `Integer` | `INTEGER` |
| `UINT32`, `INT64`, `UINT64` | `Long` | `BIGINT` |
| `FLOAT` | `Float` | `REAL` |
| `DOUBLE` | `Double` | `DOUBLE` |
| `DECIMAL` | `BigDecimal` | `DECIMAL` |
| `STRING` | `String` | `VARCHAR` |
| `TEXT` | `String` | `LONGVARCHAR` |
| `BLOB` | `byte[]` | `VARBINARY` |
| `DATE` | `LocalDate` | `DATE` |
| `LOCAL_TIME` | `LocalTime` | `TIME` |
| `ZONED_TIME` | `OffsetTime` | `TIME_WITH_TIMEZONE` |
| `LOCAL_DATETIME`, `DATETIME` | `LocalDateTime` | `TIMESTAMP` |
| `TIMESTAMP` | `Instant` | `TIMESTAMP` |
| `ZONED_DATETIME` | `OffsetDateTime` | `TIMESTAMP_WITH_TIMEZONE` |
| `YEAR_TO_MONTH` | `Period` | `OTHER` |
| `DAY_TO_SECOND` | `Duration` | `OTHER` |
| `LIST` | `List<Object>` | `OTHER` |
| `SET` | `Set<Object>` | `OTHER` |
| `MAP`, `RECORD` | `Map<String,Object>` | `OTHER` |
| `VECTOR` | `float[]` | `OTHER` |
| `POINT`, `POINT3D` | `GraphPoint` | `OTHER` |
| `NODE` | `GraphNode` | `OTHER` |
| `EDGE` | `GraphEdge` | `OTHER` |
| `PATH` | `GraphPath` | `OTHER` |
| `TABLE` | `GraphTable` | `OTHER` |
| `ERROR` | `GraphError` | `OTHER` |
| `NULL` | `null` | `NULL` |

Types with no faithful JDBC counterpart are reported as `OTHER` and returned as immutable
value objects from `com.ultipa.jdbc.graph`, so **no graph information is discarded**:

```java
ResultSet rows = statement.executeQuery("MATCH path = (a:Person)-[e:KNOWS]->(b) RETURN a, e, path");
while (rows.next()) {
    GraphNode person = (GraphNode) rows.getObject("a");
    person.getId();
    person.getLabels();
    person.getProperty("name");       // already converted, recursively

    GraphEdge knows = (GraphEdge) rows.getObject("e");
    knows.getLabel();
    knows.getFromNodeId();
    knows.getToNodeId();

    GraphPath path = (GraphPath) rows.getObject("path");
    path.length();                    // number of edges
    path.getStartNode();
    path.getNodes();
    path.getEdges();
}
```

`getString()` on a graph value gives its `toString()`, which keeps the structure visible.
Binary values render as Base64, and temporals as ISO-8601.

### Why column types are inferred from the first row

GQLDB's protocol carries a result's column **names** only; types travel per *value*, on each
cell. That follows from the data model — a graph query's column may legitimately hold a
different type in each row.

`ResultSetMetaData` therefore reports each column's type **as observed in the first row**,
which is accurate for the overwhelmingly common homogeneous case. Two consequences:

- If a column's type varies between rows, the reported type describes the first row only.
  `getObject` always returns the actual value's type, so reading values is unaffected.
- For an **empty** result there is no row to observe, so every column is reported as
  `Types.NULL` with type name `null` — not as a guess.

Nullability is `columnNullableUnknown` for the same reason: query results state none.

## Graphs are catalogs

A GQLDB graph is exposed as a JDBC **catalog**, because graphs are independent, switchable
namespaces — which is what a catalog is. GQLDB has no schema level, so `getSchema()` returns
`null` and `getSchemas()` is empty.

```java
connection.getCatalog();              // the graph in effect
connection.setCatalog("otherGraph");  // select another graph
metaData.getCatalogs();               // every graph you can see
```

`setCatalog` is refused while a transaction is active, because the server rejects `USE GRAPH`
inside one.

`DatabaseMetaData` maps the rest of JDBC's relational vocabulary like this:

- **Node labels** are tables of type `TABLE`; **edge labels** are tables of type `EDGE`. Both
  appear in `getTables`, and `getTableTypes` lists both — so a tool filtering on `TABLE` sees
  node labels, while edge labels stay distinguishable rather than being conflated with them.
  `REMARKS` says which is which.
- **Properties** are columns. `getColumns` reports the properties a label *declares*, with the
  JDBC type derived from the server's property type name; an unrecognised server type is
  reported as `OTHER` and its original name preserved in `TYPE_NAME`, rather than guessed at.
  **Only a schema-enforced graph declares properties.** A schema-less (open) graph keeps
  properties on individual elements — the server rejects `ALTER GRAPH … ADD NODE` on one with
  *"cannot add node type to open graph"* — so `getColumns` is legitimately empty there while
  `getTables` still lists every label. To learn a result's shape in an open graph, run the
  query and read `ResultSetMetaData`.
- `getTypeInfo` describes the whole GQLDB type system.

Only the connection's current graph is introspected, because the server's introspection
statements act on the selected graph. A `catalog` argument naming a different graph yields an
empty result rather than silently switching your session.

## Transactions

GQLDB's protocol has an explicit transaction service — begin, commit, rollback — and queries
carry a transaction id, so **JDBC transaction control is real here, not stubbed**.

```java
connection.setAutoCommit(false);
try {
    statement.executeUpdate("INSERT (:Person {name: 'Alice'})");
    statement.executeUpdate("INSERT (:Person {name: 'Bob'})");
    connection.commit();
} catch (SQLException e) {
    connection.rollback();
    throw e;
}
```

- Auto-commit is the default; each statement is then sent on its own.
- After `setAutoCommit(false)`, a transaction is started **lazily**, when the first statement
  runs — so turning auto-commit off costs nothing until it is used.
- A transaction needs a selected graph by the time that first statement runs.
- `close()` rolls back anything in flight rather than leaving it to expire.
- Switching auto-commit back on commits the work in progress, as JDBC requires.

**Isolation levels do not exist in the protocol.** `getTransactionIsolation()` reports
`TRANSACTION_NONE`, and `setTransactionIsolation` accepts only that value. Reporting a level
the server never promised would be a false claim. `supportsTransactions()` is still `true`,
because commit and rollback really do work.

## Errors

Every SDK and gRPC failure is translated into the `SQLException` subclass and SQLState that
describe it, driven by the SDK's typed exceptions and the server's stable reason tokens and
engine codes — never by matching on message text.

| Condition | Exception | SQLState |
|---|---|---|
| Bad credentials | `SQLInvalidAuthorizationSpecException` | `28000` |
| Host unreachable / transport failure | `SQLNonTransientConnectionException` | `08001`, `08006` |
| Unknown graph | `SQLSyntaxErrorException` | `3D000` |
| Malformed or empty statement | `SQLSyntaxErrorException` | `42000` |
| Write conflict, graph busy, leader changed | `SQLTransientException` | `40001` |
| Timeout | `SQLTimeoutException` | `HYT00` |
| Transaction lost or partly committed | `SQLTransactionRollbackException` | `40000` |
| Insufficient privilege | `SQLException` | `42501` |
| Licence limit reached | `SQLException` | `53000` |
| Unsupported JDBC feature | `SQLFeatureNotSupportedException` | `0A000` |
| Anything else | `SQLException` | `HY000` |

`SQLTransientException` is the signal to retry; the original SDK exception is always kept as
the cause, and the engine code as `getErrorCode()`.

Server warnings become `SQLWarning`s on the owning `Statement`:

```java
for (SQLWarning warning = statement.getWarnings(); warning != null;
     warning = warning.getNextWarning()) {
    log.warn(warning.getMessage());
}
```

## Supported and unsupported

**Supported**

- `Driver`, `Connection`, `Statement`, `PreparedStatement`, `ResultSet`, `ResultSetMetaData`,
  `DatabaseMetaData`, `ParameterMetaData`
- Native GQL execution; server-side parameter binding; query timeouts; read-only statements
- Real transactions: commit, rollback, lazy begin
- Graph selection as a JDBC catalog
- Batches (`addBatch` / `executeBatch`), run statement by statement
- `Statement.setMaxRows`, `closeOnCompletion`, `getLargeUpdateCount`
- Full graph type mapping; `SQLWarning` propagation; `unwrap` on every object

**Not supported — and why**

| Feature | Reason |
|---|---|
| SQL | The server speaks GQL. `jdbcCompliant()` returns `false`, and no SQL grammar level is claimed |
| Scrollable or updatable result sets | Forward-only, read-only; updater methods throw |
| Savepoints | No protocol support |
| Isolation levels other than `TRANSACTION_NONE` | The protocol has none |
| Generated keys | Use a GQL `RETURN` clause and read the result set |
| `CallableStatement` | GQLDB has no stored procedures |
| `Clob`, `NClob`, `SQLXML`, `Array`, `Ref`, `RowId`, `Struct` | Bind `String`, `byte[]`, `List` or `Map` instead; results come back as those types |
| Stream parameters | A parameter is one complete typed value on the wire; read the stream first |
| `Statement.cancel()` | The SDK cannot interrupt a request in flight; bound it with `setQueryTimeout` |
| Non-zero `setMaxFieldSize` | Values are never silently truncated |
| JDBC escape processing | GQL is passed through verbatim; `setEscapeProcessing(true)` is refused rather than ignored |
| `setSchema` | GQLDB has no schema level; use `setCatalog` |
| Client-info properties | None supported |
| Connection pooling | Out of scope for this release — see below |
| Key and index metadata, privileges, UDTs, function catalogues | Not exposed by the protocol; the matching `DatabaseMetaData` methods return correctly shaped empty results |
| Identifier quoting | `getIdentifierQuoteString()` returns `" "`. Ultipa documents the backtick as a *string literal* delimiter and does not document a delimited-identifier form, so the driver does not advertise one it has not verified |

### Connection pooling

No pool is built in, by design. The driver is pool-friendly: `isValid()` pings the server,
`close()` is idempotent, and no state leaks between statements, so HikariCP or any standard
JDBC pool can manage `UltipaConnection` instances. A `Connection` is not intended for
concurrent use by several threads; several connections may be used concurrently.

### Naming-collision warning

Ultipa's v5.3 documentation describes an official `com.ultipa:ultipa-jdbc-driver` whose driver
class is also `com.ultipa.jdbc.UltipaDriver` and whose URL scheme is also `jdbc:ultipa://`.
That artifact is **not on Maven Central**, but if it is distributed another way, putting both
on one classpath would collide on the class name and both would accept the same URLs.

This project uses the `com.ultipa.jdbc.UltipaDriver` name as specified for it. If the two ever
need to coexist, the mitigation is to rename this driver's package (for example
`com.ultipa.gqldb.jdbc`) and/or its URL scheme (`jdbc:ultipa:gqldb://`).

## Testing

```bash
mvn test                      # unit tests only; no server needed
mvn -Dtest=UltipaResultSetTest test
```

**Unit tests — 230, all passing.** They need no server. The driver's single seam to the SDK,
`GqlExecutor`, is replaced by a test double, and the SDK's own public `Response`, `Row` and
`TypedValue` constructors are used to build realistic wire values. Coverage includes URL
parsing and property validation, driver registration and discovery, the complete type-mapping
matrix, `?` rewriting against every literal and comment form, result-set iteration and every
getter conversion including the failure cases, statement and update-count semantics,
transaction behaviour, `DatabaseMetaData`, exception mapping, warnings and resource cleanup.

**Integration tests — 6, all passing** against GQLDB Community Edition 6.2.151. They are
skipped unless the environment is configured, so an ordinary build never implies they ran:

```bash
export ULTIPA_JDBC_URL='jdbc:ultipa://host:port/graph'
export ULTIPA_JDBC_USER='username'
export ULTIPA_JDBC_PASSWORD='password'
mvn test
```

No credentials are hardcoded anywhere in the test suite, and every integration test is
read-only — none creates, alters or deletes data.

To start a throwaway server locally (macOS or Linux):

```bash
# One-time install of GQLDB Community Edition - free for personal, non-commercial use
curl -fsSL https://download.ultipa.com/gqldb/install.sh | sh

ultipa-gqldb --db /tmp/gqldb-data --rbac --admin-pass 'choose-a-password' --port 60061
```

## Trying it with a GUI client

**DBeaver Community Edition** is the free, open-source (Apache-2.0) choice on macOS, and the
one this was checked with. Install it with `brew install --cask dbeaver-community`.

First build the single-file bundle, because a GUI driver manager wants one JAR rather than the
driver plus its gRPC and protobuf dependencies:

```bash
mvn -Pbundle package
# -> target/ultipa-gqldb-jdbc-0.1.0-SNAPSHOT-all.jar  (about 20 MB, self-contained)
```

Then in DBeaver: **Database → Driver Manager → New**

| Field | Value |
|---|---|
| Driver Name | `Ultipa GQLDB` |
| Class Name | `com.ultipa.jdbc.UltipaDriver` |
| URL Template | `jdbc:ultipa://{host}:{port}/{database}` |
| Default Port | `60061` |
| Libraries | add the `-all.jar` above |

Leave "No authentication" unchecked and supply the user and password on the connection. Then
**Database → New Database Connection → Ultipa GQLDB**, with host `127.0.0.1`, port `60061`,
and your graph name as the database.

### What works, and what does not

This matters more than the setup, so be clear about it before you start:

| In DBeaver | Result |
|---|---|
| Connecting, and the connection test | Works |
| The navigator tree: graphs, node and edge labels, their declared properties | Works — it uses `DatabaseMetaData` |
| Typing **GQL** in the SQL editor and running it | Works — statements are passed to the server verbatim |
| **Double-clicking a table / "View Data"** | **Fails.** DBeaver generates `SELECT * FROM Device`, and the server answers `SQLSyntaxErrorException`, SQLState `42000` |
| Any other DBeaver feature that writes SQL for you — row editing, data compare, generated DDL, visual query builder | **Fails**, for the same reason |

### Why the unsupported methods do not break it

Four methods this driver refuses are ones a GUI client may call anyway. Checked against
DBeaver 26.2.2's own bytecode, none of them blocks a connection:

| Method | What DBeaver does |
|---|---|
| `setClientInfo` | Called by `GenericDataSource.populateClientAppName` to set `ApplicationName`, inside a `catch (Throwable)` that only logs. The `SQLClientInfoException` is swallowed |
| `setEscapeProcessing` | Only ever called as `setEscapeProcessing(false)`, from `disableStatementEscapeProcessing`. This driver accepts `false`; only `true` is refused |
| `setNetworkTimeout` | Not part of the connect sequence; it exists in DBeaver's own `JDBCConnectionImpl` only as a delegating wrapper. Connection pools that do call it catch `SQLFeatureNotSupportedException`, which is the contract's way of declining |

Of 57 calls a generic client typically makes on connect — connection state,
`DatabaseMetaData`, statement setup — 53 succeed and those 4 are declined harmlessly.

That the generated SQL fails is expected, not a defect: this driver carries GQL to a GQL engine
and does not translate SQL. Verified directly against the server — `SELECT * FROM Device`, `SELECT serial FROM Device
LIMIT 10` and even `SELECT 1` are all rejected with `42000`, while
`MATCH (n:Device) RETURN n LIMIT 10` and `RETURN 1 AS ok` succeed.

So treat DBeaver as a GQL console with a working object browser. Use it for:

```gql
MATCH (n) RETURN n LIMIT 25
SHOW NODE LABELS
MATCH p = (a)-[e]->(b) RETURN p LIMIT 10
```

If you want a tool whose generated SQL also works, that needs the optional SQL-to-GQL
translation layer the architecture leaves room for — it is not in this release.

### Without installing anything

A single-file Java program is often quicker for a smoke test, and JDK 17+ runs one directly:

```bash
cat > /tmp/Probe.java <<'EOF'
import java.sql.*;
public class Probe {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(
                 "jdbc:ultipa://127.0.0.1:60061/jdbcTest", "admin", "your-password");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("MATCH (n) RETURN n LIMIT 10")) {
            System.out.println("server " + c.getMetaData().getDatabaseProductVersion());
            while (rs.next()) {
                System.out.println(rs.getObject(1));
            }
        }
    }
}
EOF
java -cp target/ultipa-gqldb-jdbc-0.1.0-SNAPSHOT-all.jar /tmp/Probe.java
```

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `must include an explicit port` | The URL has no port. GQLDB has no documented default; write `host:port` |
| `No suitable driver found` | The JAR is not on the classpath. If your framework needs a class name, use `com.ultipa.jdbc.UltipaDriver` |
| `SQLInvalidAuthorizationSpecException` (`28000`) | Wrong user or password |
| `SQLNonTransientConnectionException` (`08006`) | Host, port, firewall, or a server that is down. Check whether the server expects TLS and set `ssl=true` |
| `SQLSyntaxErrorException` (`3D000`) | The graph does not exist, or the user cannot see it. `metaData.getCatalogs()` lists what is visible |
| `executeQuery cannot satisfy its contract` | The statement returned no rows. Use `executeUpdate`, or `execute` if either outcome is possible |
| `executeUpdate cannot report it` | The statement returned rows. Use `executeQuery`, or `execute` |
| `Parameter 2 of 2 has not been set` | A `?` was left unbound before execution |
| `A graph must be selected before a transaction can be started` | `setAutoCommit(false)` with no graph selected. Name one in the URL or call `setCatalog` |
| `Column … cannot be converted to int` | The getter does not match the column's actual type. Use `getObject` to see what is there |
| `SQLTimeoutException` (`HYT00`) | The statement exceeded its timeout. Raise `setQueryTimeout` or the `timeout` property |
| `SQLTransientException` (`40001`) | A write conflict or a busy graph. Retry the transaction |
| A `DatabaseMetaData` call returns nothing | Either the protocol does not expose it (see the table above), or the `catalog` argument names a graph other than the current one |
| `getColumns` is empty but `getTables` lists the label | The graph is schema-less, so the server declares no properties. Expected — see [Graphs are catalogs](#graphs-are-catalogs) |
| `SQLSyntaxErrorException` `42000` on a `SELECT` | The server speaks GQL, not SQL. This is what makes a GUI client's generated queries fail — see [Trying it with a GUI client](#trying-it-with-a-gui-client) |
| `SQLException` `53000` mentioning a database limit | A Community Edition licence cap (1M nodes / 1M edges / 2 graphs) |
| `cannot drop graph … it is the currently active graph` | Select another graph with `setCatalog` before dropping this one |

To see what the driver is doing, enable `java.util.logging` for `com.ultipa.jdbc` at `FINE`.
Credentials are never logged.

## Version compatibility

| | |
|---|---|
| This driver | `0.1.0-SNAPSHOT` |
| Ultipa GQLDB Java driver | `6.2.15` (declared with an explicit version; no version range) |
| Java | 17 or later |
| JDBC | 4.3 (`getJDBCMajorVersion` 4, `getJDBCMinorVersion` 3) |

Verified against **GQLDB Community Edition `gqldb-grpc 6.2.151`** (core `v1.1.415`). Other
server versions are not claimed; the SDK's own documented server support applies. The driver
reads the server version from the login response and reports it through
`DatabaseMetaData.getDatabaseProductVersion()`.

## Licensing

This driver is released under the **Apache License 2.0**.

It depends on:

| Dependency | License | Note |
|---|---|---|
| `com.ultipa:ultipa-gqldb-driver` | MIT | Permissive; compatible with Apache-2.0 distribution |
| gRPC (`io.grpc:grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`) | Apache-2.0 | Transitive, via the Ultipa driver |
| `com.google.protobuf:protobuf-java` | BSD-3-Clause | Transitive, via the Ultipa driver |
| `javax.annotation:javax.annotation-api` | CDDL-1.1 / GPL-2.0-with-classpath-exception | Transitive, via the Ultipa driver |
| JUnit 5 | EPL-2.0 | Test scope only; not distributed |

The `javax.annotation-api` dependency is dual-licensed and reaches you through the Ultipa
driver; its classpath exception means linking does not impose GPL terms. If your distribution
policy excludes CDDL artifacts, review that transitive dependency before shipping.
