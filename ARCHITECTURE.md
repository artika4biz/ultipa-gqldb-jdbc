# ultipa-gqldb-jdbc — Architecture and Implementation Plan

Status: Stage 1 research complete. Written before implementation, updated as stages landed.

## 1. Research findings

All findings below were verified against the **actual published artifact**, not documentation prose.
The driver JAR, its POM, its `-sources.jar` and the embedded `gqldb.proto` were downloaded from
Maven Central and inspected with `javap` and direct source reading.

### 1.1 The official Java SDK

| Fact | Value | How it was verified |
|---|---|---|
| Maven coordinates | `com.ultipa:ultipa-gqldb-driver` | `maven-metadata.xml` on repo1.maven.org |
| Latest release at time of writing | `6.2.15` | `<release>` in `maven-metadata.xml` |
| License | MIT | `<licenses>` in the published POM |
| Compiled for | Java 8 bytecode (`maven.compiler.target=1.8`) | published POM |
| Transport | gRPC (`grpc-netty-shaded`, `protobuf-java`) | published POM `<dependencies>` |
| Protocol definition | `gqldb.proto` ships **inside** the JAR | `unzip -l` on the JAR |

Two other artifacts exist under the `com.ultipa` group and are **not** used here:
`ultipa-java-sdk` (4.x, legacy) and `ultipa-driver` (5.x, the pre-GQLDB generation).

`com.ultipa:ultipa-jdbc-driver`, which Ultipa's v5.3 docs describe as a SQL-to-GQL JDBC driver,
**is not published on Maven Central** (HTTP 404 for its `maven-metadata.xml`). See §6 for the
naming-collision risk this creates.

### 1.2 SDK API surface actually used

```java
GqldbConfig config = GqldbConfig.builder()
    .hosts("host:port")          // varargs or List<String>
    .username(...).password(...)
    .defaultGraph(...)
    .timeout(seconds)            // default RPC/query deadline
    .maxRecvSize(bytes).poolSize(n)
    .retryCount(n).retryDelay(Duration)
    .healthCheckInterval(Duration)
    .sslContext(SSLContext)      // TLS when non-null
    .disableUseGraph(bool)
    .build();

GqldbClient client = new GqldbClient(config);   // implements AutoCloseable
Session  session   = client.login(user, password);
Response response  = client.gql(gqlText, queryConfig);
```

`QueryConfig` is the single lever for per-statement behaviour, and `QueryService` was read to
confirm each field is actually placed on the wire:

| `QueryConfig` field | Wire field in `GqlRequest` | Used by this driver for |
|---|---|---|
| `graphName` | `graph_name` | per-statement graph override |
| `parameters` (`Map<String,Object>`) | `repeated Parameter` | `PreparedStatement` binding |
| `transactionId` | `transaction_id` | `setAutoCommit(false)` |
| `timeout` (seconds) | `timeout` | `Statement.setQueryTimeout` |
| `readOnly` | `read_only` | `Connection.setReadOnly` |
| `maxPathResults` | `max_path_results` | connection property |

### 1.3 Native parameter binding exists — and this is the pivotal finding

`gqldb.proto` defines `message Parameter { string name = 1; TypedValue value = 2; }`, and
`QueryService.encodeParameters` sends every entry of `QueryConfig.parameters` as a typed,
server-side-bound value. Parameters are therefore **never** interpolated into query text.

Parameter reference syntax, confirmed from Ultipa's driver documentation:

- in the query, a parameter is referenced as `$name`;
- in the parameters map, the key is `name` — **without** the leading `$`.

`Converters.convertToProtoTypedValue` encodes `null` as a typed NULL, so `setNull` is native too.

**Consequence:** `PreparedStatement` is implemented with real server-side binding. No string
concatenation, no client-side escaping, no quoting of user data anywhere in this driver.

### 1.4 Result shape and the metadata limitation

`GqlResponse` carries `columns` (names), `rows`, `row_count`, `has_more`, `warnings`,
`rows_affected`, `current_graph`, timing counters and a `DmlStats` breakdown.

**`columns` is a list of names only — the protocol carries no column type descriptors.**
Types live per *cell*, on each `TypedValue`. GQLDB is dynamically typed, so a column's type is
a property of the values returned, not of the result shape.

This drives a documented design decision (§4): `ResultSetMetaData` types are **inferred from
the first row**, and reported as `OTHER`/`NULL` when there is no row to infer from.

### 1.5 Transactions are genuinely supported

`TransactionService` exposes `Begin`/`Commit`/`Rollback`, surfaced as
`client.beginTransaction(graph, readOnly, timeoutSeconds)`, `commit(id)`, `rollback(id)`, and
honoured by `gql()` through `QueryConfig.transactionId`. JDBC transaction control is therefore
implemented for real rather than stubbed.

**But the protocol has no concept of isolation levels** (`grep -i isolation gqldb.proto` finds
nothing). `getTransactionIsolation()` reports `TRANSACTION_NONE` and
`setTransactionIsolation` accepts only that value — see §5.

### 1.6 GQL lexical facts that the `?` rewriter must respect

Verified from Ultipa's GQL reference:

- String literals have **five** forms: `'…'`, `"…"`, `` `…` ``, triple-quoted
  (`'''…'''`, `"""…"""`, ` ```…``` `), and raw strings `@'…'` (no escape processing).
- Comments: `//` to end of line, `--` to end of line, and `/* … */` block.

Both matter: a `?` inside any of these must not be treated as a parameter marker.
`--` being a comment is why GQL requires `<- -` instead of `<--`.

Delimited-identifier support could not be confirmed from the documentation (backtick is
documented as a *string literal* delimiter, not an identifier delimiter), so
`getIdentifierQuoteString()` returns `" "` — the JDBC encoding for "not supported" — rather
than guessing.

## 2. Layering

Consumers see only `java.sql.*` and, for graph values, `com.ultipa.jdbc.graph.*`.
No `com.gqldb.*` type is ever returned to a caller.

```
            java.sql.* (Driver, Connection, Statement, ResultSet, …)
                              │
  com.ultipa.jdbc        UltipaDriver → UltipaConnectionConfig
                         UltipaConnection → UltipaStatement → UltipaPreparedStatement
                         UltipaResultSet / …MetaData / UltipaDatabaseMetaData
                         UltipaTypeMapper · UltipaExceptionMapper · GqlParameterRewriter
                              │
  com.ultipa.jdbc.graph  GraphNode · GraphEdge · GraphPath · GraphPoint · GraphTable · GraphError
                              │
  com.ultipa.jdbc.internal    GqlExecutor  ← the seam
                              │
                         SdkGqlExecutor  (the only class that touches com.gqldb.*)
                              │
                         com.ultipa:ultipa-gqldb-driver (gRPC)
```

### The `GqlExecutor` seam

`GqlExecutor` is a narrow internal interface covering exactly what the JDBC layer needs:
execute, ping, graph selection, catalogue/label/property listing, and transaction control.
`SdkGqlExecutor` is its only production implementation and the sole place where SDK types
appear.

This exists for two concrete reasons:

1. **Testability without a server.** Unit tests supply a fake `GqlExecutor`, so connection
   lifecycle, statement semantics, type mapping, metadata and exception mapping are all covered
   with no network and no mocking framework.
2. **Isolation from SDK churn.** The SDK is on a fast 6.x cadence; the blast radius of an API
   change is one class.

The seam deliberately returns the SDK's `Response`, which is directly constructible via its
public constructors — this is what makes server-free tests of the full result path possible.

## 3. JDBC URL and configuration

```
jdbc:ultipa://host:port[,host2:port2…][/graph][?prop=value&…]
```

- Multiple `host:port` pairs map to `GqldbConfig.hosts(...)`.
- The path segment is the default graph; it may be omitted and set later via `setCatalog`.
- **Ports must be explicit.** No default port is assumed: the documentation does not specify
  one, and inventing a value would be guesswork.
- Precedence: URL query parameters override the `Properties` passed to `DriverManager`, which
  override defaults. (The URL is the more specific source.)
- `user`/`password` may come from either; they are the only properties read from `Properties`
  by JDBC convention.

A graph maps to a **JDBC catalog**, not a schema: graphs are independent, switchable
namespaces, which is what a catalog is. `getSchemas()` is empty.

## 4. Type mapping

`UltipaTypeMapper` is the single place where a `PropertyType` becomes a Java object and a
`java.sql.Types` constant.

| GQLDB `PropertyType` | Java object from `getObject` | `java.sql.Types` |
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

Graph values are **preserved, not flattened**. `GraphNode` keeps id, uuid, labels and
properties; `GraphEdge` keeps endpoints and label; `GraphPath` keeps its node and edge
sequences. Nested properties inside a node arrive already converted by the same mapper, so a
`List` inside a node property is a `List`, recursively.

`getString()` on any graph value yields its `toString()`; no getter silently discards graph
structure.

## 5. Deliberate, documented limitations

These are choices to **not** claim capability:

- `jdbcCompliant()` returns `false`. The driver speaks GQL, not full entry-level SQL-92.
- No SQL-to-GQL translation. Query text is passed through verbatim. §6 keeps the door open.
- `ResultSet` is `TYPE_FORWARD_ONLY`, `CONCUR_READ_ONLY`, `HOLD_CURSORS_OVER_COMMIT`;
  all updater methods throw `SQLFeatureNotSupportedException`.
- Rows are fully materialised by the SDK before `ResultSet` wraps them; `setFetchSize` is
  accepted and ignored (and says so), because the protocol's streaming mode is not row-paged.
- Isolation levels: `TRANSACTION_NONE` only (§1.5).
- Savepoints, `setHoldability` to `CLOSE_CURSORS_AT_COMMIT`, `Clob`/`NClob`/`SQLXML`,
  `RowId`, `Array`, `Ref`, scrollable/updatable cursors, `getGeneratedKeys`: all throw
  `SQLFeatureNotSupportedException`.
- No connection pool, per the brief. `Connection` is single-threaded-use and pool-friendly:
  `isValid()` pings, `close()` is idempotent, and no state leaks between statements.
- `getTables` reports node labels as `TABLE` and edge labels as `EDGE`; `getTableTypes`
  returns both. Node and edge labels sharing a name are distinguished by that type.
- BI-tool compatibility is **not** claimed. It has not been tested against any BI tool.

## 6. Naming-collision risk (flagged, not silently accepted)

Ultipa's v5.3 docs describe an official `com.ultipa:ultipa-jdbc-driver` whose driver class is
also `com.ultipa.jdbc.UltipaDriver` and whose URL scheme is also `jdbc:ultipa://`. That
artifact is not on Maven Central, but if it is distributed another way, putting both on one
classpath would collide on the class name and both would accept the same URLs.

The brief specifies the `com/ultipa/jdbc/` layout and `UltipaDriver` name explicitly, so that
is what is implemented. The risk is recorded here and in the README rather than being resolved
unilaterally; renaming the package (e.g. `com.ultipa.gqldb.jdbc`) and/or the scheme
(`jdbc:ultipa:gqldb://`) is the mitigation if the two ever need to coexist.

## 7. SQL compatibility strategy

GQL is the only query language. The pass-through is a single call in `UltipaStatement`, so a
future optional translation layer is one interface away: a `QueryTranslator` applied to the
text before it reaches `GqlExecutor`, defaulting to identity. No partial SQL parser is
included, because a half-working one is worse than none.

## 8. Execution semantics (defined, since JDBC leaves this to the driver)

| Method | Read query (has columns) | Write with no `RETURN` | Behaviour |
|---|---|---|---|
| `executeQuery` | `ResultSet` | `SQLException` | never returns null |
| `executeUpdate` | `SQLException` | `rows_affected` | `executeLargeUpdate` for >2³¹ |
| `execute` | `true` | `false` | then `getResultSet`/`getUpdateCount` |

Per ISO/IEC 39075, a write without `RETURN` yields no rows; `rows_affected` is the sum and
`DmlStats` the breakdown. A write *with* `RETURN` yields both: `execute` reports `true` and the
affected counts stay available through the graph-aware `UltipaStatement.getDmlStats()`
extension, kept off the standard API.

Server `warnings` become `SQLWarning`s on the owning `Statement`/`Connection`.

## 9. Error mapping

`UltipaExceptionMapper` turns SDK failures into the right `SQLException` subclass with an
SQLState. Mapping is driven by the SDK's own typed exceptions and `ErrorReasons` tokens, not by
message-string matching, except where no token exists.

| Source | JDBC exception | SQLState |
|---|---|---|
| auth failure at login | `SQLInvalidAuthorizationSpecException` | `28000` |
| unreachable host / transport | `SQLNonTransientConnectionException` | `08001`/`08006` |
| `GraphNotFoundException` | `SQLSyntaxErrorException` | `3D000` |
| `EmptyQueryException` | `SQLSyntaxErrorException` | `42000` |
| `WriteConflictException`, `GRAPH_BUSY`, `LEADER_CHANGED` | `SQLTransientException` | `40001` |
| timeout / `DEADLINE_EXCEEDED` | `SQLTimeoutException` | `HYT00` |
| `TransactionNotFoundException`, `PartlyCommittedException` | `SQLTransactionRollbackException` | `40000` |
| everything else | `SQLException` | `HY000` |

Credentials are never placed in a message, a log record, or a `toString()`.

## 10. Testing strategy

- **Unit tests, no server required.** The `GqlExecutor` fake plus the SDK's public `Response`,
  `Row` and `TypedValue` constructors cover URL parsing, config validation, driver
  registration, statement semantics, the full type-mapping matrix, `?` rewriting against all
  five string-literal forms and all three comment forms, metadata, warnings, resource cleanup
  and exception mapping.
- **Integration tests** run only when `ULTIPA_JDBC_URL` (and optionally `ULTIPA_JDBC_USER` /
  `ULTIPA_JDBC_PASSWORD`) are set, and are skipped via JUnit 5 assumptions otherwise. No
  credentials are hardcoded, and integration results are reported honestly — unrun is stated
  as unrun.

## 11. Stage outcomes

Stage 1 research is recorded above. Stages 2–7 outcomes, including the build and test status,
are reported in the README's **Project status** section.

### What a live server changed

The driver was later run against GQLDB Community Edition `gqldb-grpc 6.2.151`. One design
assumption was wrong, and is worth recording because it was invisible to unit testing.

`DatabaseMetaData.getTables` and `getColumns` were first built on `SHOW NODE TYPES` /
`SHOW EDGE TYPES`, chosen in Stage 1 because those statements return labels and their
properties together in one round trip. Against a real graph both returned **nothing**, while
the labels plainly existed.

The reason is that the two families of statement answer different questions:

| Statement | Answers | Schema-less graph |
|---|---|---|
| `SHOW NODE LABELS` / `SHOW EDGE LABELS` | which labels are in use | populated |
| `SHOW NODE TYPES` / `SHOW EDGE TYPES` | the graph's *declared schema* — key labels, implied labels, endpoint types, property definitions | **empty** |

A schema-less (open) graph declares no schema at all; the server says so explicitly, refusing
`ALTER GRAPH … ADD NODE` with *"cannot add node type to open graph"*. The SDK compounds this:
`showNodeProperty(label)` delegates to the TYPES statements and **throws** when a label is not
declared, so the whole property path is schema-only.

`SdkGqlExecutor.listLabels` now queries both and merges them: labels come from the LABELS
statements, so `getTables` works for every graph type, and properties are attached from the
TYPES statements where the graph declares them. A label with no declared properties yields an
empty `getColumns`, which is the truthful answer rather than a sampled guess. The merge is a
static, SDK-free method so that it is unit-testable, and `SdkGqlExecutorMergeTest` covers the
flattening of the list-valued label column, de-duplication, and declared labels not yet in
use.

Both halves are confirmed against the server: a schema-enforced graph reports `Device` with
`serial`/`port`/`seen` typed `VARCHAR`/`INTEGER`/`TIMESTAMP`, and an open graph reports the
labels with no columns.
