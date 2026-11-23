"""Share of a Claude Code session's input tokens that code navigation costs (ADR-0009).

Run on the transcript directories of the measured projects:

    python3 dev/benchmark/session_share.py ~/.claude/projects/-home-me-projects-app

Every API request re-reads the whole context, so a tool result costs its size
once per later request in the same context (until /clear or compaction).
Navigation is what the server can answer instead: Grep, Glob, read-only shell
searches (rg, grep, cat, sed -n, head, ...), Read of a Clojure file the session
does not edit afterwards (Edit needs a Read first), and the code-index tools.
Sessions under 200k input tokens or 10 requests are left out.
"""
import json, re, sys, glob, os, statistics

CHARS_PER_TOKEN = 3.7
NAV_BASH = re.compile(r"^\s*(cd [^&;]+(&&|;)\s*)?(rg|grep|cat|sed -n|head|tail|find|ls|wc|awk)\b")


def text_len(content):
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        return sum(len(c.get("text", "")) if isinstance(c, dict) else len(str(c)) for c in content)
    return 0


CLOJURE = re.compile(r"\.(clj|cljc|cljs|edn)$")


def nav_kind(name, inp):
    if name == "Read":
        return ("read", inp.get("file_path", "")) if CLOJURE.search(inp.get("file_path", "")) else None
    if name in ("Grep", "Glob"):
        return "base"
    if name.startswith("mcp__clojure-code-index__"):
        return "mcp"
    if name == "Bash" and NAV_BASH.match(inp.get("command", "")):
        return "base"
    return None


def analyse(path):
    tools = {}          # tool_use_id -> kind
    requests = []       # per API request: total input tokens
    seen = set()
    nav_cost = {"base": 0.0, "mcp": 0.0}
    nav_items = []      # (kind, tokens, request index at arrival, context id)
    context = 0
    edited = set()
    for line in open(path):
        try:
            d = json.loads(line)
        except ValueError:
            continue
        t = d.get("type")
        if t == "system" and d.get("subtype") == "compact_boundary":
            context += 1
        msg = d.get("message") or {}
        if t == "user" and isinstance(msg.get("content"), str) and "<command-name>/clear" in msg["content"]:
            context += 1
        if t == "assistant":
            mid = msg.get("id")
            u = msg.get("usage") or {}
            if mid and mid not in seen and u:
                seen.add(mid)
                total = u.get("input_tokens", 0) + u.get("cache_read_input_tokens", 0) + u.get("cache_creation_input_tokens", 0)
                requests.append((context, total))
            for c in msg.get("content") or []:
                if isinstance(c, dict) and c.get("type") == "tool_use":
                    tools[c["id"]] = nav_kind(c.get("name", ""), c.get("input") or {})
                    if c.get("name") in ("Edit", "Write", "MultiEdit"):
                        edited.add((context, (c.get("input") or {}).get("file_path")))
        if t == "user" and isinstance(msg.get("content"), list):
            for c in msg["content"]:
                if isinstance(c, dict) and c.get("type") == "tool_result":
                    kind = tools.get(c.get("tool_use_id"))
                    if kind:
                        nav_items.append((kind, text_len(c.get("content")) / CHARS_PER_TOKEN, len(requests), context))
    total_input = sum(r for _c, r in requests)
    for kind, tokens, at, ctx in nav_items:
        if isinstance(kind, tuple):
            if (ctx, kind[1]) in edited:
                continue
            kind = "base"
        later = sum(1 for c, _r in requests[at:] if c == ctx)
        nav_cost[kind] += tokens * later
    return total_input, nav_cost, len(requests)


def main(dirs):
    rows = []
    for d in dirs:
        for path in glob.glob(os.path.join(d, "**", "*.jsonl"), recursive=True):
            total, nav, n = analyse(path)
            if total < 200_000 or n < 10:
                continue
            rows.append((path, total, nav, n))
    for label, pick in (("without MCP", lambda nav: nav["mcp"] == 0),
                        ("with MCP", lambda nav: nav["mcp"] > 0)):
        group = [r for r in rows if pick(r[2])]
        if not group:
            continue
        shares = [(r[2]["base"] + r[2]["mcp"]) / r[1] for r in group]
        total = sum(r[1] for r in group)
        nav = sum(r[2]["base"] + r[2]["mcp"] for r in group)
        mcp = sum(r[2]["mcp"] for r in group)
        print(f"{label}: {len(group)} sessions, {total/1e6:.0f}M input tokens, "
              f"navigation share overall {100*nav/total:.0f}%, median {100*statistics.median(shares):.0f}%, "
              f"quartiles {100*statistics.quantiles(shares, n=4)[0]:.0f}-{100*statistics.quantiles(shares, n=4)[2]:.0f}%, "
              f"of which MCP {100*mcp/max(nav,1):.0f}%")


main(sys.argv[1:])
