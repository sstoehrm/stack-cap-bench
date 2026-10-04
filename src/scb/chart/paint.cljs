(ns scb.chart.paint
  "Draws one frame of the chart from its state. Each stack has its own hue,
   each effort level a shade of it (lighter is lower), and each model its own
   texture. Lancet windows in evil mode, plain rounded bars in serious mode.
   Colours and fonts come from the stylesheet."
  (:require [clojure.string :as str]
            [scb.chart.tween :as tw]))

(defn- fmt-axis [v] (str "$" (if (== v (js/Math.round v)) v (.toFixed v 1))))

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
  [^js th slot rank]
  (let [[k sign] (nth hues (mod slot (count hues)))]
    (oklch (+ (.-sl th) (* sign 0.07) (shade rank)) (.-sc th) (+ 20 (/ (* 360 k) (count hues))))))

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
  [^js ctx ^js th color style x top w base alpha hot? dim?]
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
      (set! (.-strokeStyle ctx) (if hot? (.-hot th) (.-frame th)))
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
  [^js ctx ^js th color style x top w base alpha hot? dim?]
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
          (set! (.-strokeStyle ctx) (.-hot th))
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
  [^js ctx ^js th x y rank style]
  (set! (.-fillStyle ctx) (oklch (+ (.-sl th) (shade rank)) 0 0))
  (.beginPath ctx) (.rect ctx x (- y 6) 10 12) (.fill ctx)
  (.rect ctx x (- y 6) 10 12)
  (texture! ctx style x (- y 6) 10 12))

(defn- draw-legend!
  "Right-aligned in the top row: the effort shades, lightest first, then the
   models' textures."
  [^js ctx ^js s model]
  (let [th (.-theme s)
        font (str "500 11px " (.-num th))
        y 16
        items (concat (for [e (:efforts model)] [(:effort e) (:rank e) 0])
                      (for [md (:models model)] [(:label md) high-rank (:style md)]))]
    (set! (.-font ctx) font)
    (set! (.-textBaseline ctx) "middle")
    (loop [items (reverse items) x (- (.-w s) (.. s -margin -r))]
      (when-let [[label rank style] (first items)]
        (let [x0 (- x (.-width (.measureText ctx label)) 16)]
          (swatch! ctx th x0 y rank style)
          (text! ctx label (+ x0 16) y "left" (.-ink2 th) font)
          (recur (rest items) (- x0 (if (= label (:label (first (:models model)))) 30 16))))))))

(defn frame!
  "Paint the chart in state `s` as it stands at time `t`; nothing until a
   model is placed. The context is already scaled to CSS pixels."
  [^js ctx ^js s t]
  (let [w (.-w s) h (.-h s)
        m (.-margin s)
        model (.-model s)
        th (.-theme s)
        evil (and th (.-evil th))
        num (and th (.-num th))]
    (when (and model th (pos? w))
      (.clearRect ctx 0 0 w h)
      (let [base (- h (.-b m))
            top0 (.-t m)
            ymax (max 1e-9 (tw/tween-val (.-ymax s) t))
            y (fn [v] (- base (* (- base top0) (/ v ymax))))
            hover (.-hover s)
            bars ^js (.-bars s)
            labels ^js (.-labels s)]
        ;; grid and y axis
        (set! (.-font ctx) (str "500 11px " num))
        (set! (.-textAlign ctx) "right")
        (set! (.-textBaseline ctx) "middle")
        (doseq [tk (tw/nice-ticks (.. s -ymax -to))
                :when (<= tk (* ymax 1.0001))]
          (let [yy (js/Math.round (y tk))]
            (set! (.-strokeStyle ctx) (if (zero? tk) "rgba(0,0,0,0)" (.-grid th)))
            (set! (.-lineWidth ctx) 1)
            (.beginPath ctx) (.moveTo ctx (.-l m) (+ yy 0.5)) (.lineTo ctx (- w (.-r m)) (+ yy 0.5)) (.stroke ctx)
            (set! (.-fillStyle ctx) (.-ink2 th))
            (.fillText ctx (fmt-axis tk) (- (.-l m) 12) yy)))
        (text! ctx (if evil "COST · USD" "Cost · USD") (.-l m) 16 "left" (.-ink2 th)
               (str "600 10px " (.-label th)))
        (draw-legend! ctx s model)
        ;; bars
        (doseq [[id ^js b] (es6-iterator-seq (.entries bars))]
          (let [a (tw/tween-val (.-a b) t)
                d (.-d b)]
            (if (and (< a 0.01) (tw/settled? (.-a b) t) (zero? (.. b -a -to)))
              (.delete bars id)
              ((if evil draw-lancet! draw-plain!)
               ctx th (bar-color th (:slot d) (:rank d)) (:style d) (tw/tween-val (.-x b) t) (y (tw/tween-val (.-v b) t)) (tw/tween-val (.-w b) t)
               base a (= hover (:stack d)) (and hover (not= hover (:stack d)))))))
        ;; baseline
        (if evil
          (let [g (.createLinearGradient ctx 0 base 0 (+ base 6))]
            (.addColorStop g 0 (.-base th))
            (.addColorStop g 1 "rgba(20,16,26,0)")
            (set! (.-fillStyle ctx) g)
            (.fillRect ctx (- (.-l m) 6) base (- w (.-l m) (.-r m) -12) 6))
          (do (set! (.-fillStyle ctx) (.-base th))
              (.fillRect ctx (.-l m) base (- w (.-l m) (.-r m)) 1)))
        ;; one label per stack, rotated, under its group
        (doseq [[id ^js l] (es6-iterator-seq (.entries labels))]
          (let [a (tw/tween-val (.-a l) t)]
            (if (and (< a 0.01) (tw/settled? (.-a l) t) (zero? (.. l -a -to)))
              (.delete labels id)
              (do (.save ctx)
                  (set! (.-globalAlpha ctx) (* a (if (and hover (not= hover id)) 0.45 1)))
                  (.translate ctx (tw/tween-val (.-x l) t) (+ base 12))
                  (.rotate ctx (- (/ (* 50 js/Math.PI) 180)))
                  (set! (.-textBaseline ctx) "middle")
                  (text! ctx (:label (.-d l)) 0 0 "right" (.-ink th)
                         (str (if (= hover id) "700" "500") " 11px " num))
                  (.restore ctx)))))))))

;; ---- theme

(defn- css-var [^js cs k fallback]
  (let [v (str/trim (.getPropertyValue cs k))] (if (seq v) v fallback)))

(defn read-theme!
  "Colours and fonts from the active stylesheet into the chart state."
  [^js s]
  (let [cs (js/getComputedStyle (.-documentElement js/document))]
    (set! (.-theme s)
          #js {:evil (= "evil" (.. js/document -documentElement -dataset -mode))
               :sl (js/parseFloat (css-var cs "--chart-stack-l" "0.62"))
               :sc (js/parseFloat (css-var cs "--chart-stack-c" "0.14"))
               :ink (css-var cs "--chart-ink" "#ddd")
               :ink2 (css-var cs "--chart-ink-2" "#aaa")
               :grid (css-var cs "--chart-grid" "rgba(128,128,128,0.15)")
               :base (css-var cs "--chart-base" "rgba(128,128,128,0.6)")
               :frame (css-var cs "--chart-frame" "rgba(44,40,50,0.95)")
               :hot (css-var cs "--chart-hot" "rgba(255,255,255,0.8)")
               :label (css-var cs "--chart-label-font" "monospace")
               :num (css-var cs "--chart-num-font" "monospace")})))

(defn text-width
  "Width of a stack label in the label font, for fitting the margins."
  [^js ctx ^js th]
  (set! (.-font ctx) (str "500 11px " (.-num th)))
  (fn [label] (.-width (.measureText ctx label))))
