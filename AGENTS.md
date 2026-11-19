## Clojure codebase

To check any Clojure functionality, **always** use the `/clojure-eval` skill. Use this when you need to test code, check if edited files compile, verify function behavior, or interact with a running REPL session.

External tools:
- **parinfer-rust** (optional, recommended) - Faster delimiter repair when on PATH; falls back to parinferish if not available

## Agent skills

### Issue tracker

Issues and specs are local markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
