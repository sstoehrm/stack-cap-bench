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
  "The canvas's handlers and lifecycle, built once per chart (and again when
   `revealed` flips, since hammer re-derives bindings that name an atom)."
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
   ;; mouse, not pointer, events: a tap fires no pointermove, but the
   ;; compatibility mousemove after it shows the tooltip on touch screens
   :on-mousemove (:move on) :on-mouseleave (:leave on)
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
