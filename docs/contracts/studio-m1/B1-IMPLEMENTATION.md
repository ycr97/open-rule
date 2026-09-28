# B1 Core implementation notes

The D0 OpenAPI declares `StageResult.status` but does not define how a Stage with a
`CONTINUE` node failure is summarized. B1 uses this rule consistently for the Core
result that B2 will map to the API: all nodes skipped means `SKIPPED`; any `FAILED`,
`TIMED_OUT`, or `CANCELLED` node means `FAILED`; otherwise `SUCCEEDED`. The node
results retain their individual statuses. This is a contract clarification for B2
and the front end, not a change to D0's status vocabulary.

B1's Core model has no HTTP fields such as requestId, traceId, revision, purpose,
or checksum. B2 owns those values and the JSON binding/schema gate. B3 owns the
RuleSet, Scorecard, and DecisionTable executors and their typed detail records.
The B1 compiler checks those three node types' identity, order, references,
Terminal placement, and parallel output conflicts using their declared output
keys and conditions; B3 must add their configuration-specific validation.

The existing v1 compiler/runtime and JDBC rows remain unchanged. The v2 Core
path does not read or reinterpret v1 rows.
