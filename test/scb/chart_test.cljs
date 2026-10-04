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
