# 07: Project config in the server instructions and README

**What to build:** An agent learns from the server's `initialize` instructions, in two or three lines, that `.clojure-index.edn` at the project root overrides the server config, that edits apply from the next answer (connection settings after a restart), with one example of a Search exclusion on a generic generated-code path (e.g. a `com/google/protobuf` tree, never a path from a real user project). The README describes the Project config in full. See spec `.scratch/agent-feedback-round-3/spec.md`, section Server instructions and README; ADR-0010.

**Blocked by:** 04, 05, 06

**Status:** resolved

- [x] The `initialize` instructions carry the two or three lines with the generic example; the existing server test is extended to check them.
- [x] README (and README.RU) describes location, allowed keys, hot and cold keys, replacement semantics, error and restart lines, and a full example.
- [x] Nothing in the instructions or README names the user's other projects.
