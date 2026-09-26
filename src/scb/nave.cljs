(ns scb.nave
  "Full-page background: a rose window turning slowly behind the title, an
   arcade of pointed arches, shafts of coloured light and drifting dust.
   Static stone is drawn once per resize; each frame only composites.")

(def ^:private jewels ["#e0344c" "#3f7cf0" "#c9830c" "#15a06f" "#a86ef2"])

(defonce ^:private st #js {:c nil :ctx nil :stone nil :rose nil :w 0 :h 0 :dpr 1 :dust nil :raf nil
                           :reduced false})

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
      (.addColorStop g 0 "#0b0910")
      (.addColorStop g 0.45 "#0d0a12")
      (.addColorStop g 1 "#050407")
      (set! (.-fillStyle ctx) g)
      (.fillRect ctx 0 0 w h))
    ;; two tiers of arcade down the sides, fading toward the centre
    (doseq [[tier aw ah alpha] [[0 (max 90 (/ w 9)) (* h 0.62) 0.075] [1 (max 60 (/ w 15)) (* h 0.36) 0.05]]]
      (let [n (js/Math.ceil (/ w aw))]
        (doseq [i (range n)]
          (let [x (* i aw)
                d (js/Math.abs (- (+ x (/ aw 2)) (/ w 2)))
                fade (min 1 (/ d (* w 0.42)))]
            (set! (.-strokeStyle ctx) (str "rgba(214,200,232," (* alpha fade) ")"))
            (set! (.-lineWidth ctx) (if (zero? tier) 1.4 1))
            (.beginPath ctx)
            (arch! ctx (+ x 6) h (- aw 12) ah)
            (.stroke ctx)
            (.beginPath ctx)
            (arch! ctx (+ x 18) h (- aw 36) (- ah 26))
            (.stroke ctx)))))
    ;; ribs of the vault
    (set! (.-strokeStyle ctx) "rgba(214,200,232,0.035)")
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
          (.addColorStop g 0 (rgba col 0.03))
          (.addColorStop g 0.7 (rgba col 0.16))
          (.addColorStop g 1 (rgba col 0.05))
          (set! (.-fillStyle ctx) g))
        (.fill ctx)))
    ;; tracery
    (set! (.-strokeStyle ctx) "rgba(226,212,240,0.16)")
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
    (.rotate ctx (* secs 0.018))
    (set! (.-globalAlpha ctx) (+ 0.8 (* 0.2 (js/Math.sin (* secs 0.5)))))
    (.drawImage ctx (.-img rose) (- (/ rs dpr 2)) (- (/ rs dpr 2)) (/ rs dpr) (/ rs dpr))
    (.restore ctx)
    ;; shafts of light falling from the clerestory
    (.save ctx)
    (set! (.-globalCompositeOperation ctx) "lighter")
    (doseq [[i col] (map-indexed vector ["#e0344c" "#3f7cf0" "#a86ef2"])]
      (let [x0 (+ (* w (+ 0.12 (* i 0.3))) (* 40 (js/Math.sin (+ (* secs 0.07) i))))
            a (+ 0.035 (* 0.02 (js/Math.sin (+ (* secs 0.3) (* i 2)))))
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
    ;; dust in the light
    (set! (.-fillStyle ctx) "rgba(255,238,220,0.5)")
    (doseq [^js d (.-dust st)]
      (let [y (mod (- (.-y d) (* secs (.-v d))) h)
            x (+ (.-x d) (* 12 (js/Math.sin (+ (.-p d) (* secs 0.4)))))]
        (set! (.-globalAlpha ctx) (+ 0.25 (* 0.35 (js/Math.sin (+ (.-p d) secs)))))
        (.beginPath ctx) (.arc ctx x y (.-r d) 0 6.2832) (.fill ctx)))
    (.restore ctx))
  (when-not (or (.-reduced st) (.-hidden js/document))
    (set! (.-raf st) (js/requestAnimationFrame frame))))

(defn- kick! []
  (when-not (.-raf st) (set! (.-raf st) (js/requestAnimationFrame frame))))

(defn start!
  "Paint the nave into canvas#nave; animated unless the reader prefers reduced motion."
  []
  (when-let [c (.getElementById js/document "nave")]
    (set! (.-c st) c)
    (set! (.-ctx st) (.getContext c "2d"))
    (set! (.-reduced st) (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))
    (rebuild!)
    (.addEventListener js/window "resize" #(do (rebuild!) (kick!)))
    (.addEventListener js/window "scroll" #(when (.-reduced st) (kick!)) #js {:passive true})
    (.addEventListener js/document "visibilitychange" kick!)
    (kick!)))
