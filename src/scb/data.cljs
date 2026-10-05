(ns scb.data
  "Pure aggregation over results.json. Every value is the mean over *complete*
   runs (every step passed), per project first, then across the projects in
   scope, so a topic with two projects weighs both equally."
  (:require [clojure.string :as str]))

;; Table row order: CLI stacks, then web stacks by family.
(def stack-order ["babashka" "clojure" "bash" "python" "go" "rust" "odin" "elixir" "ocaml"
                  "svelte-java" "svelte-kotlin" "re-frame-clojure" "replicant-clojure"
                  "reagami+squint-clojure" "clojure+hammer" "babashka+hammer" "clojure+hammer-app"
                  "babashka+hammer-app" "react-go" "vue-go"
                  "react-rust" "angular-java" "nextjs-ts" "phoenix-liveview"])

;; Hidden in evil mode unless the reader is brave: the author's own hammer stacks
;; and other small or niche frameworks and languages.
(def niche #{"clojure+hammer" "babashka+hammer" "clojure+hammer-app" "babashka+hammer-app"
             "replicant-clojure" "reagami+squint-clojure" "phoenix-liveview" "odin" "ocaml"})

(defn without-niche [data] (update data :runs #(filterv (comp not niche :stack) %)))

;; Projects left off the page for now (bookit has no runs yet).
(def hidden-projects #{5})

(defn without-hidden-projects [data]
  (-> data
      (update :projects #(filterv (comp not hidden-projects :id) %))
      (update :topics #(mapv (fn [t] (update t :projects (fn [ps] (filterv (comp not hidden-projects) ps)))) %))
      (update :runs #(filterv (comp not hidden-projects :project) %))))

;; Model order in tables and the chart: each model keeps its place (and its
;; texture in the chart) whatever else is shown. Unknown models come after.
(def model-order ["claude-opus-5-5" "claude-fable-5-1" "gpt-6-sol" "gpt-6-astra"])

(defn- model-rank [m] (let [i (.indexOf model-order m)] (if (neg? i) 99 i)))

(defn models "Models present in runs, in model order." [runs]
  (sort-by (juxt model-rank identity) (distinct (map :model runs))))

;; Models left unchecked when the page loads; the reader can tick them on.
(def default-off #{"gpt-6-sol"})

(defn default-models
  "Models checked on load: all but default-off, unless that would leave none."
  [runs]
  (let [all (set (models runs))
        on (into #{} (remove default-off) all)]
    (if (seq on) on all)))

(defn with-models
  "Only the runs of the models in `shown`; nil shows every model."
  [data shown]
  (cond-> data shown (update :runs #(filterv (comp shown :model) %))))

(def ^:private display-names {"cli" {"svelte-java" "java" "svelte-kotlin" "kotlin"}})

(defn display
  "Name shown for `stack` in topic `topic-id`; ids in the data never change."
  [topic-id stack]
  (get-in display-names [topic-id stack] stack))

(def efforts ["default" "low" "medium" "high" "xhigh" "max"])

(defn- compact [v]
  (let [a (js/Math.abs v)
        trim #(str/replace % #"\.0$" "")]
    (cond (>= a 1e9) (str (trim (.toFixed (/ v 1e9) (if (>= a 1e10) 0 1))) "B")
          (>= a 1e6) (str (trim (.toFixed (/ v 1e6) (if (>= a 1e7) 0 1))) "M")
          (>= a 1e3) (str (trim (.toFixed (/ v 1e3) (if (>= a 1e4) 0 1))) "K")
          :else (str (js/Math.round v)))))

(defn- full [v] (.toLocaleString (js/Math.round v) "en-US"))

(def metrics
  [{:key "cost_usd" :label "Cost" :unit "USD" :note "the agent's list price, not a bill"
    :axis #(str "$" (if (== % (js/Math.round %)) % (.toFixed % 1))) :fmt #(str "$" (.toFixed % 2))}
   {:key "input_tokens" :label "Input tokens" :unit "tokens" :note "incl. cache reads and writes"
    :axis compact :fmt compact :full full}
   {:key "output_tokens" :label "Output tokens" :unit "tokens" :note "incl. thinking"
    :axis compact :fmt compact :full full}
   {:key "wall_min" :label "Wall time" :unit "min" :note "grows with parallel load"
    :axis #(str (js/Math.round %)) :fmt #(str (.toFixed % 1) " min")}
   {:key "attempts" :label "Attempts" :unit "attempts" :note "5 means no step was retried"
    :axis #(str (js/Math.round %)) :fmt #(.toFixed % 1)}
   {:key "tool_calls" :label "Tool calls" :unit "calls" :note "runs that recorded activity"
    :axis #(str (js/Math.round %)) :fmt #(str (js/Math.round %))}])

(def metric-by-key (into {} (map (juxt :key identity)) metrics))

(defn metric [k] (get metric-by-key k (first metrics)))

;; ---- labels

(defn model-label
  "claude-opus-5-5 → Opus 5.5, gpt-6-sol → GPT-6 Sol"
  [m]
  (let [[vendor & parts] (str/split (or m "unknown") #"-")
        [fam & ver] parts]
    (cond
      (and (= vendor "gpt") fam)
      (str "GPT-" fam (when (seq ver) (str " " (str/join " " (map str/capitalize ver)))))
      fam (str (str/capitalize fam) (when (seq ver) (str " " (str/join "." ver))))
      :else (or m "unknown"))))

(defn config-key [r] (str (:model r) "|" (:effort r)))

(defn config-label [{:keys [model effort]}] (str (model-label model) " · " effort))

(defn- effort-rank [e] (let [i (.indexOf efforts e)] (if (neg? i) 99 i)))

(defn- stack-rank [s] (let [i (.indexOf stack-order s)] (if (neg? i) 99 i)))

(defn sort-stacks [ss] (sort-by (juxt stack-rank identity) ss))

;; ---- data access

(defn topic [data id] (first (filter #(= id (:id %)) (:topics data))))

(defn project [data id] (first (filter #(= id (:id %)) (:projects data))))

(defn topic-runs [data topic-id]
  (let [ps (set (:projects (topic data topic-id)))]
    (filter #(ps (:project %)) (:runs data))))

(defn configs
  "Model · effort groups present in runs, models in model order, efforts in order."
  [runs]
  (->> runs
       (map #(select-keys % [:model :effort]))
       distinct
       (sort-by (juxt (comp model-rank :model) :model (comp effort-rank :effort)))
       (mapv #(assoc % :key (config-key %) :label (config-label %)))))

(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (count xs))))

(defn cell
  "Mean of metric k for one stack × config over `project-ids`: per project over
   its complete runs, then across the projects that have any. nil when none."
  [runs project-ids stack cfg-key k]
  (let [kw (keyword k)
        mine (filter #(and (:complete %) (= stack (:stack %)) (= cfg-key (config-key %))
                           (some? (get % kw)))
                     runs)
        per-project (keep (fn [p]
                            (when-let [rs (seq (filter #(= p (:project %)) mine))]
                              {:project p :value (mean (map kw rs)) :runs rs}))
                          project-ids)]
    (when (seq per-project)
      {:value (mean (map :value per-project))
       :projects (mapv :project per-project)
       :coverage [(count per-project) (count project-ids)]
       :runs (vec (mapcat :runs per-project))})))

(defn table
  "Aggregate table for a topic over all its projects: stacks × configs."
  [data topic-id k]
  (let [t (topic data topic-id)
        runs (topic-runs data topic-id)
        cfgs (configs runs)
        stacks (sort-stacks (distinct (map :stack runs)))
        cells (into {} (for [s stacks c cfgs
                             :let [v (cell runs (:projects t) s (:key c) k)]
                             :when v]
                         [[s (:key c)] v]))
        best (into {} (for [c cfgs
                            :let [vs (keep #(get cells [% (:key c)]) stacks)]
                            :when (> (count vs) 1)]
                        [(:key c) (apply min (map :value vs))]))]
    {:topic t :configs cfgs :stacks stacks :cells cells :best best}))

(defn- ranks
  "[stack rank last?] for every config of `tbl` where at least two stacks compete."
  [{:keys [configs stacks cells]}]
  (for [c configs
        :let [vs (->> stacks
                      (keep #(when-let [v (get cells [% (:key c)])] [% (:value v)]))
                      (sort-by second))]
        :when (> (count vs) 1)
        [i [s _]] (map-indexed vector vs)]
    [s i (= i (dec (count vs)))]))

(defn standings
  "Stacks by mean rank over the configs of `tables`, best first:
   {:stack :mean-rank :configs :wins :losses}."
  [tables]
  (let [rs (mapcat ranks tables)]
    (->> (group-by first rs)
         (map (fn [[s xs]] {:stack s :mean-rank (mean (map second xs)) :configs (count xs)
                            :wins (count (filter #(zero? (second %)) xs))
                            :losses (count (filter #(nth % 2) xs))}))
         (sort-by (juxt :mean-rank (comp - :configs))))))

(defn leanest
  "Stack with the lowest mean rank across configs where at least two stacks
   compete; nil when nothing competes."
  [tbl]
  (:stack (first (standings [tbl]))))

(defn topic-totals [data topic-id]
  (let [rs (topic-runs data topic-id)]
    {:runs (count rs)
     :complete (count (filter :complete rs))
     :cost (reduce + (map :cost_usd rs))
     :tokens (reduce + (map #(+ (:input_tokens %) (:output_tokens %)) rs))
     :stacks (count (distinct (map :stack rs)))}))

(defn chart-model
  "What the canvas draws: per stack, one group of cost bars per model, one
   bar per effort level, each the mean over complete runs. :slot picks a
   stack's colour, :rank (the effort) its shade."
  [data {tid :topic pid :project shown :models all :all}]
  (when data
    (let [t (topic data tid)
          ;; projects without runs yet don't count against coverage
          scope (if (= pid "all")
                  (filter (set (map :project (topic-runs data tid))) (:projects t))
                  [(js/parseInt pid)])
          runs (filter #((set scope) (:project %)) (topic-runs data tid))
          ;; a model's texture is its place among all models, not among those shown
          style (into {} (map-indexed (fn [i m] [m i]) (models (:runs data))))
          ms (filter #(or (nil? shown) (shown %)) (models runs))
          ;; colour slots follow all of the topic's stacks, so neither a project
          ;; filter nor hiding the niche ones repaints a stack
          slot (into {} (map-indexed (fn [i s] [s i])
                                     (sort-stacks (distinct (map :stack (topic-runs (or all data) tid))))))
          bar (fn [s m i e]
                (let [k (config-key {:model m :effort e})]
                  (when-let [c (cell runs scope s k "cost_usd")]
                    {:id (str s "|" k) :stack s :slot (slot s) :effort e :rank (effort-rank e) :style i
                     :value (:value c) :runs (:runs c) :coverage (:coverage c)})))]
      {:models (mapv (fn [m] {:model m :label (model-label m) :style (style m)}) ms)
       :efforts (mapv (fn [e] {:effort e :rank (effort-rank e)})
                      (sort-by effort-rank (distinct (map :effort (filter #((set ms) (:model %)) runs)))))
       :stacks (vec (for [s (sort-stacks (distinct (map :stack runs)))
                          :let [series (vec (for [m ms
                                                  :let [bars (vec (keep #(bar s m (style m) %) efforts))]
                                                  :when (seq bars)]
                                              {:model (model-label m) :style (style m) :bars bars}))]
                          :when (seq series)]
                      {:id s :label (display tid s) :series series}))})))
