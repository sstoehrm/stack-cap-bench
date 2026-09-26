(ns scb.core
  (:require [clojure.string :as str]
            [hammer.core :refer [defc reg-event reg-fx dispatch mount!]]
            [scb.chart :as chart]
            [scb.data :as data]
            [scb.nave :as nave]))

;; ---- events

(defn- with-chart [db]
  {:db db :chart (data/chart-model (:data db) db)})

(reg-fx :chart #(when % (chart/update! %)))

(reg-event :loaded (fn [db d] (with-chart (assoc db :data d :topic (:id (first (:topics d)))))))
(reg-event :failed (fn [db msg] {:db (assoc db :error msg)}))
(reg-event :metric (fn [db k] (with-chart (assoc db :metric k))))
(reg-event :topic (fn [db t] (with-chart (assoc db :topic t :project "all" :hidden #{}))))
(reg-event :project (fn [db p] (with-chart (assoc db :project p))))
(reg-event :creed (fn [db tone] {:db (assoc db :tone tone) :show-creed true}))
(reg-event :tone (fn [db tone] {:db (assoc db :tone tone)}))

(defn- creed-el [] (.getElementById js/document "creed"))

(reg-fx :show-creed (fn [_] (some-> (creed-el) (.showModal))))

(reg-event :toggle-config
           (fn [db k]
             (let [h (:hidden db)]
               (with-chart (assoc db :hidden (if (contains? h k) (disj h k) (conj h k)))))))

;; ---- helpers

(def ^:private numerals ["I" "II" "III" "IV" "V" "VI"])

(defn- usd [v] (str "$" (.toLocaleString (js/Math.round v) "en-US")))

(defn- compact [v] ((:fmt (data/metric "input_tokens")) v))

(defn- swatch [stack] [:span.swatch {:style {:background (data/color stack)}}])

(defn- chip [on? event label]
  [:button.chip {:class (when on? "on") :aria-pressed (str (boolean on?)) :on-click event} label])

(defn- tile [label value note & [cls]]
  [:div.tile.glass {:class cls} [:span.tile-label label] [:strong.tile-value value] [:span.tile-note note]])

(defn- project-names [d ids]
  (str/join " · " (for [id ids] (str id " " (:name (data/project d id))))))

;; ---- components

(defc hero []
  [d [:data]
   runs (:runs d)]
  [:header.hero
   [:p.kicker (:source d) " · imported " (:imported d)]
   [:h1 "Stack Cap Bench"]
   [:p.lede "One headless agent builds the same projects, step by step, in every stack. "
    "Each token it burns is counted. Each dollar is on the ledger. No stack is absolved."]
   [:aside.disclaimer.glass {:aria-label "Disclaimer"}
    [:span.disclaimer-label "Disclaimer"]
    [:p "This benchmark tries to measure the token cost and performance of technology stacks — "
     "for now, on small projects. Treat the numbers as a direction, not a verdict."]
    [:p.disclaimer-links
     [:button.linkish {:on-click [:creed :semi]} "Read the vision"]
     [:span.sep "·"]
     [:button.linkish {:on-click [:creed :method]} "how the data is gathered"]
     [:span.sep "·"]
     [:button.linkish {:on-click [:creed :jest]} "or the version nobody should take seriously"]]]
   [:div.tiles.hero-tiles
    (tile "Runs" (str (count (filter :complete runs)) "/" (count runs)) "complete / recorded")
    (tile "Stacks" (count (distinct (map :stack runs))) "in the dock")
    (tile "Spent" (usd (reduce + (map :cost_usd runs))) "list price, every attempt")
    (tile "Tokens burnt" (compact (reduce + (map #(+ (:input_tokens %) (:output_tokens %)) runs)))
          "input + output")]])

(defc metric-bar []
  [metric [:metric]
   m (data/metric metric)]
  [:nav.rood.glass {:aria-label "Measure"}
   [:span.rood-label "Judge by"]
   [:div.chips (for [x data/metrics]
                 ^{:key (:key x)} (chip (= metric (:key x)) [:metric (:key x)] (:label x)))]
   [:span.rood-note (:note m)]])

(defc topic-section [tid idx]
  [d [:data]
   metric [:metric]
   m (data/metric metric)
   tbl (data/table d tid metric)
   totals (data/topic-totals d tid)
   lean (data/leanest tbl)]
  (let [{:keys [topic configs stacks cells best]} tbl]
    [:section.topic {:id (str "topic-" tid)}
     [:header.topic-head
      [:span.numeral (nth numerals idx)]
      [:div [:h2 (:label topic)]
       [:p.topic-sub "Project" (when (> (count (:projects topic)) 1) "s") " " (project-names d (:projects topic))]]]
     [:div.tiles
      (tile "Runs" (str (:complete totals) "/" (:runs totals)) "complete / recorded")
      (tile "Spent" (usd (:cost totals)) "list price, every attempt")
      (tile "Tokens burnt" (compact (:tokens totals)) "input + output")
      (tile "Leanest" (if lean [:span (swatch lean) lean] "—") (str "lowest mean rank by " (str/lower-case (:label m))) "tile-name")]
     [:div.table-wrap.glass
      [:table
       [:caption (:label m) " — mean over complete runs"
        (when (> (count (:projects topic)) 1) ", averaged across projects")
        ". ✠ marks the leanest stack per configuration; superscript is the run count."]
       [:thead [:tr [:th {:scope "col"} "Stack"]
                (for [c configs]
                  (let [[mdl eff] (str/split (:label c) #" · ")]
                    ^{:key (:key c)} [:th {:scope "col"} mdl [:span.eff eff]]))]]
       [:tbody
        (for [s stacks]
          ^{:key s}
          [:tr [:th {:scope "row"} (swatch s) s]
           (for [c configs]
             (let [v (get cells [s (:key c)])
                   win? (and v (= (:value v) (get best (:key c))))]
               ^{:key (:key c)}
               [:td {:class (when win? "best")}
                (if v
                  [:span (when win? [:span.cross {:aria-label "leanest"} "✠ "])
                   ((:fmt m) (:value v)) [:sup (count (:runs v))]]
                  [:span.none "—"])]))])]]]]))

(def ^:private creed-text
  {:semi
   [[:h3 "Why this exists"]
    [:p "Picking a framework for a greenfield project used to be a question about people: who knows it, "
     "who can hire for it, how it feels to write every day. Now an agent writes a good part of the code. "
     "I want to be aware of the trade-offs of that choice in the agentic age — and to find out whether "
     "it even still matters."]
    [:p "If every stack costs an agent about the same, pick whatever you enjoy. If not, the difference "
     "should show up somewhere measurable: tokens, dollars, minutes and retries. This page collects that evidence."]
    [:h3 "How it is measured"]
    [:p "A headless coding agent (Claude Code, run as " [:code "claude -p"] ") builds the same small projects "
     "step by step in every stack: " [:em "loga"] ", a log-analyzer CLI; " [:em "spendly"]
     ", a full-stack expense tracker; and " [:em "kanbn"] ", a kanban single-page app. Five steps each. "
     "After every step a harness checks the result — golden CLI cases, an HTTP contract, a headless browser — "
     "and retries a failed step up to three times."]
    [:p "Every attempt is recorded: input and output tokens, list-price cost, wall time, attempts and tool calls. "
     "Only runs where every step passed count toward the means you see here."]
    [:p [:button.linkish {:on-click [:tone :method]} "How the data is gathered, in detail"]]
    [:h3 "What it is not"]
    [:ul
     [:li "Not a verdict. The projects are small, and most configurations have one or two runs."]
     [:li "Not the whole trade-off. Hiring, ecosystem, runtime performance and taste are not measured here."]
     [:li "Not a bill. Cost is " [:code "claude -p"] "'s list price, not what anyone paid."]
     [:li "Not vendor-neutral. One agent, a few models and effort levels; others may rank stacks differently."]
     [:li "Not a clean stopwatch. Runs share a machine, so wall time grows with parallel load."]]
    [:p "Bigger projects and more runs per configuration come next. Until then, read the rankings as a hint "
     "about where the trade-offs are, not as the answer."]]
   :method
   [[:h3 "Easy problems, exact targets"]
    [:p "The projects are deliberately not hard. No algorithm puzzles, no research: a log analyzer, "
     "an expense tracker, a kanban board — work a competent developer finishes in an afternoon. "
     "What makes them useful is how precisely they are defined."]
    [:p "Building software professionally is rarely about solving something nobody could solve. "
     "It is about reaching an agreed, well-defined state — this output, this API, this screen — "
     "reliably and at a known cost. So every step names that state exactly, and the harness checks it exactly. "
     "The question is not " [:em "can"] " the agent do it, but what it costs to get there in each stack."]
    [:h3 "Three projects, five steps each"]
    [:ul
     [:li [:em "loga"] " (CLI) — a log analyzer: summaries, time filters, top messages, JSON output, "
      "several inputs and stdin, histograms. "
      "Acceptance is the exact stdout and exit code of " [:code "./run.sh"] " for fixed arguments."]
     [:li [:em "spendly"] " (full stack) — an expense tracker: a JSON API with validation and persistence, "
      "categories and filters, a monthly summary with a bar chart, CSV import with a validation report, "
      "editing, and budgets with over-budget highlights."]
     [:li [:em "kanbn"] " (single-page app) — a kanban board whose logic lives in the browser: "
      "cards moved across three columns by button and drag and drop, undo and redo, labels and search, "
      "several boards with routing, reordering and work-in-progress limits. The backend only stores the board."]]
    [:p "Each step's prompt is the feature text, plus a run contract (how to start the program, "
     "where data lives, which port to use), plus a stack block naming the languages and libraries "
     "— for example ClojureScript with Replicant and a Clojure backend. Web steps also fix the API contract, "
     "the " [:code "data-testid"] " attributes and the exact DOM, down to the Tailwind classes."]
    [:h3 "The agent"]
    [:ul
     [:li "Claude Code, headless (" [:code "claude -p"] "), in a fresh workspace per run. "
      "Model and effort level stay the same for every step of a run."]
     [:li "Nobody answers its questions. Where it would ask, it decides the simplest option that meets "
      "the acceptance criteria and records the choice in " [:code "DECISIONS.md"] "."]
     [:li "It writes no tests of its own and skips test-driven development, so every stack spends its "
      "effort on the same thing: the feature. It checks the examples by running the program."]
     [:li "Steps build on each other in the same workspace; each finished step is a git commit. "
      "The advisor tool is switched off so a second model never helps in secret."]]
    [:h3 "Verification"]
    [:ul
     [:li "After every attempt the harness runs the checks for this step and every earlier one, "
      "so a regression fails the step."]
     [:li "CLI: " [:code "./run.sh"] " with fixed arguments; stdout and exit code must match exactly."]
     [:li "Web: the harness starts the server on a random port with an empty data directory, "
      "runs the HTTP contract, restarts the server on a new port to prove persistence, and drives the UI "
      "in headless Chromium. Screenshots are kept for side-by-side review against a reference UI."]
     [:li "Stack check: the workspace must contain the stack's own sources, config and dependencies "
      "(e.g. " [:code "squint.edn"] " and " [:code "reagami"] " in " [:code "package.json"]
      "). Building it in something else fails the step."]
     [:li "The checks themselves pass against a reference implementation of every project first."]]
    [:h3 "Retries"]
    [:p "A failed attempt resumes the same agent session with the list of failures. "
     "Each step gets up to three attempts of at most an hour each. After the third failure the run stops: "
     "it still shows up in the run counts, but never in the means."]
    [:h3 "What is recorded"]
    [:ul
     [:li "Input tokens, including cache reads and writes, summed over every model the session used."]
     [:li "Output tokens, including thinking."]
     [:li "Cost as reported by " [:code "claude -p"] " at list price, and wall-clock time."]
     [:li "Tool calls, pass or fail, the failure messages and the screenshots."]]
    [:p "Attempts are summed per run. The page shows the mean over complete runs per project, "
     "then averages across the projects of a topic, so each project weighs the same."]]
   :jest
   [[:h3 "Hear ye"]
    [:p "Every tech stack swears it is lean. Every stack has a conference talk proving it. "
     "We stopped listening and built a cathedral instead."]
    [:p "Into the nave we send a scribe who never sleeps, never complains and bills by the token. "
     "It must build the same three humble works in every stack: a scroll-reader for logs, "
     "a ledger of one's shameful spending, and a board of cards to be moved about for no clear reason."]
    [:h3 "The tithe"]
    [:p "Each token the scribe burns is tithe, and the tithe is recorded in stained glass, forever, "
     "in colours chosen by a committee of one. The stack that tithes least is crowned with the ✠. "
     "The others are displayed beside it. Publicly. On canvas. There are no appeals."]
    [:h3 "Articles of faith"]
    [:ul
     [:li "The scribe's retries are penance. Five attempts means none were needed."]
     [:li "Wall time is measured in candles and depends on how many other scribes are praying at once."]
     [:li "A stack that fails a step is not damned, merely left out of the means until it repents."]
     [:li "Dollars shown are list-price indulgences. Nobody actually paid them. Probably."]]
    [:p "Should your favourite stack come last: the projects are small, the runs are few, "
     "and the cathedral is still under construction. Pray for bigger projects."]]})

(defn- close-creed [] (some-> (creed-el) (.close)))

(defc creed []
  [tone [:tone]]
  [:dialog#creed.creed {:aria-labelledby "creed-title"
                        :on-click #(when (identical? (.-target %) (.-currentTarget %)) (close-creed))}
   [:div.creed-body
    [:header.creed-head
     [:h2#creed-title "The Creed"]
     [:button.creed-close {:on-click close-creed :aria-label "Close"} "✕"]]
    [:div.chips {:role "tablist" :aria-label "Tone"}
     (for [[k label] [[:semi "Semi-serious"] [:method "The method"] [:jest "Not serious at all"]]]
       ^{:key k}
       [:button.chip {:class (when (= tone k) "on") :role "tab" :aria-selected (str (= tone k))
                      :on-click [:tone k]} label])]
    (into [:div.creed-text {:role "tabpanel"}] (creed-text tone))]])

(defc chart-canvas []
  []
  [:div.canvas-box
   [:canvas {:ref chart/attach! :tabindex "0"
             :aria-label "Bar chart of the selected measure per stack and configuration. The tables above hold the same aggregates. Arrow keys step through the bars."}]])

(defc nave-chart [idx]
  [d [:data]
   tid [:topic]
   pid [:project]
   hidden [:hidden]
   topic (data/topic d tid)
   cfgs (data/configs (data/topic-runs d tid))
   stacks (data/sort-stacks (distinct (map :stack (data/topic-runs d tid))))]
  [:section.topic.nave-section {:id "chart"}
   [:header.topic-head
    [:span.numeral (nth numerals idx)]
    [:div [:h2 "The Nave"]
     [:p.topic-sub "One chart. Every dimension. Choose the topic, the project, the measure; "
      "strike configurations out to widen what remains."]]]
   [:div.controls.glass
    [:div.control [:span.control-label "Topic"]
     [:div.chips (for [t (:topics d)] ^{:key (:id t)} (chip (= tid (:id t)) [:topic (:id t)] (:label t)))]]
    [:div.control [:span.control-label "Project"]
     [:div.chips
      (chip (= pid "all") [:project "all"] "All")
      (for [p (:projects topic)]
        ^{:key p} (chip (= pid (str p)) [:project (str p)] (str p " " (:name (data/project d p)))))]]
    [:div.control [:span.control-label "Configurations"]
     [:div.chips (for [c cfgs]
                   ^{:key (:key c)} (chip (not (contains? hidden (:key c))) [:toggle-config (:key c)] (:label c)))]]]
   [:div.legend (for [s stacks] ^{:key s} [:span.legend-item (swatch s) s])]
   [:div.canvas-wrap.glass [chart-canvas]]
   [:p.chart-note "Each window rises to the mean over complete runs; its apex is the value. "
    "✠ crowns the leanest stack in each configuration. Hover, or focus the chart and use the arrow keys, "
    "for the runs behind a bar. The measure follows the bar at the top of the page."]])

(defc app []
  [d [:data]
   err [:error]]
  [:div.shell
   (cond
     err [:p.state "The candles went out: " err]
     (nil? d) [:p.state "Lighting the candles…"]
     :else
     (list
      ^{:key "hero"} [hero]
      ^{:key "creed"} [creed]
      ^{:key "bar"} [metric-bar]
      ^{:key "main"}
      [:main
       (for [[i t] (map-indexed vector (:topics d))]
         ^{:key (:id t)} [topic-section (:id t) i])
       [nave-chart (count (:topics d))]]
      ^{:key "foot"}
      [:footer.foot
       [:p "Data: " [:code "results.json"] " — hand-editable, or regenerate it with "
        [:code "bb import ../token-comparision"] ". Built with "
        [:a {:href "https://github.com/sstoehrm/hammer"} "hammer"] "; the charts are drawn by hand on canvas."]
       [:div.foot-bar
        [:span.foot-mark "Stack Cap Bench"]
        [:span "© " (.getFullYear (js/Date.)) " Sören Stöhrmann"]
        [:a.foot-link {:href "https://github.com/sstoehrm/stack-cap-bench"} "Source on GitHub"]]]))])

;; ---- start

(defn- load! []
  (-> (js/fetch "results.json" #js {:cache "no-cache"})
      (.then (fn [^js r] (if (.-ok r) (.json r) (throw (js/Error. (str "results.json: HTTP " (.-status r)))))))
      (.then #(dispatch [:loaded (js->clj % :keywordize-keys true)]))
      (.catch #(dispatch [:failed (str (.-message %))]))))

(defn ^:export main []
  (nave/start!)
  (mount! [app] (.getElementById js/document "app")
          {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :hidden #{} :tone :semi})
  (load!))
