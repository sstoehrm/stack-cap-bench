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
    (testing "outside the plot" (is (= [nil nil] [(tw/hit s 50 200) (tw/hit s 500 5)])))))

(deftest idle-until-something-moves
  (is (not (tw/busy? (tw/state) 0)) "a fresh chart has nothing to animate")
  (let [s (doto (tw/state) (tw/size! 800 400) (tw/place! m1 false 0 text-w))]
    (is (not (tw/busy? s 0)) "a snap is settled at once")))
