# 08: `get_source`

**What to build:** An agent that needs a body asks for exactly one Definition by name and gets its source with a `path:start-end` header, so it never has to read a whole file to see one function. Name resolution is the same as in `find_definition`; an ambiguous name returns candidates, not several bodies.

**Blocked by:** 02 (clj-kondo index and `find_definition`)

**Status:** ready-for-agent

- [ ] Returns the source of one Definition with its line range header.
- [ ] Ambiguous name returns candidates only.
- [ ] A body larger than `:output/max-chars` is cut with a Truncation note giving the line range that was not shown.
- [ ] Tested on this code base.
