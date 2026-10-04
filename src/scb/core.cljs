(ns scb.core
  (:require [clojure.string :as str]
            [hammer.core :refer [defc reg-event reg-fx dispatch mount!]]
            [scb.chart :as chart]
            [scb.data :as data]
            [scb.nave :as nave]))

;; ---- events

(defn- with-data
  "Derive :data from everything loaded (:all): serious mode never shows the
   niche stacks, evil mode only once the reader is brave."
  [db]
  (assoc db :data (cond-> (:all db)
                    (and (:all db) (not (and (= "evil" (:mode db)) (:brave db)))) data/without-niche)))

(defn- store! [k v] (try (.setItem js/localStorage k v) (catch :default _ nil)))

(reg-fx :store (fn [[k v]] (store! k v)))

(reg-event :loaded
           (fn [db d]
             (let [d (data/without-hidden-projects d)]
               {:db (with-data (assoc db :all d :topic (:id (first (:topics d)))
                                      :models (data/default-models (:runs d))))})))
(reg-event :failed (fn [db msg] {:db (assoc db :error msg)}))
(reg-event :metric (fn [db k] {:db (assoc db :metric k)}))
(reg-event :topic (fn [db t] {:db (assoc db :topic t :project "all" :hover nil)}))
(reg-event :project (fn [db p] {:db (assoc db :project p :hover nil)}))
(reg-event :creed (fn [db tone] {:db (assoc db :tone (when (not= tone (:tone db)) tone))}))
(reg-event :tone (fn [db tone] {:db (assoc db :tone tone)}))
(reg-event :brave (fn [db on] {:db (with-data (assoc db :brave on :hover nil)) :store ["scb-brave" (str on)]}))
(reg-event :model
           (fn [db m]
             ;; toggle one model; the last one shown stays on
             (let [shown (or (:models db) (set (data/models (:runs (:data db)))))
                   next (if (shown m) (disj shown m) (conj shown m))]
               (when (seq next) {:db (assoc db :models next :hover nil)}))))

(defn- apply-look!
  "Switch stylesheet, <html> attributes, background and chart to `mode`
   (\"serious\" | \"evil\") and `theme` (\"auto\" | \"light\" | \"dark\", serious only)."
  [[mode theme]]
  (let [root (.-documentElement js/document)
        evil? (= mode "evil")]
    (set! (.. root -dataset -mode) mode)
    (if (and (not evil?) (not= theme "auto"))
      (set! (.. root -dataset -theme) theme)
      (.removeAttribute root "data-theme"))
    (.setAttribute (.getElementById js/document "css-evil") "media" (if evil? "all" "not all"))
    (.setAttribute (.getElementById js/document "css-serious") "media" (if evil? "not all" "all"))
    (when (#{"#evil" "#serious"} (.-hash js/location))
      (js/history.replaceState nil "" (str (.-pathname js/location) (.-search js/location))))
    (store! "scb-mode" mode)
    (store! "scb-theme" theme)))

(reg-fx :look apply-look!)

(reg-event :mode (fn [db m] {:db (with-data (assoc db :mode m :hover nil)) :look [m (:theme db)]}))
(reg-event :restyle (fn [db] {:db (update db :look-rev inc)}))
(reg-event :cycle-theme
           (fn [db]
             (let [t (case (:theme db) "auto" "light" "light" "dark" "auto")]
               {:db (assoc db :theme t) :look [(:mode db) t]})))

;; ---- helpers

(def ^:private numerals ["I" "II" "III" "IV" "V" "VI"])

(defn- evil? [mode] (= mode "evil"))

(defn- numeral [mode idx] (if (evil? mode) (nth numerals idx) (str "0" (inc idx))))

(defn- mark [mode] (if (evil? mode) "✠" "↓"))

(defn- usd [v] (str "$" (.toLocaleString (js/Math.round v) "en-US")))

(defn- compact [v] ((:fmt (data/metric "input_tokens")) v))


(defn- chip [on? event label]
  [:button.chip {:class (when on? "on") :aria-pressed (str (boolean on?)) :on-click event} label])

(defn- tile [label value note & [cls]]
  [:div.tile.glass {:class cls} [:span.tile-label label] [:strong.tile-value value] [:span.tile-note note]])

(defn- project-names [d ids]
  (str/join " · " (for [id ids] (str id " " (:name (data/project d id))))))

;; ---- components

(declare creed)

(defc hero []
  [d [:data]
   mode [:mode]
   runs (:runs d)]
  (let [e? (evil? mode)]
    [:header.hero
     [:p.kicker (:source d) " · imported " (:imported d)]
     [:img.hero-logo {:src "assets/stack-cap-bench.svg" :alt "" :width 72 :height 72}]
     [:h1 "Stack Cap Bench"]
     (if e?
       [:p.lede "The dread of being a software engineer: once, we cared so deeply about our stacks, our editors "
        "and our tools that there were heated arguments and whitepapers written. "
        "Now we are all the same, staring into the void at what is to come and where it will lead us. "
        "Will our beloved stacks and languages still matter in one, three, five or ten years? "
        "This benchmark won't answer that."]
       [:p.lede "Benchmarking the token cost of tech stacks on capped projects."])
     [:aside.disclaimer.glass {:aria-label "Disclaimer"}
      [:span.disclaimer-label "Disclaimer"]
      [:p "This benchmark tries to measure the token cost and performance of technology stacks — "
       "for now, on small projects. Treat the numbers as a direction, not a verdict."]
      [creed]]
     [:div.tiles.hero-tiles
      (tile "Runs" (str (count (filter :complete runs)) "/" (count runs)) "complete / recorded")
      (tile "Stacks" (count (distinct (map :stack runs))) (if e? "in the dock" "compared"))
      (tile (if e? "Spent" "Cost") (usd (reduce + (map :cost_usd runs))) "list price, every attempt")
      (tile (if e? "Tokens burnt" "Tokens")
            (compact (reduce + (map #(+ (:input_tokens %) (:output_tokens %)) runs)))
            "input + output")]]))

(defc modebar []
  [mode [:mode]
   theme [:theme]]
  [:div.modebar
   [:div.seg.glass {:role "group" :aria-label "Mode"}
    (for [[k label] [["serious" "Serious"] ["evil" "Evil"]]]
      ^{:key k} [:button {:class (when (= mode k) "on") :aria-pressed (str (= mode k)) :on-click [:mode k]} label])]
   (when-not (evil? mode)
     [:button.theme-btn.glass {:on-click [:cycle-theme] :title "Colour theme: auto, light or dark"}
      (case theme "light" "☀ light" "dark" "☾ dark" "◐ auto")])])

(def ^:private ranks ["I" "II" "III"])

(defn- rank-row [tid i {:keys [stack mean-rank configs wins losses]} good?]
  ^{:key stack}
  [:li.judged
   [:span.judged-rank (nth ranks i)]
   [:span.judged-name (data/display tid stack)]
   [:span.judged-stats
    (if good? (str "leanest in " wins " of " configs) (str "last in " losses " of " configs))
    " · mean rank " (.toFixed (inc mean-rank) 1)]])

(defc judgement [tid]
  [all [:data]
   shown [:models]
   d (data/with-models all shown)
   label (:label (data/topic d tid))
   standing (vec (data/standings [(data/table d tid "cost_usd")]))
   month (.toLocaleDateString (js/Date.) "en-US" #js {:month "long" :year "numeric"})]
  (let [k (min 3 (quot (count standing) 2))
        saved (subvec standing 0 k)
        damned (vec (reverse (subvec standing (- (count standing) k))))
        god (data/display tid (:stack (first saved)))]
    (when (pos? k)
      [:section.lord {:aria-label (str "Judgement by cost: " label)}
       [:div.lord-card.glass
        [:span.lord-kicker "✠ Holy Stacks" [:span.lord-month label " · " month]]
        (into [:ol.judged-list] (map-indexed #(rank-row tid %1 %2 true) saved))
        [:p.lord-decree "These shall ascend. Build thy next greenfield in " [:b god]
         "; rewrite thy monolith this weekend. Thy manager will understand."]
        [:p.lord-fine "Ranked by mean cost rank across every configuration in " label
         ". Chosen by the numbers, which is to say by the Lord."]]
       [:div.lord-card.damned.glass
        [:span.lord-kicker "⛧ Diabolical Stacks" [:span.lord-month label]]
        (into [:ol.judged-list] (map-indexed #(rank-row tid %1 %2 false) damned))
        [:p.lord-decree "Abandon hope, all ye who deploy here. Penance: rewrite everything in " [:b god] "."]
        [:p.lord-fine "Worst first. Confession is accepted as a pull request; absolution is not."]]])))

;; evil mode only: the niche stacks wait outside until the reader lets them in
(defc niche-gate []
  [brave [:brave]
   all [:all]
   names (sort (filter data/niche (distinct (map :stack (:runs all)))))]
  (when (seq names)
    [:aside.gate.glass {:aria-label "Niche frameworks"}
     [:button.gate-btn {:aria-pressed (str (boolean brave)) :on-click [:brave (not brave)]}
      [:span.gate-mark {:aria-hidden "true"} (if brave "✠" "⛧")] "Admit the niche frameworks"]
     [:p.gate-note
      (if brave
        "Admitted to every ranking, table and chart below: "
        (str (count names) " stacks wait outside the gate, for brave souls only: "))
      (str/join ", " names)]]))

(defc metric-bar []
  [metric [:metric]
   mode [:mode]
   m (data/metric metric)]
  [:nav.rood.glass {:aria-label "Measure"}
   [:span.rood-label (if (evil? mode) "Judge by" "Measure")]
   [:div.chips (for [x data/metrics]
                 ^{:key (:key x)} (chip (= metric (:key x)) [:metric (:key x)] (:label x)))]
   [:span.rood-note (:note m)]])

;; which models the tables and the chart show; the last one checked stays on
(defc model-filter []
  [d [:data]
   shown [:models]
   models (data/models (:runs d))]
  [:div.models.glass {:role "group" :aria-label "Models"}
   [:span.control-label {:aria-hidden "true"} "Models"]
   [:div.model-boxes
    (for [m models
          :let [on? (or (nil? shown) (boolean (shown m)))]]
      ^{:key m}
      [:label.model-box
       [:input {:type "checkbox" :checked on? :on-change [:model m]
                :disabled (and on? shown (= 1 (count shown)))}]
       (data/model-label m)])]])

(defc topic-section [tid idx]
  [all [:data]
   shown [:models]
   d (data/with-models all shown)
   mode [:mode]
   metric [:metric]
   m (data/metric metric)
   tbl (data/table d tid metric)
   totals (data/topic-totals d tid)
   lean (data/leanest tbl)]
  (let [{:keys [topic configs stacks cells best]} tbl]
    [:section.topic {:id (str "topic-" tid)}
     [:header.topic-head
      [:span.numeral (numeral mode idx)]
      [:div [:h2 (:label topic)]
       [:p.topic-sub "Project" (when (> (count (:projects topic)) 1) "s") " " (project-names d (:projects topic))]]]
     [:div.tiles
      (tile "Runs" (str (:complete totals) "/" (:runs totals)) "complete / recorded")
      (tile (if (evil? mode) "Spent" "Cost") (usd (:cost totals)) "list price, every attempt")
      (tile (if (evil? mode) "Tokens burnt" "Tokens") (compact (:tokens totals)) "input + output")
      (tile (if (evil? mode) "Leanest" "Lowest") (if lean (data/display tid lean) "—")
            (str "best mean rank by " (str/lower-case (:label m))) "tile-name")]
     [:div.table-wrap.glass
      [:table
       [:caption (:label m) " — mean over complete runs"
        (when (> (count (:projects topic)) 1) ", averaged across projects")
        ". " (mark mode) " marks the " (if (evil? mode) "leanest stack" "lowest value")
        " per configuration; superscript is the run count."]
       [:thead [:tr [:th {:scope "col"} "Stack"]
                (for [c configs]
                  (let [[mdl eff] (str/split (:label c) #" · ")]
                    ^{:key (:key c)} [:th {:scope "col"} mdl [:span.eff eff]]))]]
       [:tbody
        (for [s stacks]
          ^{:key s}
          [:tr [:th {:scope "row"} (data/display tid s)]
           (for [c configs]
             (let [v (get cells [s (:key c)])
                   win? (and v (= (:value v) (get best (:key c))))]
               ^{:key (:key c)}
               [:td {:class (when win? "best")}
                (if v
                  [:span (when win? [:span.cross {:aria-label "lowest"} (str (mark mode) " ")])
                   ((:fmt m) (:value v)) [:sup (count (:runs v))]]
                  [:span.none "—"])]))])]]]]))

(def ^:private creed-text
  {:semi
   [[:p "Picking a framework for a greenfield project used to be a question about people: who knows it, "
     "who can hire for it, how it feels to write every day. Now an agent writes a good part of the code. "
     "I want to be aware of the trade-offs of that choice in the agentic age — and to find out whether "
     "it even still matters."]
    [:p "If every stack costs an agent about the same, pick whatever you enjoy. If not, the difference "
     "should show up somewhere measurable: safety, performance, tokens and time. This page collects that "
     "evidence for some of these important dimensions."]]
   :method
   [[:ul
     [:li "Three simple projects: a log-analyzer CLI, an expense tracker and a kanban board. "
      "Each is built in five steps, and every step has an exact target."]
     [:li "A headless coding agent builds them in every stack, each run in a fresh workspace, "
      "with nobody around to answer its questions."]
     [:li "For every step, the agent gets the feature to build and how the program has to run: "
      "the start command, where the data lives, which port to use."]
     [:li "It also gets the stack, meaning the languages and libraries to use, and, for the web projects, "
      "the exact API and page markup the result must have."]
     [:li "After every step, a harness checks the CLI output, the API contract and the UI, "
      "using a headless browser and screenshots. Failures go back to the agent until the harness "
      "approves the step, up to three attempts."]]]})

;; the disclaimer's links; each opens its section below them, one at a time
(defc creed []
  [tone [:tone]
   mode [:mode]
   e? (evil? mode)
   tabs (if e?
          [[:semi "Read the vision"] [:method "or how the data is gathered"]]
          [[:semi "Vision"] [:method "How the data is gathered"]])
   shown (some #{tone} (map first tabs))]
  [:div
   (into [:p.disclaimer-links]
         (interpose [:span.sep "·"]
                    (for [[k label] tabs]
                      [:button.linkish {:on-click [:creed k] :aria-expanded (str (= shown k))
                                        :aria-controls "creed-text"} label])))
   (when shown
     (into [:div#creed-text.creed-text {:role "region" :aria-label (some #(when (= shown (first %)) (second %)) tabs)}]
           (creed-text shown)))])

(defc nave-chart [idx]
  [d [:data]
   mode [:mode]
   tid [:topic]
   pid [:project]
   shown [:models]
   all [:all]
   topic (data/topic d tid)
   model (data/chart-model d {:topic tid :project pid :models shown :all all})]
  [:section.topic.nave-section {:id "chart"}
   [:header.topic-head
    [:span.numeral (numeral mode idx)]
    [:div [:h2 (if (evil? mode) "The Stained Glass" "Compare")]
     [:p.topic-sub (if (evil? mode) "The tithe of every effort. " "Cost at every effort level. ")
      "Each stack gets one bar per model and effort; an even staircase means cost rises in step with the effort."]]]
   [:div.controls.glass
    [:div.control [:span.control-label "Topic"]
     [:div.chips (for [t (:topics d)] ^{:key (:id t)} (chip (= tid (:id t)) [:topic (:id t)] (:label t)))]]
    [:div.control [:span.control-label "Project"]
     [:div.chips
      (chip (= pid "all") [:project "all"] "All")
      (for [p (:projects topic)]
        ^{:key p} (chip (= pid (str p)) [:project (str p)] (str p " " (:name (data/project d p)))))]]]
   [:div.canvas-wrap.glass [:div.canvas-box [chart/chart model] [chart/chart-tip model]]]
   [:p.chart-note
    "Each bar is the mean cost over complete runs for one stack, model and effort level. "
    "Each stack has its own colour; within a group the bars run from the lowest effort (lightest) "
    "to the highest (darkest), one texture per model, as the legend shows. "
    "Hover, or focus the chart and use the arrow keys, to pick out a stack and see its numbers."]])

(defc app []
  [d [:data]
   err [:error]
   mode [:mode]]
  [:div.shell
   (cond
     err [:p.state "Could not load results.json: " err]
     (nil? d) [:p.state "Loading…"]
     :else
     (list
      ^{:key "modebar"} [modebar]
      ^{:key "hero"} [hero]
      (when (evil? mode) ^{:key "gate"} [niche-gate])
      (when (evil? mode)
        ^{:key "judgement"} [:div (for [t (:topics d)] ^{:key (:id t)} [judgement (:id t)])])
      ^{:key "models"} [model-filter]
      ^{:key "bar"} [metric-bar]
      ^{:key "main"}
      [:main
       (for [[i t] (map-indexed vector (:topics d))]
         ^{:key (:id t)} [topic-section (:id t) i])
       ^{:key "chart"} [nave-chart (count (:topics d))]]
      ^{:key "foot"}
      [:footer.foot
       [:p "Data: " [:code "results.json"] " — hand-editable, or regenerate it with "
        [:code "bb import ../token-comparision"] "."]
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

(defn- stored [k default] (or (try (.getItem js/localStorage k) (catch :default _ nil)) default))

(defn ^:export main []
  (let [mode (or (.. js/document -documentElement -dataset -mode) "serious")
        theme (stored "scb-theme" "auto")]
    (when (evil? mode) (nave/start!))
    (.addEventListener (js/matchMedia "(prefers-color-scheme: dark)") "change" #(dispatch [:restyle]))
    (.then (.-ready (.-fonts js/document)) #(dispatch [:restyle]))
    (mount! [app] (.getElementById js/document "app")
            {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :tone nil
             :mode mode :theme theme :brave (= "true" (stored "scb-brave" "false")) :models nil
             :hover nil :look-rev 0})
    (load!)))
