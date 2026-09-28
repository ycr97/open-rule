# Studio B2 local host

The host assembles the existing OSS Core, Spring, API, and JDBC modules. It does
not contain a second decision engine. The v2 tables are independent of legacy
`or_flow`; startup applies the idempotent initial SQL in
`openrule-jdbc/src/main/resources/db/studio/V1__studio_tables.sql` after the
legacy `schema.sql`. For an existing database, run the same Studio SQL once
before starting the host; it creates new tables and does not rewrite legacy rows.

```sh
mysql -h 127.0.0.1 -u openrule -p openrule \
  < openrule-jdbc/src/main/resources/db/studio/V1__studio_tables.sql
```

## Build and start

Requires JDK 21 and MySQL 8. Create database `openrule` and a user with table
creation/read/write privileges, then run:

```sh
cd /Users/ycr/IdeaProjects/Sandbox/open-rule
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home mvn clean package
STUDIO_JDBC_URL='jdbc:mysql://127.0.0.1:3306/openrule?serverTimezone=UTC' \
STUDIO_DB_USER=openrule STUDIO_DB_PASSWORD='YOUR_PASSWORD' PORT=8080 \
  /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java \
  -jar openrule-studio-server/target/openrule-studio-server-1.0.0-SNAPSHOT.jar
```

The host returns bare `/api/v2` DTOs. `schema.sql` is retained for the legacy
API; v2 saves and publishes never change its `enabled` pointer. This local host
does not settle ADR-0005's policy question about the production lifetime of
the legacy engine. Configure operator identity/authentication before an external
deployment.

## Minimal HTTP cycle

```sh
API=http://127.0.0.1:8080/api/v2
curl -sS -X POST "$API/admin/flows" -H 'Content-Type: application/json' \
  -d '{"flowId":"demo_flow","flowName":"Demo"}' > /tmp/studio-flow.json

# Fill the initial Terminal before saving. jq preserves the contract's string
# version/revision; for arbitrary-precision business numbers use a lossless
# JSON editor rather than a JavaScript Number round trip.
jq '.document | .stages[0].nodes[0].config.decisionCode="APPROVE" |
  .stages[0].nodes[0].config.reasonCodes=["OK"]' \
  /tmp/studio-flow.json > /tmp/studio-document.json
jq -n --slurpfile d /tmp/studio-document.json \
  '{expectedRevision:"1",document:$d[0]}' > /tmp/studio-save.json
curl -sS -X PUT "$API/admin/flows/demo_flow/versions/1" \
  -H 'Content-Type: application/json' --data-binary @/tmp/studio-save.json

curl -sS -X POST "$API/admin/flows/demo_flow/versions/1/simulations" \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":"2","requestId":"r1","bizId":"b1","facts":{},"timeoutMillis":1000}'
curl -sS -X POST "$API/admin/flows/demo_flow/versions/1/publish" \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":"2","changeNote":"reviewed"}'
curl -sS -X POST "$API/flows/demo_flow/versions/1/executions" \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"r2","bizId":"b1","facts":{},"timeoutMillis":1000}'
```

GET `/api/v2/admin/flows`, GET
`/api/v2/admin/flows/{flowId}/versions`, and GET
`/api/v2/admin/flows/{flowId}/versions/{version}` provide paged discovery and
exact-version reads. Save and publish require the current `expectedRevision`;
on 409, GET the resource and retain the local edit rather than retrying the
write blindly.

## Verification

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn clean verify -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
DOCKER_HOST=unix:///Users/ycr/.orbstack/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn -Pintegration -pl openrule-jdbc -am verify -Dapi.version=1.43 \
  -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
./scripts/validate-studio-m1.sh
```

The Java agent, OrbStack socket, Docker API override, and disabled Ryuk are
specific to the local test environment. The cached `mysql:8.0` image permits
integration tests without pulling a new image.

The B3 runtime executes RuleSet, Scorecard, and DecisionTable definitions,
including the D0 order-admission fixture. See
`docs/contracts/studio-m1/B3-IMPLEMENTATION.md` for the complete order API
cycle. Provider, Scene, and production order-admission policy remain outside
this host.
