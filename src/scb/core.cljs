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
        [:a {:href "https://github.com/sstoehrm/hammer"} "hammer"] "; the charts are drawn by hand on canvas."]]))])

;; ---- start

(defn- load! []
  (-> (js/fetch "results.json" #js {:cache "no-cache"})
      (.then (fn [^js r] (if (.-ok r) (.json r) (throw (js/Error. (str "results.json: HTTP " (.-status r)))))))
      (.then #(dispatch [:loaded (js->clj % :keywordize-keys true)]))
      (.catch #(dispatch [:failed (str (.-message %))]))))

(defn ^:export main []
  (nave/start!)
  (mount! [app] (.getElementById js/document "app")
          {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :hidden #{}})
  (load!))
