(ns scb.nave
  "Full-page background: a rose window turning slowly behind the title, an
   arcade of pointed arches, shafts of coloured light and drifting dust.
   Static stone is drawn once per resize; each frame only composites.")

(def ^:private jewels ["#5a0f1a" "#2a2226" "#3d0a13" "#221c20" "#4a0c17"])

(defonce ^:private st #js {:c nil :ctx nil :stone nil :rose nil :w 0 :h 0 :dpr 1 :dust nil :raf nil
                           :reduced false :active false :wired false})

(defn- rgba [hex a]
  (let [n (js/parseInt (subs hex 1) 16)]
    (str "rgba(" (bit-and (bit-shift-right n 16) 255) "," (bit-and (bit-shift-right n 8) 255) ","
         (bit-and n 255) "," a ")")))

(defn- canvas [w h]
  (let [c (.createElement js/document "canvas")]
    (set! (.-width c) w) (set! (.-height c) h)
    c))

(defn- arch! [^js ctx x base w h]
  (let [ah (* w 0.9) mid (+ x (/ w 2))]
    (.moveTo ctx x base)
    (.lineTo ctx x (+ (- base h) ah))
    (.quadraticCurveTo ctx x (- base h) mid (- base h))
    (.quadraticCurveTo ctx (+ x w) (- base h) (+ x w) (+ (- base h) ah))
    (.lineTo ctx (+ x w) base)))

(defn- draw-stone!
  "Vault gradient and the arcade, into an offscreen canvas."
  [w h dpr]
  (let [c (canvas (* w dpr) (* h dpr))
        ctx (.getContext c "2d")]
    (.scale ctx dpr dpr)
    (let [g (.createLinearGradient ctx 0 0 0 h)]
      (.addColorStop g 0 "#050405")
      (.addColorStop g 0.45 "#060505")
      (.addColorStop g 1 "#010101")
      (set! (.-fillStyle ctx) g)
      (.fillRect ctx 0 0 w h))
    ;; two tiers of arcade down the sides, fading toward the centre
    (doseq [[tier aw ah alpha] [[0 (max 90 (/ w 9)) (* h 0.62) 0.04] [1 (max 60 (/ w 15)) (* h 0.36) 0.025]]]
      (let [n (js/Math.ceil (/ w aw))]
        (doseq [i (range n)]
          (let [x (* i aw)
                d (js/Math.abs (- (+ x (/ aw 2)) (/ w 2)))
                fade (min 1 (/ d (* w 0.42)))]
            (set! (.-strokeStyle ctx) (str "rgba(170,160,160," (* alpha fade) ")"))
            (set! (.-lineWidth ctx) (if (zero? tier) 1.4 1))
            (.beginPath ctx)
            (arch! ctx (+ x 6) h (- aw 12) ah)
            (.stroke ctx)
            (.beginPath ctx)
            (arch! ctx (+ x 18) h (- aw 36) (- ah 26))
            (.stroke ctx)))))
    ;; ribs of the vault
    (set! (.-strokeStyle ctx) "rgba(200,192,184,0.025)")
    (set! (.-lineWidth ctx) 1)
    (doseq [i (range -6 7)]
      (.beginPath ctx)
      (.moveTo ctx (/ w 2) (* h -0.2))
      (.quadraticCurveTo ctx (+ (/ w 2) (* i w 0.09)) (* h 0.25) (+ (/ w 2) (* i w 0.16)) h)
      (.stroke ctx))
    c))

(defn- draw-rose!
  "Tracery and glass of the rose window, radius r, into a square canvas."
  [r dpr]
  (let [s (* 2 (+ r 8))
        c (canvas (* s dpr) (* s dpr))
        ctx (.getContext c "2d")
        petals 16
        tau (* 2 js/Math.PI)]
    (.scale ctx dpr dpr)
    (.translate ctx (/ s 2) (/ s 2))
    ;; glass
    (doseq [i (range petals)]
      (let [a0 (* tau (/ i petals)) a1 (* tau (/ (inc i) petals))
            col (nth jewels (mod i (count jewels)))]
        (.beginPath ctx)
        (.moveTo ctx 0 0)
        (.arc ctx 0 0 (* r 0.94) a0 a1)
        (.closePath ctx)
        (let [g (.createRadialGradient ctx 0 0 (* r 0.2) 0 0 r)]
          (.addColorStop g 0 (rgba col 0.02))
          (.addColorStop g 0.7 (rgba col 0.16))
          (.addColorStop g 1 (rgba col 0.04))
          (set! (.-fillStyle ctx) g))
        (.fill ctx)))
    ;; tracery
    (set! (.-strokeStyle ctx) "rgba(170,160,160,0.08)")
    (set! (.-lineWidth ctx) 1.4)
    (doseq [k [1 0.94 0.62 0.3 0.12]]
      (.beginPath ctx) (.arc ctx 0 0 (* r k) 0 tau) (.stroke ctx))
    (doseq [i (range petals)]
      (let [a (* tau (/ i petals))
            pr (* r 0.2)
            pd (* r 0.78)]
        (.beginPath ctx)
        (.moveTo ctx (* (js/Math.cos a) r 0.3) (* (js/Math.sin a) r 0.3))
        (.lineTo ctx (* (js/Math.cos a) r 0.94) (* (js/Math.sin a) r 0.94))
        (.stroke ctx)
        (.beginPath ctx)
        (.arc ctx (* (js/Math.cos (+ a (/ tau petals 2))) pd) (* (js/Math.sin (+ a (/ tau petals 2))) pd) pr 0 tau)
        (.stroke ctx)
        (.beginPath ctx)
        (.arc ctx (* (js/Math.cos (+ a (/ tau petals 2))) r 0.46) (* (js/Math.sin (+ a (/ tau petals 2))) r 0.46)
              (* r 0.1) 0 tau)
        (.stroke ctx)))
    c))

(defn- rebuild! []
  (let [^js c (.-c st)
        w (.-innerWidth js/window)
        h (.-innerHeight js/window)
        dpr (min 2 (or (.-devicePixelRatio js/window) 1))
        r (* 0.34 (min (* w 1.1) (* h 1.3)))]
    (set! (.-w st) w) (set! (.-h st) h) (set! (.-dpr st) dpr)
    (set! (.-width c) (* w dpr)) (set! (.-height c) (* h dpr))
    (set! (.-stone st) (draw-stone! w h dpr))
    (set! (.-rose st) #js {:img (draw-rose! r dpr) :r r})
    (set! (.-dust st)
          (into-array (for [_ (range (js/Math.round (/ (* w h) 26000)))]
                        #js {:x (rand w) :y (rand h) :v (+ 4 (rand 10)) :r (+ 0.4 (rand 1.3)) :p (rand 6.28)})))))

(defn- frame [t]
  (set! (.-raf st) nil)
  (let [^js ctx (.-ctx st)
        w (.-w st) h (.-h st) dpr (.-dpr st)
        sc (.-scrollY js/window)
        ^js rose (.-rose st)
        secs (/ t 1000)
        rs (.-width (.-img rose))]
    (.setTransform ctx 1 0 0 1 0 0)
    (.drawImage ctx (.-stone st) 0 0)
    (.setTransform ctx dpr 0 0 dpr 0 0)
    ;; rose window, drifting up with scroll a little slower than the page
    (.save ctx)
    (.translate ctx (/ w 2) (- (* h 0.34) (* sc 0.35)))
    (.rotate ctx (* secs 0.006))
    (set! (.-globalAlpha ctx) 0.8)
    (.drawImage ctx (.-img rose) (- (/ rs dpr 2)) (- (/ rs dpr 2)) (/ rs dpr) (/ rs dpr))
    (.restore ctx)
    ;; one cold shaft of light from the clerestory
    (.save ctx)
    (set! (.-globalCompositeOperation ctx) "lighter")
    (doseq [[i col] (map-indexed vector ["#9a9aa6"])]
      (let [x0 (+ (* w 0.18) (* 40 (js/Math.sin (* secs 0.05))))
            a (+ 0.012 (* 0.006 (js/Math.sin (* secs 0.25))))
            g (.createLinearGradient ctx x0 0 (+ x0 (* h 0.45)) h)]
        (.addColorStop g 0 (rgba col a))
        (.addColorStop g 1 (rgba col 0))
        (set! (.-fillStyle ctx) g)
        (.beginPath ctx)
        (.moveTo ctx x0 0)
        (.lineTo ctx (+ x0 70) 0)
        (.lineTo ctx (+ x0 (* h 0.5) 180) h)
        (.lineTo ctx (+ x0 (* h 0.5) -40) h)
        (.closePath ctx)
        (.fill ctx)))
    ;; ash, falling
    (set! (.-globalCompositeOperation ctx) "source-over")
    (set! (.-fillStyle ctx) "rgba(150,145,145,0.45)")
    (doseq [^js d (.-dust st)]
      (let [y (mod (+ (.-y d) (* secs (.-v d) 0.8)) h)
            x (+ (.-x d) (* 18 (js/Math.sin (+ (.-p d) (* secs 0.25)))))]
        (set! (.-globalAlpha ctx) (+ 0.15 (* 0.2 (js/Math.sin (+ (.-p d) (* secs 0.7))))))
        (.beginPath ctx) (.arc ctx x y (.-r d) 0 6.2832) (.fill ctx)))
    (set! (.-globalAlpha ctx) 1)
    ;; embers behind the title, and a heavy vignette
    (let [g (.createRadialGradient ctx (/ w 2) (- (* h 0.3) (* sc 0.35)) 0 (/ w 2) (- (* h 0.3) (* sc 0.35)) (* 0.45 (max w h)))]
      (.addColorStop g 0 (str "rgba(120,14,28," (+ 0.1 (* 0.04 (js/Math.sin (* secs 0.6)))) ")"))
      (.addColorStop g 1 "rgba(120,14,28,0)")
      (set! (.-fillStyle ctx) g)
      (.fillRect ctx 0 0 w h))
    (let [v (.createRadialGradient ctx (/ w 2) (/ h 2) (* 0.25 (min w h)) (/ w 2) (/ h 2) (* 0.8 (max w h)))]
      (.addColorStop v 0 "rgba(0,0,0,0)")
      (.addColorStop v 1 "rgba(0,0,0,0.85)")
      (set! (.-fillStyle ctx) v)
      (.fillRect ctx 0 0 w h))
    (.restore ctx))
  (when-not (or (.-reduced st) (.-hidden js/document) (not (.-active st)))
    (set! (.-raf st) (js/requestAnimationFrame frame))))

(defn- kick! []
  (when (and (.-active st) (not (.-raf st)))
    (set! (.-raf st) (js/requestAnimationFrame frame))))

(defn start!
  "Paint the nave into canvas#nave (evil mode); animated unless the reader
   prefers reduced motion."
  []
  (when-let [c (.getElementById js/document "nave")]
    (when-not (.-wired st)
      (set! (.-wired st) true)
      (set! (.-c st) c)
      (set! (.-ctx st) (.getContext c "2d"))
      (set! (.-reduced st) (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))
      (.addEventListener js/window "resize" #(when (.-active st) (rebuild!) (kick!)))
      (.addEventListener js/window "scroll" #(when (.-reduced st) (kick!)) #js {:passive true})
      (.addEventListener js/document "visibilitychange" kick!))
    (set! (.-active st) true)
    (rebuild!)
    (kick!)))

(defn stop!
  "Serious mode: stop drawing and blank the canvas."
  []
  (set! (.-active st) false)
  (when-let [r (.-raf st)] (js/cancelAnimationFrame r) (set! (.-raf st) nil))
  (when-let [^js ctx (.-ctx st)]
    (.setTransform ctx 1 0 0 1 0 0)
    (.clearRect ctx 0 0 (.. st -c -width) (.. st -c -height))))
