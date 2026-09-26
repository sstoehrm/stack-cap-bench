(ns scb.data
  "Pure aggregation over results.json. Every value is the mean over *complete*
   runs (every step passed), per project first, then across the projects in
   scope, so a topic with two projects weighs both equally."
  (:require [clojure.string :as str]))

;; Colour follows the stack, never its rank. The five jewel tones pass the
;; dataviz validator on the #0c0a10 surface in this adjacent order.
(def stack-order ["re-frame-clojure" "clojure" "replicant-clojure" "reagami+squint-clojure"
                  "svelte-java" "svelte-kotlin"])

(def stack-colors {"re-frame-clojure" "#e0344c" "clojure" "#e0344c"
                   "replicant-clojure" "#3f7cf0" "reagami+squint-clojure" "#c9830c"
                   "svelte-java" "#15a06f" "svelte-kotlin" "#a86ef2"})

(def fallback-color "#8a8594")

(defn color [stack] (get stack-colors stack fallback-color))

(def efforts ["default" "low" "medium" "high" "xhigh" "max"])

(defn- compact [v]
  (let [a (js/Math.abs v)
        trim #(str/replace % #"\.0$" "")]
    (cond (>= a 1e6) (str (trim (.toFixed (/ v 1e6) (if (>= a 1e7) 0 1))) "M")
          (>= a 1e3) (str (trim (.toFixed (/ v 1e3) (if (>= a 1e4) 0 1))) "K")
          :else (str (js/Math.round v)))))

(defn- full [v] (.toLocaleString (js/Math.round v) "en-US"))

(def metrics
  [{:key "cost_usd" :label "Cost" :unit "USD" :note "claude -p list price, not a bill"
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
  "claude-opus-5-5 → Opus 5.5"
  [m]
  (let [[fam & ver] (rest (str/split (or m "unknown") #"-"))]
    (if fam
      (str (str/capitalize fam) (when (seq ver) (str " " (str/join "." ver))))
      (or m "unknown"))))

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
  "Model · effort groups present in runs, Fable before Opus, efforts in order."
  [runs]
  (->> runs
       (map #(select-keys % [:model :effort]))
       distinct
       (sort-by (juxt :model (comp effort-rank :effort)))
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

(defn leanest
  "Stack with the lowest mean rank across configs where at least two stacks
   compete; nil when nothing competes."
  [{:keys [configs stacks cells]}]
  (let [ranks (for [c configs
                    :let [vs (->> stacks
                                  (keep #(when-let [v (get cells [% (:key c)])] [% (:value v)]))
                                  (sort-by second))]
                    :when (> (count vs) 1)
                    [i [s _]] (map-indexed vector vs)]
                [s i])]
    (when (seq ranks)
      (->> (group-by first ranks)
           (map (fn [[s rs]] [s (mean (map second rs)) (count rs)]))
           (sort-by (juxt second (comp - #(nth % 2))))
           ffirst))))

(defn topic-totals [data topic-id]
  (let [rs (topic-runs data topic-id)]
    {:runs (count rs)
     :complete (count (filter :complete rs))
     :cost (reduce + (map :cost_usd rs))
     :tokens (reduce + (map #(+ (:input_tokens %) (:output_tokens %)) rs))
     :stacks (count (distinct (map :stack rs)))}))

(defn chart-model
  "What the canvas draws: clusters of bars (one per config, bars per stack)."
  [data {tid :topic pid :project mk :metric hidden :hidden}]
  (when data
    (let [t (topic data tid)
          scope (if (= pid "all") (:projects t) [(js/parseInt pid)])
          runs (filter #((set scope) (:project %)) (topic-runs data tid))
          cfgs (remove #(contains? hidden (:key %)) (configs runs))
          stacks (sort-stacks (distinct (map :stack runs)))
          m (metric mk)]
      {:metric m
       :stacks (vec stacks)
       :clusters (vec (for [c cfgs]
                        {:key (:key c) :label (:label c)
                         :bars (vec (for [s stacks
                                          :let [v (cell runs scope s (:key c) mk)]
                                          :when v]
                                      (assoc v :id (str s "|" (:key c)) :stack s
                                             :color (color s) :config (:label c))))}))})))
