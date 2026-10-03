<img src="public/assets/stack-cap-bench.svg" alt="" width="96" height="96">

# stack-cap-bench

Source: <https://github.com/sstoehrm/stack-cap-bench> · Code: [MIT](LICENSE) ·
Data: [CC BY 4.0](LICENSE-DATA)

One page that shows what a headless coding agent spends — tokens, dollars,
minutes, attempts, tool calls — building the same projects in different tech
stacks. The numbers come from
[token-comparision](https://github.com/sstoehrm/token-comparision); the page is
built with [hammer](https://github.com/sstoehrm/hammer) and draws its chart by
hand on a canvas.

- **Topics**: runs are grouped by project kind — *Web development* (projects 2
  and 3) and *CLI tools* (project 1). Each topic gets headline tiles and a
  stack × model·effort table.
- **Compare**: cost bars grouped by stack, per topic and project: one bar per
  model and effort level, so an even staircase means cost rises in step with
  the effort. Each stack has its own hue (fixed per topic, validated for
  colour blindness between neighbours); effort is its shade, lighter for
  lower; each model has its own texture (Opus solid, Fable diagonal, GPT-6 Sol
  horizontal, GPT-6 Astra dotted) and a chip to show or hide it.
- **Two modes**, switched at the top right and remembered per browser (or
  forced with `#serious` / `#evil` in the URL):
  - *Serious* (default): functional glass, monospace, light and dark
    (follows the OS, or pick with the theme button).
  - *Evil*: the gothic cathedral and the judgement, per topic: the Holy
    Stacks (best mean cost rank across the topic's configurations) and the
    Diabolical Stacks (worst first), up to three each and never overlapping.
- **Niche stacks** (`data/niche`: the hammer stacks and other small
  frameworks and languages) never appear in serious mode. Evil mode shows
  them once "Admit the niche frameworks", the gate above the judgement, is
  pressed, remembered per browser.
- **Agent**: every run records the agent harness and version that produced it
  (`claude-code 2.1.282`) in `results.json`; the page does not show it.
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

## License

Two licenses, one for each kind of thing in this repository:

| What | License | What you may do |
|------|---------|-----------------|
| **Code**: everything except the data (`src/`, `scripts/`, the stylesheets, `index.html`, build files) | [MIT](LICENSE) | Use, copy, modify and ship it, commercially too. Keep the copyright and license notice in copies of the code. |
| **Data**: `public/results.json` | [CC BY 4.0](LICENSE-DATA) | Anything, commercially too: analyse it, chart it, merge it into your own benchmark, publish or sell the result. Give credit and say if you changed it. |

Both licenses are permissive and ask for one thing: attribution. For the data,
a line like this is enough:

> Data: [Stack Cap Bench](https://github.com/sstoehrm/stack-cap-bench) by
> Sören Stöhrmann, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

Both come without warranty: the numbers are measurements of a few runs, not
guarantees.
