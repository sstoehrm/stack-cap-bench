# Hammer-native page: design

Date: 2026-10-04 · Figure: `.blend/specs/2026-10-04-hammer-native.edn`

## Goal

Rebuild the page's imperative parts on hammer v0.1.0's own features, so the
page shows hammer used as documented (hammer's README links to it) and drops
the plumbing it carries today:

- the chart (`scb.chart`, 595 lines): its own `requestAnimationFrame` loop,
  `ResizeObserver`, `visibilitychange` listener, `:ref` attach, global state,
  a DOM tooltip built by hand, and `core` pushing models into it through the
  `:chart` fx and `restyle!`;
- the evil-mode nave (`scb.nave`): a `canvas#nave` in `index.html` that `core`
  starts and stops, with its own loop and resize, scroll and visibility
  listeners;
- loading `results.json` with `js/fetch`.

## Success criteria

- The page looks and behaves as before in both modes: bars rise when the chart
  first scrolls into view and tween between states; hover and arrow keys pick
  out a stack and show its tooltip; mode, light/dark (OS or button) and web
  fonts restyle the chart; reduced motion snaps instead of tweening.
- No hand-written `requestAnimationFrame`, `ResizeObserver`, `visibilitychange`
  or `:ref` code remains; nothing outside `scb.chart` calls into it.
- `npm test` passes: data, tween and component tests (below).
- `bb release` builds with 0 warnings; the console shows no `hammer:` reports
  in either mode.

Deliberate changes, all small:

- The tooltip is a `defc`. It sits in the top corner away from the hovered
  stack as now, placed by CSS classes rather than measured, so its offset may
  differ by a few pixels.
- The chart's height moves from JS to CSS: `clamp(340px, 52cqi, 540px)` on
  `.canvas-box`, with `.canvas-wrap` as the inline-size container: the numbers
  `resize!` uses today.
- With reduced motion, the rose window no longer follows scrolling (the nave
  loop is paused; it redraws on resize only).

## Architecture

Data flows one way. The db holds what the page shows; the chart and the nave
are components that read it. Nothing pushes into them.

### `scb.core`

- `:init` event returns `{:http {:uri "results.json" :fetch-options {:cache
  "no-cache"} :on-success [:loaded] :on-failure [:failed]}}`; `main` mounts and
  dispatches `[:init]`. `:failed` turns the failure map into the message shown
  today (`results.json: HTTP 404`, or the failure kind).
- Removed: `with-chart`, the `:chart` fx, `load!`, and the chart and nave calls
  in `apply-look!`. `:look` keeps switching stylesheets and `<html>`
  attributes and storing the choice.
- New db keys: `:hover` (stack id or nil) and `:look-rev` (a counter).
  `[:hover id]` sets the hovered stack; events that change what the chart
  shows (`:topic`, `:project`, `:model`, `:brave`, `:mode`) clear it.
  `[:restyle]` bumps `:look-rev`; `main` dispatches it when the OS colour
  scheme changes and when `document.fonts.ready` resolves.
- `app` renders `[nave]` only in evil mode; `index.html` loses `canvas#nave`.

### `scb.chart`: the component

```clojure
(defloop chart []
  [model    (data/chart-model d {...})   ; from [:data] [:topic] [:project] [:models] [:all]
   hover    [:hover]
   look     (vector mode theme rev)      ; [:mode] [:theme] [:look-rev]
   busy     (atom false)                 ; a tween is moving
   revealed (atom false)                 ; scrolled into view once
   st       (volatile! (tween/state))    ; tweens, layout, theme
   handlers ...]                         ; pointer/key fns, built once from st
  {:run? @busy :attrs {...} :init observe-reveal :dispose disconnect
   :on-pointermove ... :on-pointerleave ... :on-keydown ... :on-blur ...}
  (fn [ctx info io] ...))
```

- **Draw fn**, per frame: re-reads the theme from CSS when `look` differs from
  the last one drawn; once `@revealed`, places `model` when it is not
  identical to the last one placed (snapping on resize or reduced motion); then
  paints. When everything has settled it resets `busy` to false, which stops
  the loop; placing a new model sets it to true.
- **Reveal**: `:init` observes the canvas with an `IntersectionObserver`
  (threshold 0.25) that sets `revealed`; without one (jsdom, old browsers) it
  sets it at once. `:dispose` disconnects.
- **Hover**: the handlers hit-test against the layout in `st` and dispatch
  `[:hover id]` only when the stack under the pointer changes; arrow keys step
  through the stacks, Escape and blur clear it. The handler fns are a binding
  computed once, so the opts stay `=` between renders.
- **`chart-tip`**, a `defc` reading `[:hover]` and the model: the hovered
  stack's runs per model and effort, as the tooltip shows today. Class
  `at-left` or `at-right`: left when the stack lies in the right half (its
  index ≥ half the stack count).
- **Sizing**: no `:size`; the canvas fills `.canvas-box`, whose height CSS
  sets. Narrow layouts (< 560px) keep their smaller margins, read from `w`.

### `scb.chart.tween`

The layout and tween logic from today's `chart.cljs`, no DOM, every function
taking the state object instead of reading module globals: `state`,
`nice-ticks`, `layout`, `fit-margins!` (text widths from a measure fn),
`place!`, `busy?`, `tween-val`, `hit`. Behaviour unchanged.

### `scb.chart.paint`

The drawing from today's `chart.cljs`, unchanged: colours (`oklch`, `mix`,
`bar-color`), `texture!`, lancet and plain bars, legend, and `frame!`, which
paints one frame from the state at time `t`. `read-theme` reads the CSS
variables into the state's theme.

### `scb.nave`

```clojure
(defloop nave []
  [reduced (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
   st      (volatile! nil)]                ; cached stone, rose, dust, shards
  {:run? (not reduced) :attrs {:id "nave" :aria-hidden "true"}}
  (fn [ctx {:keys [w h dpr t]}] ...))
```

Rebuilds its cached layers when `w`, `h` or `dpr` change, then composites a
frame at `t` as today. It now sits inside `#app` (`z-index: 1`), so `#nave`
gets `z-index: -1` to stay under the page; serious mode's `#nave` rule goes.
`start!`, `stop!` and the listeners go.

## Error handling

hammer reports failing draws, handlers and fxs and keeps the page running. A
failed load shows the existing "Could not load results.json" state. No new
error paths.

## Testing

A shadow-cljs `:node-test` build (`test/`, `-test$` namespaces) with `jsdom`
(30.1.2, an exact pin as hammer does) as an npm dev dependency; `npm test` and
`bb test` run it.

- `scb.test-env`: jsdom globals, a recording fake 2d context (a `Proxy`:
  every method records its call, `measureText` returns a width,
  `createLinearGradient` returns a stub), and `clientWidth`/`clientHeight`
  stubs so auto-sized canvases have a size.
- `scb.data-test`: `table`, `standings`, `chart-model` over a small fixture.
- `scb.chart.tween-test`: `nice-ticks`, `layout` widths, `place!` retargeting
  from the current value, `busy?` settling after the tween duration, `hit`.
- `scb.chart-test` (`hammer.testing`, fake frames): the chart draws once
  revealed; a model change sets `busy` and the loop stops after the tween
  settles; `[:hover id]` renders the tooltip for that stack; a topic change
  clears the hover.
- `scb.core-test`: `[:init]` with `hammer.http/set-fetch!` stubbed renders the
  tables; a 404 shows the error state.

## Out of scope

`scb.data` aggregation, the stylesheets beyond the chart box height and the
tooltip classes, the import script, the page's text.
