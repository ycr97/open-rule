# B2 implementation boundary

The D0 schemas admit all five built-in node types, while B2 precedes the B3
executors for RuleSet, Scorecard, and DecisionTable. A draft containing those
types can be structurally saved and read. B2 validation reports
`OR-DEF-VALIDATION` with the Stage and Node IDs saying the executor is not yet
available; publish, SIMULATE, and LIVE reject that definition. This prevents a
published Flow that the installed runtime cannot execute. B3 must remove this
temporary capability check when its executors and configuration-specific
compiler checks are installed. The five-type JSON format remains the D0 format;
there is no B2-specific wire schema.

The Studio lifecycle uses `or_studio_flow` and `or_studio_version`, separate from
the legacy `or_flow` table. Studio save never changes legacy `enabled`. The
initial idempotent SQL migration creates only new tables. Previously stored v1
rows retain raw JSON and their old raw-byte SHA-256 checksum; legacy reads now
verify that checksum before decoding. The policy questions in ADR-0005 about
the legacy runtime lifetime and production order-admission policy remain open.
