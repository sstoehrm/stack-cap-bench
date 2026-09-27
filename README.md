# stack-cap-bench

One page that shows what a headless coding agent spends — tokens, dollars,
minutes, attempts, tool calls — building the same projects in different tech
stacks. The numbers come from
[token-comparision](https://github.com/sstoehrm/token-comparision); the page is
built with [hammer](https://github.com/sstoehrm/hammer) and draws its chart by
hand on a canvas.

- **Topics**: runs are grouped by project kind — *Web development* (projects 2
  and 3) and *CLI tools* (project 1). Each topic gets headline tiles and a
  stack × model·effort table.
- **Compare**: one chart for every dimension — topic, project, measure, and
  which model·effort configurations to show.
- **Two modes**, switched at the top right and remembered per browser (or
  forced with `#serious` / `#evil` in the URL):
  - *Serious* (default): functional glass, monospace, light and dark
    (follows the OS, or pick with the theme button).
  - *Evil*: the gothic cathedral, the Creed's unserious tab, and the Lord's
    Stack of the Month — the stack with the best mean cost rank across every
    configuration — with its Heretic.
- **Agent**: every run records the agent harness and version that produced it
  (`claude-code 2.1.282`), shown in the header and the chart tooltips.
- Every value is the mean over **complete** runs (every step passed), per
  project first, then across the topic's projects. Stack colours are fixed,
  never by rank.

## Data

`public/results.json` is the only input. The page fetches it at runtime, so
new data needs no rebuild. Regenerate it from a token-comparision checkout:

```sh
bb import ../token-comparision
```

or edit it by hand:

```json
{
  "source": "token-comparision@b5564fe",
  "imported": "2026-09-26",
  "topics":   [{"id": "web", "label": "Web development", "projects": [2, 3]}],
  "projects": [{"id": 2, "name": "spendly", "kind": "web", "steps": 5}],
  "runs": [{"project": 2, "stack": "svelte-java", "run_id": "20260925-061616",
            "model": "claude-opus-5-5", "effort": "high", "agent": "claude-code 2.1.282",
            "input_tokens": 3294556, "output_tokens": 53614, "cost_usd": 3.2,
            "wall_min": 9.9, "attempts": 5, "tool_calls": 79,
            "steps_passed": 5, "complete": true}]
}
```

`scripts/import.clj` copies the per-run summary rules from token-comparision's
`harness/src/harness/report.clj` (`run-summaries`), so the numbers match its
reports. Change both together.

## Build

Needs Node, Java and the Clojure CLI. hammer is a git dependency in `deps.edn`,
pinned to a commit on its `perf2/slim` branch (compiled templates, delegated
events).

```sh
npm install
bb dev       # watch build on http://localhost:8290
bb release   # optimized build into public/js
bb serve     # serve public/ on http://localhost:8290
```

`public/` is the whole site after `bb release`.
