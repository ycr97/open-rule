# B3 implementation and handoff

B3 replaces B2's temporary three-executor capability rejection. The wire
contract remains D0 OpenAPI/Schema; no frontend DTO change is required. Core
binds and validates typed RuleSet, Scorecard, and DecisionTable configurations,
executes them in the B1 stage engine, and returns ordered typed `RuleDetail`
and `ScoreDetail` entries. RuleSet supports `FIRST_MATCH` and `ALL_MATCH`;
DecisionTable supports `FIRST`. A miss yields `hit=false`, `details=[]`, and
`outputKey: null`. Scorecard requires one bin per characteristic, sums with
`BigDecimal`, and fails the node without outputs on missing, overlapping, or
non-matching runtime input. Simple numeric interval overlap and gaps are
rejected at compile time; more complex predicates retain the runtime guard.
The B1 parallel snapshot, output collision, timeout, ABORT, and CONTINUE
handling is reused unchanged.

## Verification performed on 2026-09-28

Run from repository root with JDK 21:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  mvn clean verify -q -o \
  -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
DOCKER_HOST=unix:///Users/ycr/.orbstack/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
  mvn -Pintegration -pl openrule-jdbc -am verify -q -Dapi.version=1.43 \
  -DargLine=-javaagent:/Users/ycr/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar
./scripts/validate-studio-m1.sh
git diff --check
```

The full reactor and MySQL Testcontainers integration suite passed. The
fixture test executes all 19 D0 OA input/expected pairs through Java Core.
MockMvc executes all 19 OA cases through B2's API with a fake Store,
including variant publication and exact-version LIVE calls. A separate boot-jar
smoke test against a temporary MySQL 8 container passed real HTTP save,
validate, simulate, publish, LIVE v1 (APPROVE/15 and REVIEW/75), then new
draft/publish/LIVE v2 (APPROVE/35); v1 still returned 75. The temporary host
and container were stopped after the check. API and actual MySQL evidence are
reported separately.

OA-01 through OA-12 (including lettered boundary cases) have Java fixture
coverage. OA-01, OA-02, and OA-03 also have real HTTP/MySQL smoke evidence.
ST-07 and ST-08 retain B1 compiler/runtime tests; ST-09 has B3 static and
runtime overlap/no-match tests plus OA bin boundaries; ST-10 and ST-12 retain
B2 API/MySQL tests; ST-11 has B3 decimal sum and OA-05b precision evidence.
ST-13 retains B1 deadline tests. Frontend states, screenshots, and full M1
acceptance remain for F2/F3/V1.

## Complete order API cycle

Start the host as in `openrule-studio-server/README.md`. This example inserts
the fixture document as raw JSON so a client-side binary float conversion does
not alter Definition numbers. Use a new database or a new flowId for a repeat
run; the fixture's identity is `order_admission`.

```sh
API=http://127.0.0.1:8080/api/v2
FIX=docs/contracts/studio-m1/fixtures/valid
curl -sS -X POST "$API/admin/flows" -H 'Content-Type: application/json' \
  -d '{"flowId":"order_admission","flowName":"订单准入决策"}'
{ printf '{"expectedRevision":"1","document":'; cat "$FIX/order-admission.definition.json"; printf '}'; } \
  > /tmp/order-save.json
curl -sS -X PUT "$API/admin/flows/order_admission/versions/1" \
  -H 'Content-Type: application/json' --data-binary @/tmp/order-save.json
{ printf '{"document":'; cat "$FIX/order-admission.definition.json"; printf '}'; } \
  > /tmp/order-validate.json
curl -sS -X POST "$API/admin/flows/order_admission/versions/1/validations" \
  -H 'Content-Type: application/json' --data-binary @/tmp/order-validate.json
curl -sS -X POST "$API/admin/flows/order_admission/versions/1/publish" \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":"2","changeNote":"订单准入 D0 定义"}'
curl -sS -X POST "$API/flows/order_admission/versions/1/executions" \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"oa01","bizId":"order-1","facts":{"order":{"amount":680,"country":"JP"},"buyer":{"ageDays":365},"metrics":{"refundRate":0.08},"risk":{"blacklisted":false}},"timeoutMillis":3000}'
```

The LIVE response has `decisionCode=APPROVE`, `score="15"`, `segment="LOW"`,
and ordered `nodeResults.details`. To test OA-03, create a draft from version
`1`, save `order-admission-score-35.definition.json` with its document
`version` set to `"2"`, publish revision `2`, then call `/flows/order_admission/versions/2/executions`
with OA-03 facts. Use GET on the version resource to inspect the persisted
revision and checksum before retrying a write.

The frontend may now integrate all five built-in nodes with validation,
simulation, publish, and exact-version LIVE against the real API. It must
preserve decimal JSON literals and distinguish missing from explicit null.
Provider and Scene remain out of scope. ADR-0005 remains Proposed: the legacy
runtime lifetime and production order-admission admission policy are not
settled by B3. The existing v1 persistence compatibility promise remains in
force.
