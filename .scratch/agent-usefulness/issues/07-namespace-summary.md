# 07: `namespace_summary`

**What to build:** An agent asks what a namespace contains and gets, without reading the file, one line per Definition (public and private marked) with arglists and the first docstring line, followed by the namespaces that require it. No bodies.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** ready-for-agent

- [ ] Lists every Definition of the namespace with visibility, kind, line, arglists and first docstring line.
- [ ] Lists the namespaces that require it.
- [ ] Unknown namespace returns close candidates by name.
- [ ] Obeys the Output budget; tested on a namespace of this code base.
