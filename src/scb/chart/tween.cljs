(ns scb.chart.tween
  "Where the chart's bars and labels are heading and where they are now:
   layout and tweens, no DOM. One mutable state object per chart; times are
   the chart loop's :t in ms. Every change tweens from what is on screen to
   the new target.")

(def dur 850)
(def ^:private stagger 24)

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

(defn- ease [t] (let [u (- 1 (min 1 (max 0 t)))] (- 1 (* u u u))))

(defn tween-val
  "Current value of a {from to t0 delay} tween."
  [^js tw t]
  (let [p (ease (/ (- t (.-t0 tw) (or (.-delay tw) 0)) dur))]
    (+ (.-from tw) (* p (- (.-to tw) (.-from tw))))))

(defn settled? [^js tw t] (>= (- t (.-t0 tw) (or (.-delay tw) 0)) dur))

(defn- tw [from to t0 delay] #js {:from from :to to :t0 t0 :delay delay})

(defn- retarget
  "Point an existing tween at a new target, starting from where it is now."
  [^js old to t0 delay]
  (if old (tw (tween-val old t0) to t0 delay) (tw to to t0 0)))

(defn nice-ticks [mx]
  (if-not (pos? mx)
    [0 1]
    (let [raw (/ mx 4)
          mag (js/Math.pow 10 (js/Math.floor (js/Math.log10 raw)))
          step (some #(when (>= % raw) %) (map #(* % mag) [1 2 2.5 5 10]))
          n (js/Math.ceil (- (/ mx step) 1e-9))]
      (mapv #(* % step) (range (inc n))))))

;; the gap between a stack's models, in bar widths
(def ^:private series-gap 0.6)

(defn- group-units
  "Width of a stack's group in bar widths: its bars plus the gaps between models."
  [s]
  (+ (count (mapcat :bars (:series s))) (* series-gap (dec (count (:series s))))))

(defn layout
  "One slot per stack, the same bar width everywhere: target x/width for
   every bar and the centre of every group."
  [^js s model]
  (let [m (.-margin s)
        l (.-l m)
        stacks (:stacks model)
        n (max 1 (count stacks))
        slot (/ (- (.-w s) l (.-r m)) n)
        bw (min 16 (/ (* slot 0.84) (reduce max 1 (map group-units stacks))))
        gap (min 2 (* bw 0.2))]
    {:slot slot
     :centres (into {} (map-indexed (fn [i st] [(:id st) (+ l (* (+ i 0.5) slot))]) stacks))
     :bars (into {}
                 (for [[i st] (map-indexed vector stacks)
                       :let [x0 (- (+ l (* (+ i 0.5) slot)) (/ (* bw (group-units st)) 2))]
                       [k [j b]] (map-indexed vector (for [[j sr] (map-indexed vector (:series st))
                                                           b (:bars sr)]
                                                       [j b]))]
                   ;; bar k sits after k bars and one gap per earlier model
                   [(:id b) {:x (+ x0 (* bw (+ k (* series-gap j))) (/ gap 2)) :w (- bw gap)}]))}))

(defn- fit-margins!
  "Room for the stack labels, rotated by 50 degrees and ending under their
   group: the bottom margin fits the longest, and the left margin grows (from
   the width's base margin) until no label runs past the canvas edge.
   `text-w` measures a label in the label font."
  [^js s model text-w]
  (let [m (.-margin s)
        stacks (:stacks model)
        ws (mapv (comp text-w :label) stacks)
        base-l (.-lbase s)
        n (max 1 (count stacks))
        ;; how far left of its centre a label reaches: text plus glyph height
        reach (fn [w] (+ (* 0.643 w) (* 0.766 11)))
        fit-l (fn [l]
                (let [slot (/ (- (.-w s) l (.-r m)) n)]
                  (reduce max base-l (map-indexed (fn [i w] (+ 6 (- (reach w) (* slot (+ i 0.5))))) ws))))]
    (set! (.-b m) (+ 24 (* 0.77 (reduce max 50 ws))))
    (set! (.-l m) (-> base-l fit-l fit-l fit-l))))

(defn place!
  "Retarget every bar and label (and the y max) at `model` from time `t`;
   what vanishes falls and fades. With `move?` false (a resize or restyle)
   positions snap instead."
  [^js s model move? t text-w]
  (let [bars ^js (.-bars s)
        labels ^js (.-labels s)
        _ (fit-margins! s model text-w)
        {:keys [slot centres] :as pos} (layout s model)
        snap? (or (.-reduced s) (not move?))
        ticks (nice-ticks (reduce max 0 (for [st (:stacks model) sr (:series st) b (:bars sr)] (:value b))))]
    (set! (.-slot s) slot)
    (set! (.-ymax s) (if snap? (tw (peek ticks) (peek ticks) t 0) (retarget (.-ymax s) (peek ticks) t 0)))
    (doseq [[i st] (map-indexed vector (:stacks model))
            :let [delay (if snap? 0 (* stagger i))
                  cx (centres (:id st))
                  ^js ol (.get labels (:id st))]]
      (.set labels (:id st)
            (if (or snap? (nil? ol))
              #js {:x (tw cx cx t 0)
                   :a (cond (and ol (not move?)) (.-a ol)
                            snap? (tw 1 1 t 0)
                            :else (tw 0 1 t delay))
                   :d st}
              #js {:x (retarget (.-x ol) cx t delay) :a (retarget (.-a ol) 1 t delay) :d st}))
      (doseq [sr (:series st)
              b (:bars sr)
              :let [{:keys [x w]} (get-in pos [:bars (:id b)])
                    v (:value b)
                    ^js old (.get bars (:id b))
                    fresh? (or (nil? old) (zero? (.. old -a -to)))]]
        (.set bars (:id b)
              (if snap?
                #js {:v (if (and old (not move?)) (.-v old) (tw v v t 0))
                     :a (if (and old (not move?)) (.-a old) (tw 1 1 t 0))
                     :x (tw x x t 0) :w (tw w w t 0) :d b}
                #js {:v (if old (retarget (.-v old) v t delay) (tw 0 v t delay))
                     :a (if old (retarget (.-a old) 1 t delay) (tw 0 1 t delay))
                     :x (if fresh? (tw x x t 0) (retarget (.-x old) x t delay))
                     :w (if fresh? (tw w w t 0) (retarget (.-w old) w t delay))
                     :d b}))))
    (when move?
      (doseq [[id ^js b] (es6-iterator-seq (.entries bars))
              :when (not (contains? (:bars pos) id))]
        (if (.-reduced s)
          (.delete bars id)
          (do (set! (.-v b) (retarget (.-v b) 0 t 0))
              (set! (.-a b) (retarget (.-a b) 0 t 0)))))
      (doseq [[id ^js l] (es6-iterator-seq (.entries labels))
              :when (not (contains? centres id))]
        (if (.-reduced s) (.delete labels id) (set! (.-a l) (retarget (.-a l) 0 t 0)))))
    (set! (.-order s) (clj->js (mapv :id (:stacks model))))
    (set! (.-model s) model)))

(defn busy?
  "True while anything is still moving at time `t`."
  [^js s t]
  (or (not (settled? (.-ymax s) t))
      (some (fn [^js b] (not (and (settled? (.-v b) t) (settled? (.-a b) t) (settled? (.-x b) t))))
            (es6-iterator-seq (.values ^js (.-bars s))))
      (some (fn [^js l] (not (and (settled? (.-x l) t) (settled? (.-a l) t))))
            (es6-iterator-seq (.values ^js (.-labels s))))))

(defn hit
  "The stack whose slot is under the pointer, from the plot's top down to its labels."
  [^js s mx my]
  (let [order (.-order s)
        m (.-margin s)
        i (js/Math.floor (/ (- mx (.-l m)) (.-slot s)))]
    (when (and (<= (- (.-t m) 30) my) (< -1 i (.-length order)))
      (aget order i))))

(defn stack [^js s id] (first (filter #(= id (:id %)) (:stacks (.-model s)))))
