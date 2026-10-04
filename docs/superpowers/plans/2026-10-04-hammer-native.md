# Hammer-native page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> Task graph: `.blend/specs/2026-10-04-hammer-native-tasks.edn`. Set each task's `:state` alongside its checkbox: `:in-progress` when you start it, `:done` once its review is clean, `:blocked` + `:reason` when stuck.

**Goal:** Move the chart, the evil-mode nave and the `results.json` load onto hammer v0.1.0's `defloop` and `:http`, with tests.

**Architecture:** The db holds what the page shows; `scb.chart/chart` and `scb.nave/nave` are `hammer.canvas/defloop` components reading it. The chart's layout and tween logic moves to `scb.chart.tween` (no DOM), its drawing to `scb.chart.paint`. The tooltip is a `defc`. Loading is an `:http` effect.

**Tech Stack:** ClojureScript, shadow-cljs 3.5.3, hammer v0.1.0 (`hammer.core`, `hammer.canvas`, `hammer.http`, `hammer.testing`), jsdom 30.1.2 for node tests.

**Spec:** `docs/superpowers/specs/2026-10-04-hammer-native-design.md`

## Global Constraints

- hammer stays pinned at `{:git/tag "v0.1.0" :git/sha "35d7ed7"}`; no other runtime dependency.
- jsdom `30.1.2`, exact pin, dev dependency only.
- Chart box height: `clamp(340px, 52% of the box width, 540px)`, as `resize!` computes today.
- `bb release`: 0 warnings. Browser console: no `hammer:` reports in serious or evil mode.
- No hand-written `requestAnimationFrame`, `ResizeObserver`, `visibilitychange` listener or `:ref` remains in `src/`.
- Match the code style around it: terse docstrings, `;;` comments only where the why is not obvious.

## Review Focus

1. A narrow viewport (< 560px) keeps the smaller margins (46 left, 6 right) → `tween-test/narrow-margins` (Task 2).
2. Switching mode or theme while bars are still moving: the tween finishes and the loop stops → `chart-test/restyle-mid-tween-settles` (Task 3).
3. A hovered stack that a filter then removes: no tooltip, no error → `chart-test/stale-hover-shows-no-tip` (Task 3).
4. Models changing before the chart was ever in view: nothing animates off screen, and the first reveal shows the latest model → `chart-test/reveal-places-latest-model` (Task 3).
5. A network failure (not an HTTP status) while loading → the error state names it → `core-test/network-failure-shows-error` (Task 4).

---

### Task 1: Node test harness and data tests

**Files:**
- Modify: `package.json`, `shadow-cljs.edn`, `deps.edn`, `bb.edn`, `.gitignore`
- Create: `test/scb/test_env.cljs`, `test/scb/fixture.cljs`, `test/scb/data_test.cljs`

**Interfaces:**
- Produces: `scb.test-env` (require first; installs jsdom globals, `matchMedia`, `getComputedStyle`, canvas `clientWidth` 800 / `clientHeight` 400, and a recording fake 2d context); `scb.test-env/ops` → vector of `[op & args]` recorded since `(reset! scb.test-env/log [])`; `scb.fixture/results` → a results.json map.

- [x] **Step 1: Wire the test build**

`package.json` devDependencies gain `"jsdom": "30.1.1"`, and scripts gain `"test": "shadow-cljs compile test && node target/test.js"`. Run `npm install --save-exact --save-dev jsdom@30.1.1`.

`deps.edn`: `:paths ["src" "test"]`.

`shadow-cljs.edn` builds gain:

```clojure
:test {:target :node-test
       :output-to "target/test.js"
       :ns-regexp "-test$"}
```

`bb.edn` tasks gain:

```clojure
test    {:doc "Run the node tests (jsdom)"
         :task (shell "npm test")}
```

`.gitignore` gains `target/`.

- [x] **Step 2: Test environment**

`test/scb/test_env.cljs`:

```clojure
(ns scb.test-env
  "Required first by every test namespace: jsdom globals, the browser APIs
   the page uses, and a recording fake 2d context (jsdom has none)."
  (:require ["jsdom" :refer [JSDOM]]))

(defonce log (atom []))

(defn ops "Recorded 2d calls, [op & args], since the last reset." [] @log)

(defn- fake-2d [^js canvas]
  (js/Proxy.
   #js {:canvas canvas}
   #js {:get (fn [^js t k]
               (cond
                 (or (not (string? k)) (js-in k t)) (aget t k)
                 (= k "measureText") (fn [s] #js {:width (* 7 (count s))})
                 (#{"createLinearGradient" "createRadialGradient"} k) (fn [& _] #js {:addColorStop (fn [& _])})
                 :else (fn [& args] (swap! log conj (into [(keyword k)] args)) nil)))}))

(defonce env
  (let [d (JSDOM. "<!DOCTYPE html><html><body></body></html>" #js {:url "http://localhost/"})
        w (.-window d)
        proto (.. w -HTMLCanvasElement -prototype)]
    (set! js/globalThis.window w)
    (set! js/globalThis.document (.-document w))
    (set! js/globalThis.localStorage (.-localStorage w))
    (set! js/globalThis.location (.-location w))
    (set! js/globalThis.getComputedStyle (.bind (.-getComputedStyle w) w))
    (set! js/globalThis.matchMedia (fn [_] #js {:matches false :addEventListener (fn [& _])}))
    (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16)))
    (set! (.-getContext proto)
          (fn [kind] (this-as ^js c (when (= kind "2d") (or (.-__fake c) (set! (.-__fake c) (fake-2d c)))))))
    (js/Object.defineProperty proto "clientWidth" #js {:get (fn [] 800)})
    (js/Object.defineProperty proto "clientHeight" #js {:get (fn [] 400)})
    d))
```

- [x] **Step 3: Fixture**

`test/scb/fixture.cljs`:

```clojure
(ns scb.fixture "A small results.json: two topics, four stacks, two models.")

(defn- run [project stack model effort cost & [complete]]
  {:project project :stack stack :run_id (str stack "-" cost) :model model :effort effort
   :agent "claude-code 2.1.282" :input_tokens 1000 :output_tokens 100 :cost_usd cost
   :wall_min 10 :attempts 5 :tool_calls 50 :steps_passed 5 :complete (not= complete :incomplete)})

(def results
  {:source "fixture" :imported "2026-10-04"
   :topics [{:id "web" :label "Web development" :projects [2 3]}
            {:id "cli" :label "CLI tools" :projects [1]}]
   :projects [{:id 1 :name "logan" :kind "cli" :steps 5}
              {:id 2 :name "spendly" :kind "web" :steps 5}
              {:id 3 :name "kanban" :kind "web" :steps 5}]
   :runs [(run 2 "svelte-java" "claude-opus-5-5" "high" 3.0)
          (run 2 "svelte-java" "claude-opus-5-5" "high" 5.0)
          (run 3 "svelte-java" "claude-opus-5-5" "high" 2.0)
          (run 2 "react-go" "claude-opus-5-5" "high" 6.0)
          (run 3 "react-go" "claude-opus-5-5" "high" 1.0 :incomplete)
          (run 2 "svelte-java" "claude-fable-5-1" "high" 1.0)
          (run 2 "react-go" "claude-fable-5-1" "high" 2.0)
          (run 1 "go" "claude-opus-5-5" "high" 1.0)
          (run 1 "rust" "claude-opus-5-5" "high" 2.0)
          (run 1 "rust" "claude-opus-5-5" "low" 0.5)]})
```

- [x] **Step 4: Data tests**

`test/scb/data_test.cljs`:

```clojure
(ns scb.data-test
  (:require [cljs.test :refer [deftest is]]
            [scb.data :as data]
            [scb.fixture :refer [results]]))

(def opus-high "claude-opus-5-5|high")
(def fable-high "claude-fable-5-1|high")

(deftest table-means-per-project-then-across
  (let [{:keys [stacks configs cells best]} (data/table results "web" "cost_usd")]
    (is (= ["svelte-java" "react-go"] stacks) "stack order, not alphabetical")
    (is (= [opus-high fable-high] (mapv :key configs)) "model order")
    (is (= 3 (:value (get cells ["svelte-java" opus-high]))) "p2 mean 4, p3 2 → 3")
    (is (= [2 2] (:coverage (get cells ["svelte-java" opus-high]))))
    (is (= 6 (:value (get cells ["react-go" opus-high]))) "the incomplete p3 run is left out")
    (is (= [1 2] (:coverage (get cells ["react-go" opus-high]))))
    (is (= {opus-high 3 fable-high 1} best))))

(deftest standings-and-leanest
  (let [tbl (data/table results "web" "cost_usd")]
    (is (= [{:stack "svelte-java" :mean-rank 0 :configs 2 :wins 2 :losses 0}
            {:stack "react-go" :mean-rank 1 :configs 2 :wins 0 :losses 2}]
           (data/standings [tbl])))
    (is (= "svelte-java" (data/leanest tbl)))))

(deftest topic-totals-count-every-run
  (is (= {:runs 7 :complete 6 :cost 20.0 :tokens 7700 :stacks 2}
         (data/topic-totals results "web"))))

(deftest chart-model-groups-bars-by-stack-and-model
  (let [m (data/chart-model results {:topic "web" :project "all" :models nil :all results})
        [sj rg] (:stacks m)]
    (is (= ["svelte-java" "react-go"] (mapv :id (:stacks m))))
    (is (= [0 1] [(-> sj :series first :bars first :slot) (-> rg :series first :bars first :slot)]))
    (is (= ["Opus 5.5" "Fable 5.1"] (mapv :model (:series sj))))
    (is (= [0 1] (mapv :style (:series sj))) "a model's texture is its place among all models")
    (is (= 3 (-> sj :series first :bars first :value)))
    (is (= ["high"] (mapv :effort (:efforts m))))))

(deftest chart-model-project-filter-and-hidden-models
  (let [m (data/chart-model results {:topic "web" :project "3" :models #{"claude-opus-5-5"} :all results})]
    (is (= ["svelte-java"] (mapv :id (:stacks m))) "react-go has no complete run in project 3")
    (is (= ["Opus 5.5"] (mapv :model (-> m :stacks first :series))))
    (is (= 2 (-> m :stacks first :series first :bars first :value)))))
```

- [x] **Step 5: Run the tests**

Run: `npm test`
Expected: `Ran 5 tests containing 19 assertions. 0 failures, 0 errors.` (pure functions that already exist; if one fails, the fixture arithmetic in the test is wrong, not `scb.data`: recheck by hand before changing anything).

- [x] **Step 6: Commit**

```bash
git add package.json package-lock.json shadow-cljs.edn deps.edn bb.edn .gitignore test/
git commit -m "Node tests with jsdom; data aggregation tests"
```

---

### Task 2: `scb.chart.tween`

**Files:**
- Create: `src/scb/chart/tween.cljs`, `test/scb/chart/tween_test.cljs`
- (`src/scb/chart.cljs` keeps working untouched until Task 3.)

**Interfaces:**
- Produces (all on one mutable state object `s` from `(state)`; times `t` in ms):
  - `(state)` → `#js {:w :h :lbase :margin #js {:l :r :t :b} :model :bars (Map) :labels (Map) :order :slot :ymax :reduced :hover :look :theme}`
  - `dur` (850), `(tween-val tw t)`, `(settled? tw t)`, `(nice-ticks mx)` → vector
  - `(size! s w h)`, `(layout s model)` → `{:slot :centres {id x} :bars {id {:x :w}}}`
  - `(place! s model move? t text-w)` where `text-w` is `(fn [label] px)`
  - `(busy? s t)` → truthy while a tween moves; `(hit s x y)` → stack id or nil; `(stack s id)` → the model's stack map

- [x] **Step 1: Write the failing tests**

`test/scb/chart/tween_test.cljs`:

```clojure
(ns scb.chart.tween-test
  (:require [cljs.test :refer [deftest is testing]]
            [scb.chart.tween :as tw]))

(defn- bar [id v] {:id id :stack (subs id 0 1) :slot 0 :rank 3 :style 0 :effort "high" :value v})
(defn- model [& stacks]
  {:models [] :efforts []
   :stacks (vec (for [[id bars] stacks] {:id id :label id :series [{:model "M" :style 0 :bars bars}]}))})

(def m1 (model ["a" [(bar "a1" 2)]] ["b" [(bar "b1" 4) (bar "b2" 1)]]))
(def text-w (constantly 20))

(defn- placed [m t]
  (doto (tw/state) (tw/size! 800 400) (tw/place! m true t text-w)))

(defn- v [^js s id t] (tw/tween-val (.-v (.get (.-bars s) id)) t))

(deftest nice-ticks
  (is (= [0 1] (tw/nice-ticks 0)))
  (is (= [0 1 2 3 4] (tw/nice-ticks 3.2)))
  (is (= [0 2 4 6 8] (tw/nice-ticks 7))))

(deftest layout-one-slot-per-stack
  (let [s (doto (tw/state) (tw/size! 800 400))
        {:keys [slot centres bars]} (tw/layout s m1)]
    (is (= 357 slot) "(800 - 68 - 18) / 2")
    (is (= 246.5 (centres "a")))
    (is (= {:x 239.5 :w 14} (bars "a1")) "16px bars less a 2px gap, centred on the slot")))

(deftest narrow-margins
  (let [^js s (doto (tw/state) (tw/size! 500 340))]
    (is (= [46 6] [(.. s -margin -l) (.. s -margin -r)]))
    (tw/size! s 900 468)
    (is (= [68 18] [(.. s -margin -l) (.. s -margin -r)]))))

(deftest bars-rise-staggered-and-settle
  (let [s (placed m1 0)]
    (is (= 0 (v s "b1" 0)))
    (is (= 4 (v s "b1" (+ 24 tw/dur))) "second stack starts 24ms later")
    (is (tw/busy? s 800))
    (is (not (tw/busy? s 900)))))

(deftest retarget-starts-where-the-bar-is
  (let [s (placed m1 0)
        mid (v s "b1" 437)]
    (tw/place! s (model ["a" [(bar "a1" 2)]] ["b" [(bar "b1" 8) (bar "b2" 1)]]) true 437 text-w)
    (is (= mid (v s "b1" 437)) "no jump")
    (is (= 8 (v s "b1" (+ 437 24 tw/dur))))))

(deftest vanished-bars-fall-and-fade
  (let [^js s (placed m1 0)]
    (tw/place! s (model ["a" [(bar "a1" 2)]]) true 1000 text-w)
    (is (= 0 (v s "b1" (+ 1000 tw/dur))))
    (is (= 0 (.. (.get (.-labels s) "b") -a -to)))))

(deftest reduced-motion-snaps
  (let [^js s (doto (tw/state) (tw/size! 800 400))]
    (set! (.-reduced s) true)
    (tw/place! s m1 true 0 text-w)
    (is (= 4 (v s "b1" 0)))))

(deftest hit-finds-the-stack-under-the-pointer
  (let [s (placed m1 0)]
    (testing "slots left to right" (is (= ["a" "b"] [(tw/hit s 100 200) (tw/hit s 500 200)])))
    (testing "outside the plot" (is (= [nil nil] [(tw/hit s 50 200) (tw/hit s 500 5)])))
    (is (= "b" (:id (tw/stack s "b"))))))
```

- [x] **Step 2: Run to see them fail**

Run: `npm test`
Expected: compile warning/error `No such namespace: scb.chart.tween`.

- [x] **Step 3: Implement**

`src/scb/chart/tween.cljs`: move from `src/scb/chart.cljs`, unchanged in logic, `ease`, `tween-val`, `settled?`, `tw`, `retarget`, `nice-ticks`, `series-gap`, `group-units`, `layout`, `fit-margins!`, `place!`, `busy?`, `hit`, `stack`, with these mechanical changes:

- Every reference to the module globals `st` and `margin` becomes the parameter `s` and `(.-margin s)` (bind `m (.-margin s)` where used often). `(.-reduced st)` → `(.-reduced s)`.
- `tween-val` and `settled?` become public; `dur` becomes public (`(def dur 850)`).
- `layout` takes `[^js s model]` and reads the width from `(.-w s)`.
- `fit-margins!` takes `[^js s model text-w]`: replace the `ctx`/`measureText` lines with `text-w`:
  ```clojure
  ws (mapv (comp text-w :label) stacks)
  base-l (.-lbase s)
  ```
- `place!` takes `[^js s model move? t text-w]`: drop `(now)`, use `t`; call `(fit-margins! s model text-w)` and `(layout s model)`.
- `busy?` takes `[^js s t]`; `hit` takes `[^js s mx my]`; `stack` takes `[^js s id]`.
- New:
  ```clojure
  (defn state
    "A fresh chart: no model placed, nothing on screen."
    []
    #js {:w 0 :h 0 :lbase 68 :margin #js {:l 68 :r 18 :t 44 :b 70}
         :model nil :bars (js/Map.) :labels (js/Map.) :order #js [] :slot 1
         :ymax #js {:from 1 :to 1 :t0 0 :delay 0} :reduced false
         :hover nil :look nil :theme nil})

  (defn size!
    "The canvas size in CSS pixels; narrow canvases get smaller margins."
    [^js s w h]
    (let [narrow? (< w 560)
          m (.-margin s)]
      (set! (.-w s) w)
      (set! (.-h s) h)
      (set! (.-lbase s) (if narrow? 46 68))
      (set! (.-l m) (.-lbase s))
      (set! (.-r m) (if narrow? 6 18))))
  ```

Namespace docstring: "Where the chart's bars and labels are heading and where they are now: layout and tweens, no DOM. One mutable state object per chart; times are the chart loop's :t in ms."

- [x] **Step 4: Run the tests**

Run: `npm test`
Expected: all pass (5 data + 8 tween tests).

- [x] **Step 5: Commit**

```bash
git add src/scb/chart/tween.cljs test/scb/chart/tween_test.cljs
git commit -m "scb.chart.tween: layout and tweens on a per-chart state"
```

---

### Task 3: The chart as a `defloop`, its paint, the tooltip `defc`

**Files:**
- Create: `src/scb/chart/paint.cljs`, `test/scb/chart_test.cljs`
- Rewrite: `src/scb/chart.cljs`
- Modify: `src/scb/core.cljs` (events, `nave-chart`, `main`), `public/serious.css`, `public/evil.css`

**Interfaces:**
- Consumes: Task 2's `scb.chart.tween`.
- Produces:
  - `scb.chart.paint`: `(read-theme! s)`, `(text-width ctx theme)` → `(fn [label] px)`, `(frame! ctx s t)`.
  - `scb.chart`: `(chart model)` defloop, `(chart-tip model)` defc, event `:hover` `[:hover id-or-nil]`.
  - `scb.core`: events `:restyle` (bumps `:look-rev`), db keys `:hover`, `:look-rev`.

- [x] **Step 1: Write the failing tests**

`test/scb/chart_test.cljs`:

```clojure
(ns scb.chart-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [scb.test-env :as env]
            [hammer.core :refer [defc reg-event dispatch mount!]]
            [hammer.testing :as t]
            [scb.chart :as chart]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (reset! env/log [])
                               (js-delete js/globalThis "IntersectionObserver"))})

(reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defn- bar [id v] {:id id :stack (subs id 0 1) :slot 0 :rank 3 :style 0 :effort "high" :value v
                   :runs [{}] :coverage [1 1]})
(defn- model [& stacks]
  {:models [{:model "m" :label "M" :style 0}] :efforts [{:effort "high" :rank 3}]
   :stacks (vec (for [[id bars] stacks] {:id id :label id :series [{:model "M" :style 0 :bars bars}]}))})

(def m1 (model ["a" [(bar "a1" 2)]] ["b" [(bar "b1" 4)]]))
(def m2 (model ["a" [(bar "a1" 3)]] ["b" [(bar "b1" 1)]]))

(defc host [] [m [:m]] [:div.canvas-box [chart/chart m] [chart/chart-tip m]])

(def db0 {:m m1 :hover nil :mode "serious" :theme "auto" :look-rev 0})

(defn- mount []
  (let [el (js/document.createElement "div")]
    (mount! [host] el db0)
    el))

(defonce clock (atom 0))
(defn- frames!
  "Frames 16ms apart for ms of frame time."
  [ms]
  (dotimes [_ (js/Math.ceil (/ ms 16))] (t/frame! (swap! clock + 16))))
(defn- drawn? [] (boolean (seq (env/ops))))
(defn- ops-of [k] (filter #(= k (first %)) (env/ops)))

(deftest draws-once-revealed-then-stops
  (mount)
  (frames! 1500)
  (is (seq (ops-of :fill)) "bars")
  (is (some #(= [:fillText "b"] (take 2 %)) (env/ops)) "stack labels")
  (frames! 64)
  (reset! env/log [])
  (frames! 200)
  (is (not (drawn?)) "settled: the loop stopped"))

(deftest model-change-animates-then-stops
  (mount)
  (frames! 1500)
  (reset! env/log [])
  (dispatch [::set :m m2])
  (frames! 160)
  (is (< 5 (count (ops-of :setTransform))) "a frame each tick while moving")
  (frames! 1500)
  (reset! env/log [])
  (frames! 200)
  (is (not (drawn?))))

(deftest restyle-mid-tween-settles
  (mount)
  (frames! 1500)
  (dispatch [::set :m m2])
  (frames! 200)
  (dispatch [::set :look-rev 1])
  (frames! 1500)
  (reset! env/log [])
  (frames! 200)
  (is (not (drawn?)) "a restyle snaps and the loop still stops"))

(deftest hover-shows-the-tooltip-away-from-the-stack
  (let [el (mount)]
    (dispatch [:hover "b"])
    (t/flush!)
    (let [tip (.querySelector el ".tip")]
      (is (= "b" (.-textContent (.querySelector tip ".tip-head b"))))
      (is (.contains (.-classList tip) "at-left"))
      (is (re-find #"high\s+\$4\.00\s+1 run" (.-textContent tip))))
    (dispatch [:hover "a"])
    (t/flush!)
    (is (.contains (.-classList (.querySelector el ".tip")) "at-right"))
    (dispatch [:hover nil])
    (t/flush!)
    (is (nil? (.querySelector el ".tip")))))

(deftest stale-hover-shows-no-tip
  (let [el (mount)]
    (dispatch [:hover "zz"])
    (t/flush!)
    (is (nil? (.querySelector el ".tip")))))

(deftest pointer-and-keys-pick-a-stack
  (let [el (mount)
        c (.querySelector el "canvas")]
    (frames! 1500)
    (.dispatchEvent c (js/window.MouseEvent. "pointermove" #js {:clientX 500 :clientY 200}))
    (t/flush!)
    (is (= "b" (.-textContent (.querySelector el ".tip-head b"))))
    (.dispatchEvent c (js/window.KeyboardEvent. "keydown" #js {:key "ArrowRight"}))
    (t/flush!)
    (is (= "a" (.-textContent (.querySelector el ".tip-head b"))) "wraps around")
    (.dispatchEvent c (js/window.KeyboardEvent. "keydown" #js {:key "Escape"}))
    (t/flush!)
    (is (nil? (.querySelector el ".tip")))))

(deftest reveal-places-latest-model
  (let [cb (atom nil)]
    (set! js/globalThis.IntersectionObserver
          (fn [f _] (reset! cb f) (this-as o (set! (.-observe o) (fn [_])) (set! (.-disconnect o) (fn [])) o)))
    (mount)
    (frames! 100)
    (dispatch [::set :m m2])
    (frames! 1500)
    (is (empty? (ops-of :fill)) "not in view: no bars")
    (@cb #js [#js {:isIntersecting true}])
    (frames! 1500)
    (is (seq (ops-of :fill)))
    (is (some #(= [:fillText "$3"] (take 2 %)) (env/ops)))
    (is (not-any? #(= [:fillText "$4"] (take 2 %)) (env/ops)) "m2's axis (to $3), never m1's (to $4)")))
```

- [x] **Step 2: Run to see them fail**

Run: `npm test`
Expected: compile error — `scb.chart` has no `chart`/`chart-tip`.

- [x] **Step 3: `scb.chart.paint`**

`src/scb/chart/paint.cljs`: move from `src/scb/chart.cljs`, logic unchanged, `fmt-axis`, `mix`, `oklch`, `hues`, `high-rank`, `shade`, `bar-color`, `texture!`, `lancet-path!`, `draw-lancet!`, `plain-path!`, `draw-plain!`, `text!`, `swatch!`, `draw-legend!`, `draw!` (renamed `frame!`), `css-var`, with these changes:

- The module global `theme` becomes a parameter `th` (`^js`), taken from `(.-theme s)`: `bar-color [th slot rank]`, `draw-lancet! [ctx th color …]`, `draw-plain! [ctx th color …]`, `swatch! [ctx th x y rank style]`, `draw-legend! [ctx s model]`.
- `margin` becomes `(.-margin s)`; `(.-w st)`/`(.-h st)` become `(.-w s)`/`(.-h s)`.
- `frame!` takes `[^js ctx ^js s t]`, reads `model (.-model s)`, `hover (.-hover s)`, `th (.-theme s)`, guards `(when (and model th (pos? w)) …)`, and drops the `setTransform` line (hammer scales the context by the DPR before each draw). It calls `tw/tween-val`, `tw/settled?` and `tw/nice-ticks` from `scb.chart.tween`.
- New:
  ```clojure
  (defn read-theme!
    "Colours and fonts from the active stylesheet into the chart state."
    [^js s]
    (let [cs (js/getComputedStyle (.-documentElement js/document))]
      (set! (.-theme s)
            #js {:evil (= "evil" (.. js/document -documentElement -dataset -mode))
                 :sl (js/parseFloat (css-var cs "--chart-stack-l" "0.62"))
                 :sc (js/parseFloat (css-var cs "--chart-stack-c" "0.14"))
                 :ink (css-var cs "--chart-ink" "#ddd")
                 :ink2 (css-var cs "--chart-ink-2" "#aaa")
                 :grid (css-var cs "--chart-grid" "rgba(128,128,128,0.15)")
                 :base (css-var cs "--chart-base" "rgba(128,128,128,0.6)")
                 :frame (css-var cs "--chart-frame" "rgba(44,40,50,0.95)")
                 :hot (css-var cs "--chart-hot" "rgba(255,255,255,0.8)")
                 :label (css-var cs "--chart-label-font" "monospace")
                 :num (css-var cs "--chart-num-font" "monospace")})))

  (defn text-width
    "Width of a stack label in the label font, for fitting the margins."
    [^js ctx ^js th]
    (set! (.-font ctx) (str "500 11px " (.-num th)))
    (fn [label] (.-width (.measureText ctx label))))
  ```

Namespace docstring: the first two paragraphs of today's `scb.chart` docstring about colour, shade, texture and lancets, ending "Draws one frame from a chart state; colours and fonts come from the stylesheet."

- [x] **Step 4: `scb.chart`**

Replace `src/scb/chart.cljs` with:

```clojure
(ns scb.chart
  "The one chart: cost bars grouped by stack, one bar per model and effort
   level, so an even climb from medium to xhigh shows at a glance. A defloop
   that runs only while bars move, and a defc tooltip for the hovered stack."
  (:require [hammer.core :refer [defc reg-event dispatch]]
            [hammer.canvas :refer [defloop]]
            [scb.chart.paint :as paint]
            [scb.chart.tween :as tween]))

(reg-event :hover (fn [db id] {:db (assoc db :hover id)}))

(defn- hover! [^js s id]
  (when (not= id (.-hover s))
    (set! (.-hover s) id)
    (dispatch [:hover id])))

(defn- step-key
  "Arrow keys step through the stacks (wrapping), Escape lets go."
  [^js s ^js e]
  (let [order (vec (.-order s))
        i (.indexOf order (.-hover s))
        step (case (.-key e) ("ArrowRight" "ArrowDown") 1 ("ArrowLeft" "ArrowUp") -1 nil)]
    (cond
      (and step (seq order))
      (do (.preventDefault e)
          (hover! s (nth order (mod (+ (if (neg? i) (if (pos? step) -1 0) i) step) (count order)))))
      (= "Escape" (.-key e)) (hover! s nil))))

(defn- reveal
  "Sets `revealed` once a quarter of the canvas is in view, or at once
   without an IntersectionObserver; returns the observer for :dispose."
  [revealed ^js ctx]
  (if (exists? js/IntersectionObserver)
    (doto (js/IntersectionObserver.
           (fn [^js entries] (when (.-isIntersecting (aget entries 0)) (reset! revealed true)))
           #js {:threshold 0.25})
      (.observe (.-canvas ctx)))
    (do (reset! revealed true) nil)))

(defn- hooks
  "The canvas's handlers and lifecycle, built once per chart so its opts stay =."
  [^js s revealed]
  {:init (fn [ctx _] (reveal revealed ctx))
   :dispose (fn [^js io] (when io (.disconnect io)))
   :move (fn [_ {:keys [x y]}] (hover! s (tween/hit s x y)))
   :leave (fn [_ _] (hover! s nil))
   :key (fn [e _] (step-key s e))})

(defloop chart [model]
  [hover [:hover]
   mode [:mode]
   theme [:theme]
   rev [:look-rev]
   look (vector mode theme rev)
   reduced (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
   busy (atom false)
   revealed (atom false)
   st (volatile! (tween/state))
   on (hooks @st revealed)]
  {:run? @busy
   :init (:init on) :dispose (:dispose on)
   :on-pointermove (:move on) :on-pointerleave (:leave on)
   :on-keydown (:key on) :on-blur (:leave on)
   :attrs {:tabindex "0"
           :aria-label (str "Bar chart of cost per stack: one bar per model and effort level, grouped by stack. "
                            "The tables above hold the same aggregates. Arrow keys step through the stacks.")}}
  (fn [ctx {:keys [w h t]} _]
    (let [^js s @st
          restyle? (not= look (.-look s))
          resize? (or (not= w (.-w s)) (not= h (.-h s)))]
      (set! (.-hover s) hover)
      (set! (.-reduced s) reduced)
      (when restyle? (set! (.-look s) look) (paint/read-theme! s))
      (when resize? (tween/size! s w h))
      (let [text-w (paint/text-width ctx (.-theme s))]
        ;; fonts and sizes move the labels: snap what is there
        (when (and (or restyle? resize?) (.-model s)) (tween/place! s (.-model s) false t text-w))
        (when (and @revealed model (not (identical? model (.-model s)))) (tween/place! s model true t text-w)))
      (paint/frame! ctx s t)
      (let [b (boolean (tween/busy? s t))]
        (when (not= b @busy) (reset! busy b))))))

(defn- pad [s n] (.padEnd (str s) n))

(defc chart-tip [model]
  [hover [:hover]
   stacks (:stacks model)
   i (if hover (.indexOf (mapv :id stacks) hover) -1)
   s (when-not (neg? i) (nth stacks i))]
  (when s
    ;; in the top corner away from the stack
    [:div.tip.glass {:role "status" :class (if (>= (* 2 i) (count stacks)) "at-left" "at-right")}
     [:div.tip-head [:b (:label s)]]
     [:div
      (for [sr (:series s)]
        ^{:key (:model sr)}
        [:div
         [:div.tip-sub.tip-model (:model sr)]
         [:ul.tip-runs
          (for [b (:bars sr)
                :let [n (count (:runs b))
                      [have of] (:coverage b)]]
            ^{:key (:effort b)}
            [:li (str (pad (:effort b) 8) (pad (str "$" (.toFixed (:value b) 2)) 9)
                      n (if (= 1 n) " run" " runs")
                      (when (< have of) (str " · " have "/" of " projects")))])]])]]))
```

- [x] **Step 5: Wire `scb.core`**

In `src/scb/core.cljs`:

- Delete `with-chart`, `(reg-fx :chart …)`, the `chart-canvas` defc, and in `apply-look!` the lines `(if evil? (nave/start!) (nave/stop!))` and `(chart/restyle!)`. (The nave lines go for good in Task 5; until then evil mode has no background, which Task 5 restores.)
- Events become (unchanged ones omitted):
  ```clojure
  (reg-event :loaded
             (fn [db d]
               (let [d (data/without-hidden-projects d)]
                 {:db (with-data (assoc db :all d :topic (:id (first (:topics d)))
                                        :models (data/default-models (:runs d))))})))
  (reg-event :topic (fn [db t] {:db (assoc db :topic t :project "all" :hover nil)}))
  (reg-event :project (fn [db p] {:db (assoc db :project p :hover nil)}))
  (reg-event :brave (fn [db on] {:db (with-data (assoc db :brave on :hover nil)) :store ["scb-brave" (str on)]}))
  (reg-event :model
             (fn [db m]
               ;; toggle one model; the last one shown stays on
               (let [shown (or (:models db) (set (data/models (:runs (:data db)))))
                     next (if (shown m) (disj shown m) (conj shown m))]
                 (when (seq next) {:db (assoc db :models next :hover nil)}))))
  (reg-event :mode (fn [db m] {:db (with-data (assoc db :mode m :hover nil)) :look [m (:theme db)]}))
  (reg-event :restyle (fn [db] {:db (update db :look-rev inc)}))
  ```
- `nave-chart` gains bindings `shown [:models]`, `all [:all]`, `model (data/chart-model d {:topic tid :project pid :models shown :all all})`, and its canvas line becomes
  `[:div.canvas-wrap.glass [:div.canvas-box [chart/chart model] [chart/chart-tip model]]]`.
- `main`: the colour-scheme listener dispatches instead of restyling; fonts too; the db gains the new keys:
  ```clojure
  (.addEventListener (js/matchMedia "(prefers-color-scheme: dark)") "change" #(dispatch [:restyle]))
  (.then (.-ready (.-fonts js/document)) #(dispatch [:restyle]))
  (mount! [app] (.getElementById js/document "app")
          {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :tone nil
           :mode mode :theme theme :brave (= "true" (stored "scb-brave" "false")) :models nil
           :hover nil :look-rev 0})
  ```

- [x] **Step 6: CSS**

In both `public/serious.css` and `public/evil.css`, after the `.canvas-box` rules:

```css
.canvas-wrap { container-type: inline-size; }
.canvas-box { height: clamp(340px, 52cqi, 540px); }
.tip { top: 52px; }
.tip.at-left { left: 76px; }
.tip.at-right { right: 26px; }
```

(merge `container-type` into the existing `.canvas-wrap` rule and `height` into `.canvas-box`; drop `cursor`/`display`/`width` duplicates hammer already sets inline only if they conflict — they do not, keep them).

- [x] **Step 7: Run the tests and the build**

Run: `npm test` → all pass. Run: `npx shadow-cljs compile app` → 0 warnings.
`grep -rn "requestAnimationFrame\|ResizeObserver\|visibilitychange\|:ref" src/scb/chart* src/scb/core.cljs` → no output.

- [x] **Step 8: Commit**

```bash
git add src/scb/chart.cljs src/scb/chart/paint.cljs src/scb/core.cljs public/serious.css public/evil.css test/scb/chart_test.cljs
git commit -m "Chart as a hammer defloop with a defc tooltip; hover and restyle through the db"
```

---

### Task 4: Load results.json with `:http`

**Files:**
- Modify: `src/scb/core.cljs`
- Create: `test/scb/core_test.cljs`

**Interfaces:**
- Consumes: `scb.fixture/results`, `scb.test-env`.
- Produces: event `[:init]` that loads `results.json`.

- [x] **Step 1: Write the failing tests**

`test/scb/core_test.cljs`:

```clojure
(ns scb.core-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [scb.test-env]
            [hammer.core :refer [dispatch mount!]]
            [hammer.http :as http]
            [hammer.testing :as t]
            [scb.core :as core]
            [scb.fixture :refer [results]]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!))
                     :after (fn [] (http/set-fetch! nil))})

(def db0 {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :tone nil
          :mode "serious" :theme "auto" :brave false :models nil :hover nil :look-rev 0})

(defn- load! [respond check]
  (async done
    (let [el (js/document.createElement "div")
          urls (atom [])]
      (http/set-fetch! (fn [url _] (swap! urls conj url) (respond)))
      (mount! [core/app] el db0)
      (dispatch [:init])
      (t/flush!)
      (js/setTimeout (fn [] (t/flush!) (check el @urls) (done)) 30))))

(deftest loads-results-over-http
  (load! #(js/Promise.resolve (js/Response. (js/JSON.stringify (clj->js results)) #js {:status 200}))
         (fn [el urls]
           (is (= ["results.json"] urls))
           (is (= ["Web development" "CLI tools" "Compare"]
                  (mapv #(.-textContent %) (.querySelectorAll el "section.topic h2")))))))

(deftest http-error-shows-error
  (load! #(js/Promise.resolve (js/Response. "nope" #js {:status 404}))
         (fn [el _] (is (= "Could not load results.json: HTTP 404" (.-textContent (.querySelector el ".state")))))))

(deftest network-failure-shows-error
  (load! #(js/Promise.reject (js/TypeError. "Failed to fetch"))
         (fn [el _] (is (= "Could not load results.json: network" (.-textContent (.querySelector el ".state")))))))

(deftest topic-change-clears-hover
  (let [el (js/document.createElement "div")]
    (mount! [core/app] el db0)
    (dispatch [:loaded results])
    (t/flush!)
    (dispatch [:hover "react-go"])
    (t/flush!)
    (is (some? (.querySelector el ".tip")))
    (dispatch [:topic "cli"])
    (t/flush!)
    (is (nil? (.querySelector el ".tip")))))
```

- [x] **Step 2: Run to see them fail**

Run: `npm test`
Expected: FAIL — `no event handler for :init` reported, so `flush!` throws.

- [x] **Step 3: Implement**

In `src/scb/core.cljs`: require `[hammer.http]`; delete `load!`; add

```clojure
(reg-event :init
           (fn [_] {:http {:uri "results.json" :fetch-options {:cache "no-cache"}
                           :on-success [:loaded] :on-failure [:failed]}}))
(reg-event :failed
           (fn [db {:keys [status failure]}]
             {:db (assoc db :error (if (pos? status) (str "HTTP " status) (name failure)))}))
```

(replacing the old `:failed`), and in `main` replace `(load!)` with `(dispatch [:init])`.

- [x] **Step 4: Run the tests**

Run: `npm test` → all pass.

- [x] **Step 5: Commit**

```bash
git add src/scb/core.cljs test/scb/core_test.cljs
git commit -m "Load results.json with hammer's :http effect"
```

---

### Task 5: The nave as a `defloop`

**Files:**
- Modify: `src/scb/nave.cljs`, `src/scb/core.cljs` (`app`, `main`, requires), `public/index.html`, `public/evil.css`, `public/serious.css`
- Test: `test/scb/core_test.cljs` (one test)

**Interfaces:**
- Produces: `scb.nave/nave`, a `defloop` with no props, rendering `canvas#nave`.

- [x] **Step 1: Write the failing test**

Append to `test/scb/core_test.cljs`:

```clojure
(deftest nave-only-in-evil-mode
  (let [el (js/document.createElement "div")]
    (mount! [core/app] el db0)
    (dispatch [:loaded results])
    (t/flush!)
    (is (nil? (.querySelector el "#nave")))
    (dispatch [:mode "evil"])
    (t/flush!)
    (is (= "true" (.getAttribute (.querySelector el "canvas#nave") "aria-hidden")))
    (dispatch [:mode "serious"])
    (t/flush!)
    (is (nil? (.querySelector el "#nave")))))
```

The `:look` fx touches `#css-evil`/`#css-serious`; add them to the jsdom page in `scb.test-env` (the `JSDOM.` HTML): `<link id=\"css-serious\"><link id=\"css-evil\">` in the head.

- [x] **Step 2: Run to see it fail**

Run: `npm test` → FAIL: no `canvas#nave` in evil mode.

- [x] **Step 3: Implement**

`src/scb/nave.cljs`:
- Keep `jewels`, `rgba`, `canvas`, `arch!`, `draw-stone!`, `seeded`, `impact`, `draw-rose!` unchanged.
- Delete `st`, `kick!`, `start!`, `stop!`.
- `rebuild!` becomes:
  ```clojure
  (defn- rebuild!
    "Stone, rose, dust and shards for a w × h canvas; caches at DPR ≤ 2."
    [^js s w h dpr]
    (let [cd (min 2 dpr)
          r (* 0.34 (min (* w 1.1) (* h 1.3)))]
      (set! (.-w s) w) (set! (.-h s) h) (set! (.-dpr s) dpr) (set! (.-cd s) cd)
      (set! (.-stone s) (draw-stone! w h cd))
      (set! (.-rose s) #js {:img (draw-rose! r cd) :r r})
      (set! (.-shards s) (into-array (for [k (range 5)] #js {:t0 nil :delay (* k 1.7)})))
      (set! (.-dust s)
            (into-array (for [_ (range (js/Math.round (/ (* w h) 26000)))]
                          #js {:x (rand w) :y (rand h) :v (+ 4 (rand 10)) :r (+ 0.4 (rand 1.3)) :p (rand 6.28)})))))
  ```
- `frame` becomes `(defn- paint! [^js ctx ^js s secs reduced] …)`: same body, with `st` → `s`, `(.-reduced st)` → `reduced`, `secs` passed in, `dpr` read as `(.-cd s)` (the caches' DPR) for the rose size, and the two `setTransform` calls and the stone blit replaced by one line under hammer's DPR transform: `(.drawImage ctx (.-stone s) 0 0 w h)`; drop the trailing `requestAnimationFrame` `when`.
- New component, with `(:require [hammer.canvas :refer [defloop]])`:
  ```clojure
  (defloop nave []
    [reduced (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
     st (volatile! #js {:w 0 :h 0 :dpr 0})]
    {:run? (not reduced) :attrs {:id "nave" :aria-hidden "true"}}
    (fn [ctx {:keys [w h dpr t]}]
      (let [^js s @st]
        (when (or (not= w (.-w s)) (not= h (.-h s)) (not= dpr (.-dpr s))) (rebuild! s w h dpr))
        (paint! ctx s (/ t 1000) reduced))))
  ```
- Namespace docstring: "Full-page background in evil mode: a broken rose window behind the title, an arcade of pointed arches, a shaft of light and falling ash. A defloop; the static stone is drawn once per size, each frame only composites. Reduced motion draws still frames."

`src/scb/core.cljs`: `app` renders `(when (evil? mode) [nave/nave])` as the first child of `[:div.shell …]`; `main` drops `(when (evil? mode) (nave/start!))`.

`public/index.html`: delete `<canvas id="nave" aria-hidden="true"></canvas>`.
`public/evil.css`: `#nave` gets `z-index: -1` (it now sits inside `#app`'s stacking context, under the page).
`public/serious.css`: delete `#nave { display: none; }`.

- [x] **Step 4: Run tests and build**

Run: `npm test` → all pass. `npx shadow-cljs compile app` → 0 warnings.

- [x] **Step 5: Commit**

```bash
git add src/scb/nave.cljs src/scb/core.cljs public/index.html public/evil.css public/serious.css test/scb/
git commit -m "Nave as a hammer defloop, rendered in evil mode"
```

---

### Task 6: README, release build, browser check

**Files:**
- Modify: `README.md`

- [x] **Step 1: README**

- Intro: "draws its chart by hand on a canvas" → "draws its chart and the evil-mode nave by hand on canvases, as `hammer.canvas` loops".
- Build section: add `bb test     # node tests (jsdom)` to the command block; "Needs Node, Java and the Clojure CLI" unchanged.

- [x] **Step 2: Release build**

Stop any running `bb dev` first (its JVM keeps the classpath it started with). Run: `bb release` → 0 warnings.

- [x] **Step 3: Browser check** (serve with `bb serve` on 8290 or `npx http-server public -p 8299`)

In Chrome, both modes, light and dark: the bars rise on scroll; topic, project and model changes tween; hover and arrow keys show the tooltip on the far side; a narrow window (< 560px) keeps the labels inside; evil mode shows the nave, the rose drifts with scroll, ash falls; serious mode removes it; the console has no `hammer:` reports. With DevTools' "prefers-reduced-motion: reduce" emulation, bars snap and the nave stands still.

- [x] **Step 4: Commit**

```bash
git add README.md
git commit -m "README: hammer.canvas loops, bb test"
```
