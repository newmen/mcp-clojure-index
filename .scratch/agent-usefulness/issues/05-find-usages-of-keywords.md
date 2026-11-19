# 05: `find_usages` of keywords, including EDN

**What to build:** An agent renaming or removing a map key passes keywords (plain, qualified, auto-resolved `::k`) to `find_usages` and gets every place they appear: in Clojure code (kind `keyword`) and in `.edn` files of the project such as resources and configs (kind `edn`). Keywords and Definitions can be mixed in one call.

**Blocked by:** 03 (`find_usages` of Definitions)

**Status:** resolved

- [x] Plain and namespaced keywords are found in code with kind `keyword`; `::k` resolves to its namespace.
- [x] Keywords inside `.edn` files are found with kind `edn` and correct line/column.
- [x] A call mixing keywords and Definition names returns one group per target.
- [x] Spec acceptance case 3 passes on this code base plus a fixture EDN file.
