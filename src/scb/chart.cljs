(ns scb.chart
  "The one chart: grouped bars on a canvas — lancet windows in evil mode,
   plain rounded bars in serious mode. A bar's value is its top (the apex of
   an arch, which sits inside the bar). Every change tweens from what is on
   screen to the new target. Colours and fonts come from the stylesheet."
  (:require [clojure.string :as str]))

(defonce ^:private theme
  #js {:evil false :bar "#8a8594"
       :ink "#ddd" :ink2 "#aaa" :grid "rgba(255,255,255,0.07)" :base "rgba(120,108,134,0.9)"
       :label "Cinzel, serif" :num "'JetBrains Mono', monospace" :frame "rgba(44,40,50,0.95)"
       :hot "rgba(214,204,188,0.75)" :mark "#d9cfbf"})

(def ^:private dur 850)
(def ^:private stagger 32)
(def ^:private margin #js {:l 68 :r 18 :t 40 :b 70})

(defonce ^:private st
  #js {:canvas nil :ctx nil :tip nil :w 0 :h 0 :dpr 1
       :model nil :pending nil :revealed false :visible false
       :bars (js/Map.) :order #js [] :ymax #js {:from 1 :to 1 :t0 0}
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

;; ---- scales

(defn nice-ticks [mx]
  (if-not (pos? mx)
    [0 1]
    (let [raw (/ mx 4)
          mag (js/Math.pow 10 (js/Math.floor (js/Math.log10 raw)))
          step (some #(when (>= % raw) %) (map #(* % mag) [1 2 2.5 5 10]))
          n (js/Math.ceil (- (/ mx step) 1e-9))]
      (mapv #(* % step) (range (inc n))))))

(defn- rgba [hex a]
  (let [n (js/parseInt (subs hex 1) 16)]
    (str "rgba(" (bit-and (bit-shift-right n 16) 255) "," (bit-and (bit-shift-right n 8) 255) ","
         (bit-and n 255) "," a ")")))

(defn- mix
  "hex blended toward white (amt > 0) or black (amt < 0)."
  [hex amt]
  (let [n (js/parseInt (subs hex 1) 16)
        f (fn [c] (js/Math.round (if (pos? amt) (+ c (* (- 255 c) amt)) (* c (+ 1 amt)))))]
    (str "rgb(" (f (bit-and (bit-shift-right n 16) 255)) "," (f (bit-and (bit-shift-right n 8) 255)) ","
         (f (bit-and n 255)) ")")))

;; ---- layout

(defn- layout
  "Target x/width for every bar id: one slot per stack, in ranked order."
  [model w]
  (let [pw (- w (.-l margin) (.-r margin))
        bars (:bars model)
        n (max 1 (count bars))
        slot (/ pw n)
        bw (min 56 (* slot 0.7))]
    (into {} (for [[i b] (map-indexed vector bars)]
               [(:id b) {:x (+ (.-l margin) (* i slot) (/ (- slot bw) 2)) :w bw
                         :cx (+ (.-l margin) (* (+ i 0.5) slot))}]))))

(defn- fit-margins!
  "Room for the labels, rotated by 50 degrees: the bottom margin fits the
   longest one, and the left margin grows (from the width's base margin) until
   the first bar's label no longer runs past the canvas edge."
  [model]
  (let [bars (:bars model)
        ^js ctx (.-ctx st)
        _ (when ctx (set! (.-font ctx) (str "500 11px " (.-num theme))))
        text-w (fn [b] (if ctx (.-width (.measureText ctx (:label b))) (* 7 (count (:label b)))))
        base-l (or (.-lbase st) 68)
        slot (/ (- (.-w st) base-l (.-r margin)) (max 1 (count bars)))
        reach (if (seq bars) (* 0.643 (text-w (first bars))) 0)]
    (set! (.-b margin) (+ 24 (* 0.77 (reduce max 50 (map text-w bars)))))
    (set! (.-l margin) (max base-l (+ 6 (- reach (/ slot 2)))))))

(defn- apply-model!
  "Retarget every bar (and the y max) at `model`; bars that vanish fall and fade."
  [model]
  (let [t (now)
        bars ^js (.-bars st)
        _ (fit-margins! model)
        pos (layout model (.-w st))
        live (set (keys pos))
        order #js []
        ticks (nice-ticks (reduce max 0 (map :value (:bars model))))
        reduced (.-reduced st)]
    (set! (.-ymax st) (if reduced (tw (peek ticks) (peek ticks) t 0) (retarget (.-ymax st) (peek ticks) t 0)))
    (doseq [[i b] (map-indexed vector (:bars model))
            :let [id (:id b)
                  {:keys [x w]} (pos id)
                  ^js old (.get bars id)
                  delay (if reduced 0 (* stagger i))
                  fresh? (or (nil? old) (zero? (.. old -a -to)))]]
      (.push order id)
      (.set bars id
            (if reduced
              #js {:v (tw (:value b) (:value b) t 0) :a (tw 1 1 t 0) :x (tw x x t 0) :w (tw w w t 0) :d b}
              #js {:v (if old (retarget (.-v old) (:value b) t delay) (tw 0 (:value b) t delay))
                   :a (if old (retarget (.-a old) 1 t delay) (tw 0 1 t delay))
                   :x (if fresh? (tw x x t 0) (retarget (.-x old) x t delay))
                   :w (if fresh? (tw w w t 0) (retarget (.-w old) w t delay))
                   :d b})))
    (doseq [[id ^js b] (es6-iterator-seq (.entries bars))
            :when (not (live id))]
      (if reduced
        (.delete bars id)
        (do (set! (.-v b) (retarget (.-v b) 0 t 0))
            (set! (.-a b) (retarget (.-a b) 0 t 0)))))
    (set! (.-order st) order)
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
    (.closePath ctx)
    ah))

(defn- bar-color
  "Every bar has the same colour: a stack is identified by its label, never its colour."
  [_]
  (.-bar theme))

(defn- draw-lancet! [^js ctx b x top w base alpha hot? dim? t]
  (let [color (bar-color b)
        h (- base top)]
    (when (and (> h 0.5) (> w 1))
      (.save ctx)
      (set! (.-globalAlpha ctx) (* alpha (if dim? 0.42 1)))
      ;; glow
      (set! (.-shadowColor ctx) (if hot? "rgba(150,20,36,0.7)" "rgba(0,0,0,0)"))
      (set! (.-shadowBlur ctx) (if hot? 22 0))
      (let [ah (lancet-path! ctx x top w base)
            g (.createLinearGradient ctx 0 top 0 base)]
        (.addColorStop g 0 (if hot? (mix color 0.12) color))
        (.addColorStop g 0.35 (mix color -0.4))
        (.addColorStop g 1 (mix color -0.88))
        (set! (.-fillStyle ctx) g)
        (.fill ctx)
        (set! (.-shadowBlur ctx) 0)
        ;; glass: light through the pane, leaded lattice, mullion, transom
        (.save ctx)
        (.clip ctx)
        (let [rg (.createRadialGradient ctx (+ x (/ w 2)) (+ top (* ah 0.5)) 0
                                        (+ x (/ w 2)) (+ top (* ah 0.5)) (* w 1.1))]
          (.addColorStop rg 0 "rgba(210,200,190,0.05)")
          (.addColorStop rg 1 "rgba(210,200,190,0)")
          (set! (.-fillStyle ctx) rg)
          (.fillRect ctx x top w h))
        (set! (.-strokeStyle ctx) "rgba(2,1,3,0.75)")
        (set! (.-lineWidth ctx) 1.2)
        (.beginPath ctx)
        (let [step 13]
          (doseq [k (range (- (js/Math.ceil (/ h step))) (js/Math.ceil (/ (+ w h) step)))]
            (let [x1 (+ x (* k step))]
              (.moveTo ctx x1 base) (.lineTo ctx (+ x1 h) top)
              (.moveTo ctx (+ x1 h) base) (.lineTo ctx x1 top))))
        (.stroke ctx)
        (set! (.-strokeStyle ctx) "rgba(2,1,3,0.95)")
        (set! (.-lineWidth ctx) 2)
        (.beginPath ctx)
        (when (> w 22) (.moveTo ctx (+ x (/ w 2)) (+ top (* ah 0.5))) (.lineTo ctx (+ x (/ w 2)) base))
        (when (> h (* ah 1.6)) (.moveTo ctx x (+ top ah)) (.lineTo ctx (+ x w) (+ top ah)))
        (.stroke ctx)
        ;; a slow shaft of light sweeping the glass
        (when-not (.-reduced st)
          (let [period 7000
                p (/ (mod t period) period)
                sx (- (* p (+ (.-w st) 400)) 200)
                sg (.createLinearGradient ctx (- sx 70) 0 (+ sx 70) 0)]
            (.addColorStop sg 0 "rgba(255,255,255,0)")
            (.addColorStop sg 0.5 "rgba(200,190,185,0.035)")
            (.addColorStop sg 1 "rgba(255,255,255,0)")
            (set! (.-fillStyle ctx) sg)
            (.fillRect ctx x top w h)))
        (.restore ctx)
        ;; stone frame
        (lancet-path! ctx x top w base)
        (set! (.-strokeStyle ctx) (if hot? (.-hot theme) (.-frame theme)))
        (set! (.-lineWidth ctx) (if hot? 2 1.6))
        (.stroke ctx))
      (.restore ctx))))

(defn- draw-plain!
  "Serious mode: a flat bar, 4px rounded at the top, anchored to the baseline."
  [^js ctx b x top w base alpha hot? dim?]
  (let [h (- base top)]
    (when (and (> h 0.5) (> w 1))
      (let [r (min 1.5 (/ w 2) h)]
        (.save ctx)
        (set! (.-globalAlpha ctx) (* alpha (if dim? 0.35 1)))
        (set! (.-fillStyle ctx) (bar-color b))
        (.beginPath ctx)
        (.moveTo ctx x base)
        (.lineTo ctx x (+ top r))
        (.arcTo ctx x top (+ x r) top r)
        (.lineTo ctx (- (+ x w) r) top)
        (.arcTo ctx (+ x w) top (+ x w) (+ top r) r)
        (.lineTo ctx (+ x w) base)
        (.closePath ctx)
        (.fill ctx)
        (when hot?
          (set! (.-strokeStyle ctx) (.-hot theme))
          (set! (.-lineWidth ctx) 2)
          (.stroke ctx))
        (.restore ctx)))))

(defn- draw-bar! [ctx b x top w base alpha hot? dim? t]
  (if (.-evil theme)
    (draw-lancet! ctx b x top w base alpha hot? dim? t)
    (draw-plain! ctx b x top w base alpha hot? dim?)))

(defn- draw! [t]
  (let [^js ctx (.-ctx st)
        w (.-w st) h (.-h st)
        model (.-model st)
        evil (.-evil theme)
        num (.-num theme)
        label (.-label theme)]
    (when (and ctx model (pos? w))
      (.setTransform ctx (.-dpr st) 0 0 (.-dpr st) 0 0)
      (.clearRect ctx 0 0 w h)
      (let [base (- h (.-b margin))
            top0 (.-t margin)
            ph (- base top0)
            ymax (max 1e-9 (tween-val (.-ymax st) t))
            y (fn [v] (- base (* ph (/ v ymax))))
            m (:metric model)
            hover (.-hover st)
            bars ^js (.-bars st)]
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
            (.fillText ctx ((:axis m) tk) (- (.-l margin) 12) yy)))
        (set! (.-textAlign ctx) "left")
        (set! (.-font ctx) (str "600 10px " label))
        (set! (.-fillStyle ctx) (.-ink2 theme))
        (.fillText ctx (let [s (str (:label m) " · " (:unit m) " — " (:label (:config model)))]
                        (if evil (str/upper-case s) s))
                   (.-l margin) (- top0 22))
        ;; bars
        (doseq [[id ^js b] (es6-iterator-seq (.entries bars))]
          (let [a (tween-val (.-a b) t)
                v (tween-val (.-v b) t)]
            (if (and (< a 0.01) (settled? (.-a b) t) (zero? (.. b -a -to)))
              (.delete bars id)
              (draw-bar! ctx (.-d b) (tween-val (.-x b) t) (y v) (tween-val (.-w b) t) base a
                         (= id hover) (and hover (not= id hover)) t))))
        ;; baseline
        (if evil
          (let [g (.createLinearGradient ctx 0 base 0 (+ base 6))]
            (.addColorStop g 0 (.-base theme))
            (.addColorStop g 1 "rgba(20,16,26,0)")
            (set! (.-fillStyle ctx) g)
            (.fillRect ctx (- (.-l margin) 6) base (- w (.-l margin) (.-r margin) -12) 6))
          (do (set! (.-fillStyle ctx) (.-base theme))
              (.fillRect ctx (.-l margin) base (- w (.-l margin) (.-r margin)) 1)))
        ;; one label per bar, rotated, following the bar while it moves
        (doseq [[id ^js b] (es6-iterator-seq (.entries bars))]
          (let [a (tween-val (.-a b) t)
                cx (+ (tween-val (.-x b) t) (/ (tween-val (.-w b) t) 2))]
            (when (> a 0.01)
              (.save ctx)
              (set! (.-globalAlpha ctx) a)
              (.translate ctx cx (+ base 12))
              (.rotate ctx (- (/ (* 50 js/Math.PI) 180)))
              (set! (.-textAlign ctx) "right")
              (set! (.-textBaseline ctx) "middle")
              (set! (.-fillStyle ctx) (.-ink theme))
              (set! (.-font ctx) (str "500 11px " num))
              (.fillText ctx (:label (.-d b)) 0 0)
              (.restore ctx))))
        ;; the lowest value is the first bar: mark it (only when there is something to compare)
        (when (> (count (:bars model)) 1)
          (let [best (first (:bars model))
                ^js b (.get bars (:id best))]
            (when b
              (let [a (tween-val (.-a b) t)
                    bw (tween-val (.-w b) t)
                    x (+ (tween-val (.-x b) t) (/ bw 2))
                    yy (y (tween-val (.-v b) t))
                    mark (if evil "✠" "↓")]
                (.save ctx)
                (set! (.-globalAlpha ctx) (* a (ease (/ (- t (.. b -v -t0) (.. b -v -delay)) dur))))
                (set! (.-textAlign ctx) "center")
                (set! (.-textBaseline ctx) "bottom")
                (set! (.-fillStyle ctx) (.-mark theme))
                (when evil
                  (set! (.-shadowColor ctx) "rgba(0,0,0,0.9)")
                  (set! (.-shadowBlur ctx) 6))
                (set! (.-font ctx) (str "700 12px " num))
                (.fillText ctx (if (< bw 28) mark (str mark " " ((:fmt m) (:value best)))) x (- yy 8))
                (.restore ctx)))))))))

(defn- busy? [t]
  (or (and (.-evil theme) (not (.-reduced st)))
      (not (settled? (.-ymax st) t))
      (some (fn [^js b] (not (and (settled? (.-v b) t) (settled? (.-a b) t)
                                  (settled? (.-x b) t))))
            (es6-iterator-seq (.values ^js (.-bars st))))))

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

(defn- show-tip! [id]
  (let [^js tip (.-tip st)
        ^js b (.get ^js (.-bars st) id)
        m (:metric (.-model st))]
    (if-not (and tip b)
      (when tip (set! (.-hidden tip) true))
      (let [d (.-d b)
            t (now)
            x (+ (tween-val (.-x b) t) (/ (tween-val (.-w b) t) 2))
            base (- (.-h st) (.-b margin))
            ymax (tween-val (.-ymax st) t)
            y (- base (* (- base (.-t margin)) (/ (:value d) ymax)))
            kw (keyword (:key m))
            [have of] (:coverage d)]
        (.replaceChildren tip
                          (el "div" "tip-head" (el "b" nil (:label d)))
                          (el "div" "tip-sub" (:config d))
                          (el "div" "tip-val" ((:fmt m) (:value d))
                              (when-let [f (:full m)] (el "span" "tip-full" (str " " (f (:value d)) " " (:unit m)))))
                          (el "div" "tip-sub"
                              (str "mean of " (count (:runs d)) " complete run" (when (not= 1 (count (:runs d))) "s")
                                   (when (> of 1) (str " · " have "/" of " projects"))))
                          (when-let [ag (seq (distinct (keep :agent (:runs d))))]
                            (el "div" "tip-sub" (str "agent: " (str/join ", " (sort ag)))))
                          (let [ul (el "ul" "tip-runs")]
                            (doseq [r (take 6 (:runs d))]
                              (.append ul (el "li" nil (str "P" (:project r) " " (:run_id r) "  " ((:fmt m) (get r kw))))))
                            ul))
        (set! (.-hidden tip) false)
        (let [cw (.-w st)
              tw' (.-offsetWidth tip)
              left (max 4 (min (- cw tw' 4) (- x (/ tw' 2))))
              top (- y (.-offsetHeight tip) 18)]
          (set! (.. tip -style -left) (str left "px"))
          (set! (.. tip -style -top) (str (max 0 top) "px")))))))

(defn- set-hover! [id]
  (when (not= id (.-hover st))
    (set! (.-hover st) id)
    (show-tip! id)
    (kick!)))

(defn- hit [mx my]
  (let [t (now)
        base (- (.-h st) (.-b margin))]
    (when (and (<= my base) (>= my (- (.-t margin) 30)))
      (some (fn [id]
              (let [^js b (.get ^js (.-bars st) id)
                    x (tween-val (.-x b) t)
                    w (tween-val (.-w b) t)
                    pad (max 2 (* w 0.1))]
                (when (<= (- x pad) mx (+ x w pad)) id)))
            (.-order st)))))

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
      (when-let [m (.-model st)]
        (fit-margins! m)
        (let [t (now) pos (layout m w)]
          (doseq [[id {:keys [x w]}] pos
                  :let [^js b (.get ^js (.-bars st) id)]
                  :when b]
            (set! (.-x b) (tw x x t 0))
            (set! (.-w b) (tw w w t 0)))))
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
    (set! (.-bar theme) (css-var cs "--chart-bar" "#8a8594"))
    (set! (.-ink theme) (css-var cs "--chart-ink" "#ddd"))
    (set! (.-ink2 theme) (css-var cs "--chart-ink-2" "#aaa"))
    (set! (.-grid theme) (css-var cs "--chart-grid" "rgba(128,128,128,0.15)"))
    (set! (.-base theme) (css-var cs "--chart-base" "rgba(128,128,128,0.6)"))
    (set! (.-frame theme) (css-var cs "--chart-frame" "rgba(44,40,50,0.95)"))
    (set! (.-hot theme) (css-var cs "--chart-hot" "rgba(255,255,255,0.8)"))
    (set! (.-mark theme) (css-var cs "--chart-mark" "#ddd"))
    (set! (.-label theme) (css-var cs "--chart-label-font" "monospace"))
    (set! (.-num theme) (css-var cs "--chart-num-font" "monospace"))
    (draw! (now))
    (kick!)))

(defn update!
  "Show `model`. Before the chart first scrolls into view it is only stored,
   so the bars rise when the reader gets there."
  [model]
  (if (.-revealed st)
    (do (apply-model! model) (kick!))
    (set! (.-pending st) model)))

(defn- reveal! []
  (when-not (.-revealed st)
    (set! (.-revealed st) true)
    (when-let [m (.-pending st)]
      (set! (.-pending st) nil)
      (apply-model! m)))
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
