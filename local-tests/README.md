# Protected read-only prototype (V3)

This branch adds a protected read-only mode to the connection lock menu.
The green lock indicates an active protected connection. Selecting another mode
while connected uses the standard disconnect flow, applies the new policy only
after a successful disconnect, and reconnects. Unsaved changes and active
transactions retain standard DBeaver prompts and cancellation behavior.
SQL editors remain open; temporary session state may be lost on reconnection.

## Scope and limitations

The prototype supports MySQL and PostgreSQL; other engines are rejected in
protected mode. Ordinary connections and existing read-only remain available.
This is not yet an implementation for every DBeaver driver.

The JDBC wrapper combines a conservative SQL allowlist, server-side read-only
transactions, verification before execution, and query/lock timeouts. It blocks
unknown functions, comments, callable statements, batches, writable result sets,
manual transactions, raw connection unwrapping, and native tools in protected
mode. Some legitimate queries are deliberately rejected.

This is not a guarantee against production load or side effects in malicious
server extensions. A database account with restricted permissions is still
necessary for production access.

## Reproducing the tests

Requires Java 21, Maven, Docker, and the usual sibling dbeaver-common checkout.
Use disposable databases only: tests create and drop their own fixture tables,
functions, and views in readonly_test.

From the repository root, on a machine without containers of the same names:

    docker compose -f local-tests/readonly/compose.yaml up -d
    # Wait until both database servers accept connections.
    mvn test -f local-tests/readonly/pom.xml

Services bind only to localhost (MySQL 13316, PostgreSQL 15436). The password
in the fixtures is a disposable test credential, not a production secret.
If these test containers already exist, start them instead of creating duplicates.

The Maven harness compiles the actual production JDBC guard classes. It covers
SQL filtering, JDBC escape paths, server enforcement, hidden writes through a
view/function, timeouts, unsupported engines, and switching protected -> writable
-> protected connections on MySQL and PostgreSQL.

The model-jobs directory contains standalone diagnostics for the actual
DisconnectJob status and Eclipse job callback behavior. Compile/run these with
the built model classes and built product plugin jars on the classpath.

## Validation of V3

- Full Maven/Tycho product build succeeded.
- Two mode-switch integration tests passed (MySQL and PostgreSQL).
- Successful/cancelled disconnect status diagnostics passed.
- Eclipse callback diagnostic confirmed current-job availability.
- Previous guard validation: 68 tests plus two query-timeout tests passed.
- The complete DBeaver module test suite was not run.
- The separate Windows test package was launched for manual UI testing.

Build the product using the standard aggregate build described in AGENTS.md.
Generated applications, personal workspaces, profiles, and build logs are not
included in this branch.
