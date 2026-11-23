## Clojure codebase

To check any Clojure functionality, **always** use the `/clojure-eval` skill. Use this when you need to test code, check if edited files compile, verify function behavior, or interact with a running REPL session.

External tools:
- **parinfer-rust** (optional, recommended) - Faster delimiter repair when on PATH; falls back to parinferish if not available

### AOT and the REPL

Only the `:uberjar` profile AOT-compiles, into `target/uberjar/classes`. Keep AOT output out of `target/classes`: it is on the REPL classpath, and `require` prefers an `__init.class` newer than its `.clj`, so stale classes would shadow edited sources. A checkout built before this layout still has them there: delete `target/classes` (or `lein clean`) once.

## Agent skills

### Issue tracker

Issues and specs are local markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
