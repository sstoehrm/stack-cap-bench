(ns scb.chart
  "The one chart: cost bars grouped by stack, one bar per model and effort
   level, so an even climb from medium to xhigh shows at a glance. Each stack
   has its own hue, each effort level a shade of it (lighter is lower), and
   each model its own texture. Lancet windows in evil mode, plain
   rounded bars in serious mode. Every change tweens from what is on screen
   to the new target. Colours and fonts come from the stylesheet."
  (:require [clojure.string :as str]))

(defonce ^:private theme
  #js {:evil false :sl 0.62 :sc 0.14
       :ink "#ddd" :ink2 "#aaa" :grid "rgba(255,255,255,0.07)" :base "rgba(120,108,134,0.9)"
       :label "Cinzel, serif" :num "'JetBrains Mono', monospace" :frame "rgba(44,40,50,0.95)"
       :hot "rgba(214,204,188,0.75)"})

(def ^:private dur 850)
(def ^:private stagger 24)
(def ^:private margin #js {:l 68 :r 18 :t 44 :b 70})

(defonce ^:private st
  #js {:canvas nil :ctx nil :tip nil :w 0 :h 0 :dpr 1
       :model nil :pending nil :revealed false :visible false
       :bars (js/Map.) :labels (js/Map.) :order #js [] :slot 1 :ymax #js {:from 1 :to 1 :t0 0}
       :hover nil :raf nil :reduced false :ro nil :io nil})

(defn- now [] (js/performance.now))

(defn- ease [t] (let [u (- 1 (min 1 (max 0 t)))] (- 1 (* u u u))))

(defn- tween-val
  "Current value of a {from to t0 delay} tween."
  [^js tw t]
  (let [p (ease (/ (- t (.-t0 tw) (or (.-delay tw) 0)) dur))]
    (+ (.-from tw) (* p (- (.-to tw) (.-from tw))))))

(defn- settled? [^js tw t] (>= (- t (.-t0 tw) (or (.-delay tw) 0)) dur))

(defn- tw [from to t0 delay] #js {:from from :to to :t0 t0 :delay delay})

(defn- retarget
  "Point an existing tween at a new target, starting from where it is now."
  [^js old to t0 delay]
  (if old (tw (tween-val old t0) to t0 delay) (tw to to t0 0)))

;; ---- scales and colour

(defn nice-ticks [mx]
  (if-not (pos? mx)
    [0 1]
    (let [raw (/ mx 4)
          mag (js/Math.pow 10 (js/Math.floor (js/Math.log10 raw)))
          step (some #(when (>= % raw) %) (map #(* % mag) [1 2 2.5 5 10]))
          n (js/Math.ceil (- (/ mx step) 1e-9))]
      (mapv #(* % step) (range (inc n))))))

(defn- fmt-axis [v] (str "$" (if (== v (js/Math.round v)) v (.toFixed v 1))))

(defn- fmt-usd [v] (str "$" (.toFixed v 2)))

(defn- mix
  "#rrggbb blended toward white (amt > 0) or black (amt < 0), as #rrggbb."
  [hex amt]
  (let [n (js/parseInt (subs hex 1) 16)
        f (fn [c] (js/Math.round (if (pos? amt) (+ c (* (- 255 c) amt)) (* c (+ 1 amt)))))
        h (fn [c] (.padStart (.toString (f c) 16) 2 "0"))]
    (str "#" (h (bit-and (bit-shift-right n 16) 255)) (h (bit-and (bit-shift-right n 8) 255))
         (h (bit-and n 255)))))

(defn- oklch
  "OKLCH to #rrggbb, clipped to sRGB."
  [l c h]
  (let [r (/ (* h js/Math.PI) 180)
        a (* c (js/Math.cos r))
        b (* c (js/Math.sin r))
        cube #(* % % %)
        l' (cube (+ l (* 0.3963377774 a) (* 0.2158037573 b)))
        m' (cube (- l (* 0.1055613458 a) (* 0.0638541728 b)))
        s' (cube (- l (* 0.0894841775 a) (* 1.2914855480 b)))
        hx (fn [v]
             (let [v (max 0 (min 1 v))
                   v (if (<= v 0.0031308) (* 12.92 v) (- (* 1.055 (js/Math.pow v (/ 1 2.4))) 0.055))]
               (.padStart (.toString (js/Math.round (* v 255)) 16) 2 "0")))]
    (str "#" (hx (+ (* 4.0767416621 l') (* -3.3077115913 m') (* 0.2309699292 s')))
         (hx (+ (* -1.2684380046 l') (* 2.6097574011 m') (* -0.3413193965 s')))
         (hx (+ (* -0.0041960863 l') (* -0.7034186147 m') (* 1.7076147010 s'))))))

;; Stack hues: 15 steps around the circle ([step, lightness sign]), ordered so
;; that neighbouring groups stay apart for red-green colour blindness too
;; (validated for the first 11 and all 15: adjacent CVD ΔE >= 15 on the light,
;; dark and evil surfaces).
(def ^:private hues
  [[0 -1] [12 1] [5 -1] [10 -1] [7 1] [11 -1] [4 -1] [8 1] [1 -1] [13 1] [3 1] [14 -1] [2 1] [9 1] [6 -1]])

;; effort rank of "high", the stack's own shade; lower efforts lighter, higher darker
(def ^:private high-rank 3)

(defn- shade [rank] (* 0.09 (- high-rank (min rank 5))))

(defn- bar-color
  "Stack `slot` in the shade of effort `rank`; chroma and lightness per mode."
  [slot rank]
  (let [[k sign] (nth hues (mod slot (count hues)))]
    (oklch (+ (.-sl theme) (* sign 0.07) (shade rank)) (.-sc theme) (+ 20 (/ (* 360 k) (count hues))))))

(defn- texture!
  "Cut a model's texture out of the current path: the first model is solid,
   then diagonal stripes, horizontal stripes and dots."
  [^js ctx style x top w h]
  (when (pos? style)
    (.save ctx)
    (.clip ctx)
    (set! (.-globalCompositeOperation ctx) "destination-out")
    (set! (.-strokeStyle ctx) "rgba(0,0,0,0.62)")
    (set! (.-fillStyle ctx) "rgba(0,0,0,0.62)")
    (set! (.-lineWidth ctx) 1.8)
    (.beginPath ctx)
    (case (mod (dec style) 3)
      0 (doseq [k (range (- (js/Math.ceil (/ h 5))) (js/Math.ceil (/ w 5)))]
          (let [x1 (+ x (* k 5))]
            (.moveTo ctx x1 (+ top h)) (.lineTo ctx (+ x1 h) top)))
      1 (doseq [k (range (js/Math.ceil (/ h 4.5)))]
          (let [y1 (- (+ top h) (* k 4.5) 2)]
            (.moveTo ctx x y1) (.lineTo ctx (+ x w) y1)))
      2 (doseq [row (range (js/Math.ceil (/ h 4.5)))
                col (range -1 (js/Math.ceil (/ w 4.5)))]
          (let [cx (+ x 2.25 (* col 4.5) (if (odd? row) 2.25 0))
                cy (- (+ top h) 2.25 (* row 4.5))]
            (.moveTo ctx (+ cx 1.3) cy)
            (.arc ctx cx cy 1.3 0 (* 2 js/Math.PI)))))
    (if (= 2 (mod (dec style) 3)) (.fill ctx) (.stroke ctx))
    (.restore ctx)))

;; ---- layout

;; the gap between a stack's models, in bar widths
(def ^:private series-gap 0.6)

(defn- group-units
  "Width of a stack's group in bar widths: its bars plus the gaps between models."
  [s]
  (+ (count (mapcat :bars (:series s))) (* series-gap (dec (count (:series s))))))

(defn- layout
  "One slot per stack, the same bar width everywhere: target x/width for
   every bar and the centre of every group."
  [model w]
  (let [l (.-l margin)
        stacks (:stacks model)
        n (max 1 (count stacks))
        slot (/ (- w l (.-r margin)) n)
        bw (min 16 (/ (* slot 0.84) (reduce max 1 (map group-units stacks))))
        gap (min 2 (* bw 0.2))]
    {:slot slot
     :centres (into {} (map-indexed (fn [i s] [(:id s) (+ l (* (+ i 0.5) slot))]) stacks))
     :bars (into {}
                 (for [[i s] (map-indexed vector stacks)
                       :let [x0 (- (+ l (* (+ i 0.5) slot)) (/ (* bw (group-units s)) 2))]
                       [k [j b]] (map-indexed vector (for [[j sr] (map-indexed vector (:series s))
                                                           b (:bars sr)]
                                                       [j b]))]
                   ;; bar k sits after k bars and one gap per earlier model
                   [(:id b) {:x (+ x0 (* bw (+ k (* series-gap j))) (/ gap 2)) :w (- bw gap)}]))}))

(defn- fit-margins!
  "Room for the stack labels, rotated by 50 degrees and ending under their
   group: the bottom margin fits the longest, and the left margin grows (from
   the width's base margin) until no label runs past the canvas edge."
  [model]
  (let [stacks (:stacks model)
        ^js ctx (.-ctx st)
        _ (when ctx (set! (.-font ctx) (str "500 11px " (.-num theme))))
        text-w (fn [s] (if ctx (.-width (.measureText ctx (:label s))) (* 7 (count (:label s)))))
        ws (mapv text-w stacks)
        base-l (or (.-lbase st) 68)
        n (max 1 (count stacks))
        ;; how far left of its centre a label reaches: text plus glyph height
        reach (fn [w] (+ (* 0.643 w) (* 0.766 11)))
        fit-l (fn [l]
                (let [slot (/ (- (.-w st) l (.-r margin)) n)]
                  (reduce max base-l (map-indexed (fn [i w] (+ 6 (- (reach w) (* slot (+ i 0.5))))) ws))))]
    (set! (.-b margin) (+ 24 (* 0.77 (reduce max 50 ws))))
    (set! (.-l margin) (-> base-l fit-l fit-l fit-l))))

(defn- place!
  "Retarget every bar and label (and the y max) at `model`; what vanishes
   falls and fades. With `move?` false (a resize) positions snap instead."
  [model move?]
  (let [t (now)
        bars ^js (.-bars st)
        labels ^js (.-labels st)
        _ (fit-margins! model)
        {:keys [slot centres] :as pos} (layout model (.-w st))
        snap? (or (.-reduced st) (not move?))
        ticks (nice-ticks (reduce max 0 (for [s (:stacks model) sr (:series s) b (:bars sr)] (:value b))))]
    (set! (.-slot st) slot)
    (set! (.-ymax st) (if snap? (tw (peek ticks) (peek ticks) t 0) (retarget (.-ymax st) (peek ticks) t 0)))
    (doseq [[i s] (map-indexed vector (:stacks model))
            :let [delay (if snap? 0 (* stagger i))
                  cx (centres (:id s))
                  ^js ol (.get labels (:id s))]]
      (.set labels (:id s)
            (if (or snap? (nil? ol))
              #js {:x (tw cx cx t 0)
                   :a (cond (and ol (not move?)) (.-a ol)
                            snap? (tw 1 1 t 0)
                            :else (tw 0 1 t delay))
                   :d s}
              #js {:x (retarget (.-x ol) cx t delay) :a (retarget (.-a ol) 1 t delay) :d s}))
      (doseq [sr (:series s)
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
        (if (.-reduced st)
          (.delete bars id)
          (do (set! (.-v b) (retarget (.-v b) 0 t 0))
              (set! (.-a b) (retarget (.-a b) 0 t 0)))))
      (doseq [[id ^js l] (es6-iterator-seq (.entries labels))
              :when (not (contains? centres id))]
        (if (.-reduced st) (.delete labels id) (set! (.-a l) (retarget (.-a l) 0 t 0)))))
    (set! (.-order st) (clj->js (mapv :id (:stacks model))))
    (set! (.-model st) model)))

;; ---- drawing

(defn- lancet-path!
  "Pointed-arch window from baseline `base` up to apex `top`."
  [^js ctx x top w base]
  (let [h (- base top)
        ah (min (* w 0.95) h)
        mid (+ x (/ w 2))]
    (.beginPath ctx)
    (.moveTo ctx x base)
    (.lineTo ctx x (+ top ah))
    (.bezierCurveTo ctx x (+ top (* ah 0.42)) (- mid (* w 0.1)) (+ top (* ah 0.1)) mid top)
    (.bezierCurveTo ctx (+ mid (* w 0.1)) (+ top (* ah 0.1)) (+ x w) (+ top (* ah 0.42)) (+ x w) (+ top ah))
    (.lineTo ctx (+ x w) base)
    (.closePath ctx)))

(defn- draw-lancet!
  "Evil mode: a narrow lancet window of leaded glass, glowing when hot."
  [^js ctx color style x top w base alpha hot? dim?]
  (let [h (- base top)]
    (when (and (> h 0.5) (> w 1))
      (.save ctx)
      (set! (.-globalAlpha ctx) (* alpha (if dim? 0.35 1)))
      (set! (.-shadowColor ctx) (if hot? "rgba(150,20,36,0.7)" "rgba(0,0,0,0)"))
      (set! (.-shadowBlur ctx) (if hot? 18 0))
      (lancet-path! ctx x top w base)
      (let [g (.createLinearGradient ctx 0 top 0 base)]
        (.addColorStop g 0 (if hot? (mix color 0.12) color))
        (.addColorStop g 0.35 (mix color -0.4))
        (.addColorStop g 1 (mix color -0.88))
        (set! (.-fillStyle ctx) g))
      (.fill ctx)
      (set! (.-shadowBlur ctx) 0)
      ;; leaded lattice for the first model, every other one's texture in its place
      (if (pos? style)
        (texture! ctx style x top w h)
        (do (.save ctx)
            (.clip ctx)
            (set! (.-strokeStyle ctx) "rgba(2,1,3,0.75)")
            (set! (.-lineWidth ctx) 1)
            (.beginPath ctx)
            (let [step 11]
              (doseq [k (range (- (js/Math.ceil (/ h step))) (js/Math.ceil (/ (+ w h) step)))]
                (let [x1 (+ x (* k step))]
                  (.moveTo ctx x1 base) (.lineTo ctx (+ x1 h) top)
                  (.moveTo ctx (+ x1 h) base) (.lineTo ctx x1 top))))
            (.stroke ctx)
            (.restore ctx)))
      (lancet-path! ctx x top w base)
      (set! (.-strokeStyle ctx) (if hot? (.-hot theme) (.-frame theme)))
      (set! (.-lineWidth ctx) (if hot? 1.6 1.2))
      (.stroke ctx)
      (.restore ctx))))

(defn- plain-path! [^js ctx x top w base]
  (let [r (min 1.5 (/ w 2) (- base top))]
    (.beginPath ctx)
    (.moveTo ctx x base)
    (.lineTo ctx x (+ top r))
    (.arcTo ctx x top (+ x r) top r)
    (.lineTo ctx (- (+ x w) r) top)
    (.arcTo ctx (+ x w) top (+ x w) (+ top r) r)
    (.lineTo ctx (+ x w) base)
    (.closePath ctx)))

(defn- draw-plain!
  "Serious mode: a flat bar, rounded at the top, anchored to the baseline."
  [^js ctx color style x top w base alpha hot? dim?]
  (let [h (- base top)]
    (when (and (> h 0.5) (> w 1))
      (do
        (.save ctx)
        (set! (.-globalAlpha ctx) (* alpha (if dim? 0.3 1)))
        (set! (.-fillStyle ctx) color)
        (plain-path! ctx x top w base)
        (.fill ctx)
        (texture! ctx style x top w h)
        (when hot?
          (plain-path! ctx x top w base)
          (set! (.-strokeStyle ctx) (.-hot theme))
          (set! (.-lineWidth ctx) 1.5)
          (.stroke ctx))
        (.restore ctx)))))

(defn- text! [^js ctx s px py align color font]
  (set! (.-textAlign ctx) align)
  (set! (.-fillStyle ctx) color)
  (set! (.-font ctx) font)
  (.fillText ctx s px py))

(defn- swatch!
  "A neutral legend swatch in the shade of effort `rank`, in texture `style`."
  [^js ctx x y rank style]
  (set! (.-fillStyle ctx) (oklch (+ (.-sl theme) (shade rank)) 0 0))
  (.beginPath ctx) (.rect ctx x (- y 6) 10 12) (.fill ctx)
  (.rect ctx x (- y 6) 10 12)
  (texture! ctx style x (- y 6) 10 12))

(defn- draw-legend!
  "Right-aligned in the top row: the effort shades, lightest first, then the
   models' textures."
  [^js ctx model]
  (let [font (str "500 11px " (.-num theme))
        y 16
        items (concat (for [e (:efforts model)] [(:effort e) (:rank e) 0])
                      (for [m (:models model)] [(:label m) high-rank (:style m)]))]
    (set! (.-font ctx) font)
    (set! (.-textBaseline ctx) "middle")
    (loop [items (reverse items) x (- (.-w st) (.-r margin))]
      (when-let [[label rank style] (first items)]
        (let [x0 (- x (.-width (.measureText ctx label)) 16)]
          (swatch! ctx x0 y rank style)
          (text! ctx label (+ x0 16) y "left" (.-ink2 theme) font)
          (recur (rest items) (- x0 (if (= label (:label (first (:models model)))) 30 16))))))))

(defn- draw! [t]
  (let [^js ctx (.-ctx st)
        w (.-w st) h (.-h st)
        model (.-model st)
        evil (.-evil theme)
        num (.-num theme)]
    (when (and ctx model (pos? w))
      (.setTransform ctx (.-dpr st) 0 0 (.-dpr st) 0 0)
      (.clearRect ctx 0 0 w h)
      (let [base (- h (.-b margin))
            top0 (.-t margin)
            ymax (max 1e-9 (tween-val (.-ymax st) t))
            y (fn [v] (- base (* (- base top0) (/ v ymax))))
            hover (.-hover st)
            bars ^js (.-bars st)
            labels ^js (.-labels st)]
        ;; grid and y axis
        (set! (.-font ctx) (str "500 11px " num))
        (set! (.-textAlign ctx) "right")
        (set! (.-textBaseline ctx) "middle")
        (doseq [tk (nice-ticks (.. st -ymax -to))
                :when (<= tk (* ymax 1.0001))]
          (let [yy (js/Math.round (y tk))]
            (set! (.-strokeStyle ctx) (if (zero? tk) "rgba(0,0,0,0)" (.-grid theme)))
            (set! (.-lineWidth ctx) 1)
            (.beginPath ctx) (.moveTo ctx (.-l margin) (+ yy 0.5)) (.lineTo ctx (- w (.-r margin)) (+ yy 0.5)) (.stroke ctx)
            (set! (.-fillStyle ctx) (.-ink2 theme))
            (.fillText ctx (fmt-axis tk) (- (.-l margin) 12) yy)))
        (text! ctx (if evil "COST · USD" "Cost · USD") (.-l margin) 16 "left" (.-ink2 theme)
               (str "600 10px " (.-label theme)))
        (draw-legend! ctx model)
        ;; bars
        (doseq [[id ^js b] (es6-iterator-seq (.entries bars))]
          (let [a (tween-val (.-a b) t)
                d (.-d b)]
            (if (and (< a 0.01) (settled? (.-a b) t) (zero? (.. b -a -to)))
              (.delete bars id)
              ((if evil draw-lancet! draw-plain!)
               ctx (bar-color (:slot d) (:rank d)) (:style d) (tween-val (.-x b) t) (y (tween-val (.-v b) t)) (tween-val (.-w b) t)
               base a (= hover (:stack d)) (and hover (not= hover (:stack d)))))))
        ;; baseline
        (if evil
          (let [g (.createLinearGradient ctx 0 base 0 (+ base 6))]
            (.addColorStop g 0 (.-base theme))
            (.addColorStop g 1 "rgba(20,16,26,0)")
            (set! (.-fillStyle ctx) g)
            (.fillRect ctx (- (.-l margin) 6) base (- w (.-l margin) (.-r margin) -12) 6))
          (do (set! (.-fillStyle ctx) (.-base theme))
              (.fillRect ctx (.-l margin) base (- w (.-l margin) (.-r margin)) 1)))
        ;; one label per stack, rotated, under its group
        (doseq [[id ^js l] (es6-iterator-seq (.entries labels))]
          (let [a (tween-val (.-a l) t)]
            (if (and (< a 0.01) (settled? (.-a l) t) (zero? (.. l -a -to)))
              (.delete labels id)
              (do (.save ctx)
                  (set! (.-globalAlpha ctx) (* a (if (and hover (not= hover id)) 0.45 1)))
                  (.translate ctx (tween-val (.-x l) t) (+ base 12))
                  (.rotate ctx (- (/ (* 50 js/Math.PI) 180)))
                  (set! (.-textBaseline ctx) "middle")
                  (text! ctx (:label (.-d l)) 0 0 "right" (.-ink theme)
                         (str (if (= hover id) "700" "500") " 11px " num))
                  (.restore ctx)))))))))

(defn- busy? [t]
  (or (not (settled? (.-ymax st) t))
      (some (fn [^js b] (not (and (settled? (.-v b) t) (settled? (.-a b) t) (settled? (.-x b) t))))
            (es6-iterator-seq (.values ^js (.-bars st))))
      (some (fn [^js l] (not (and (settled? (.-x l) t) (settled? (.-a l) t))))
            (es6-iterator-seq (.values ^js (.-labels st))))))

(defn- frame [t]
  (set! (.-raf st) nil)
  (draw! t)
  (when (and (.-visible st) (not (.-hidden js/document)) (busy? t))
    (set! (.-raf st) (js/requestAnimationFrame frame))))

(defn- kick! []
  (when-not (.-raf st)
    (set! (.-raf st) (js/requestAnimationFrame frame))))

;; ---- tooltip

(defn- el [tag cls & kids]
  (let [e (.createElement js/document tag)]
    (when cls (set! (.-className e) cls))
    (doseq [k kids :when k] (.append e k))
    e))

(defn- stack [id] (first (filter #(= id (:id %)) (:stacks (.-model st)))))

(defn- pad [s n] (.padEnd (str s) n))

(defn- show-tip!
  "Every bar of the hovered stack, in the top corner away from its group."
  [id]
  (let [^js tip (.-tip st)
        s (stack id)
        ^js l (.get ^js (.-labels st) id)]
    (if-not (and tip s l)
      (when tip (set! (.-hidden tip) true))
      (do
        (.replaceChildren
         tip
         (el "div" "tip-head" (el "b" nil (:label s)))
         (let [box (el "div" nil)]
           (doseq [sr (:series s)
                   :let [ul (el "ul" "tip-runs")]]
             (doseq [b (:bars sr)
                     :let [n (count (:runs b))
                           [have of] (:coverage b)]]
               (.append ul (el "li" nil (str (pad (:effort b) 8) (pad (fmt-usd (:value b)) 9)
                                             n (if (= 1 n) " run" " runs")
                                             (when (< have of) (str " · " have "/" of " projects"))))))
             (.append box (el "div" "tip-sub tip-model" (:model sr)) ul))
           box))
        (set! (.-hidden tip) false)
        (let [cw (.-w st)
              tw' (.-offsetWidth tip)
              th (.-offsetHeight tip)
              left (if (< (.. l -x -to) (/ cw 2)) (- cw (.-r margin) tw' 8) (+ (.-l margin) 8))]
          (set! (.. tip -style -left) (str (max 4 left) "px"))
          (set! (.. tip -style -top) (str (max 0 (min (- (.-h st) th) (+ (.-t margin) 8))) "px")))))))

(defn- set-hover! [id]
  (when (not= id (.-hover st))
    (set! (.-hover st) id)
    (show-tip! id)
    (kick!)))

(defn- hit
  "The stack whose slot is under the pointer, from the plot's top down to its labels."
  [mx my]
  (let [order (.-order st)
        i (js/Math.floor (/ (- mx (.-l margin)) (.-slot st)))]
    (when (and (<= (- (.-t margin) 30) my) (< -1 i (.-length order)))
      (aget order i))))

(defn- on-move [^js e]
  (let [r (.getBoundingClientRect (.-canvas st))]
    (set-hover! (hit (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r))))))

(defn- on-key [^js e]
  (let [order (vec (.-order st))
        i (.indexOf order (.-hover st))
        step (case (.-key e) ("ArrowRight" "ArrowDown") 1 ("ArrowLeft" "ArrowUp") -1 nil)]
    (cond
      (and step (seq order))
      (do (.preventDefault e)
          (set-hover! (nth order (mod (+ (if (neg? i) (if (pos? step) -1 0) i) step) (count order)))))
      (= "Escape" (.-key e)) (set-hover! nil))))

;; ---- lifecycle

(defn- resize! []
  (when-let [^js c (.-canvas st)]
    (let [w (.-clientWidth (.-parentNode c))
          h (js/Math.round (max 340 (min 540 (* w 0.52))))
          dpr (or (.-devicePixelRatio js/window) 1)
          narrow? (< w 560)]
      (set! (.-lbase st) (if narrow? 46 68))
      (set! (.-l margin) (.-lbase st))
      (set! (.-r margin) (if narrow? 6 18))
      (set! (.-w st) w) (set! (.-h st) h) (set! (.-dpr st) dpr)
      (set! (.-width c) (js/Math.round (* w dpr)))
      (set! (.-height c) (js/Math.round (* h dpr)))
      (set! (.. c -style -height) (str h "px"))
      (when-let [m (.-model st)] (place! m false))
      (draw! (now))
      (kick!))))

(defn- css-var [^js cs k fallback]
  (let [v (str/trim (.getPropertyValue cs k))] (if (seq v) v fallback)))

(defn restyle!
  "Re-read colours and fonts from the active stylesheet (after a mode or
   light/dark switch) and redraw."
  []
  (let [cs (js/getComputedStyle (.-documentElement js/document))]
    (set! (.-evil theme) (= "evil" (.. js/document -documentElement -dataset -mode)))
    (set! (.-sl theme) (js/parseFloat (css-var cs "--chart-stack-l" "0.62")))
    (set! (.-sc theme) (js/parseFloat (css-var cs "--chart-stack-c" "0.14")))
    (set! (.-ink theme) (css-var cs "--chart-ink" "#ddd"))
    (set! (.-ink2 theme) (css-var cs "--chart-ink-2" "#aaa"))
    (set! (.-grid theme) (css-var cs "--chart-grid" "rgba(128,128,128,0.15)"))
    (set! (.-base theme) (css-var cs "--chart-base" "rgba(128,128,128,0.6)"))
    (set! (.-frame theme) (css-var cs "--chart-frame" "rgba(44,40,50,0.95)"))
    (set! (.-hot theme) (css-var cs "--chart-hot" "rgba(255,255,255,0.8)"))
    (set! (.-label theme) (css-var cs "--chart-label-font" "monospace"))
    (set! (.-num theme) (css-var cs "--chart-num-font" "monospace"))
    ;; fonts change the labels' widths, and with them the margins
    (when-let [m (.-model st)] (place! m false))
    (draw! (now))
    (kick!)))

(defn update!
  "Show `model`. Before the chart first scrolls into view it is only stored,
   so the bars rise when the reader gets there."
  [model]
  (if (.-revealed st)
    (do (place! model true) (set-hover! nil) (kick!))
    (set! (.-pending st) model)))

(defn- reveal! []
  (when-not (.-revealed st)
    (set! (.-revealed st) true)
    (when-let [m (.-pending st)]
      (set! (.-pending st) nil)
      (place! m true)))
  (kick!))

(defn attach!
  "hammer :ref for the canvas: called with the element, and nil on removal."
  [^js c]
  (if c
    (let [tip (el "div" "tip glass")]
      (set! (.-hidden tip) true)
      (.setAttribute tip "role" "status")
      (.append (.-parentNode c) tip)
      (set! (.-canvas st) c)
      (set! (.-ctx st) (.getContext c "2d"))
      (set! (.-tip st) tip)
      (set! (.-reduced st) (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))
      (.addEventListener c "mousemove" on-move)
      (.addEventListener c "mouseleave" #(set-hover! nil))
      (.addEventListener c "keydown" on-key)
      (.addEventListener c "blur" #(set-hover! nil))
      (set! (.-ro st) (doto (js/ResizeObserver. resize!) (.observe (.-parentNode c))))
      (set! (.-io st) (doto (js/IntersectionObserver.
                             (fn [entries]
                               (let [v (.-isIntersecting (aget entries 0))]
                                 (set! (.-visible st) v)
                                 (when v (reveal!))))
                             #js {:threshold 0.25})
                        (.observe c)))
      (.addEventListener js/document "visibilitychange" kick!)
      (.then (.-ready (.-fonts js/document)) restyle!)
      (restyle!)
      (resize!))
    (do (some-> ^js (.-ro st) .disconnect)
        (some-> ^js (.-io st) .disconnect)
        (some-> ^js (.-tip st) .remove)
        (set! (.-canvas st) nil)
        (set! (.-ctx st) nil))))
