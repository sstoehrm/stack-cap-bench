(ns scb.nave
  "Full-page background: a broken rose window behind the title, an
   arcade of pointed arches, shafts of coloured light and drifting dust.
   Static stone is drawn once per resize; each frame only composites.")

(def ^:private jewels ["#5a0f1a" "#2a2226" "#3d0a13" "#221c20" "#4a0c17"])

(defonce ^:private st #js {:c nil :ctx nil :stone nil :rose nil :w 0 :h 0 :dpr 1 :dust nil :raf nil
                           :reduced false :active false :wired false :shards #js []})

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

(defn- seeded
  "Deterministic random numbers in [0,1) (mulberry32), so the breakage is the
   same on every load and resize."
  [seed]
  (let [a (volatile! seed)]
    (fn []
      (vswap! a #(bit-or (+ % 0x6D2B79F5) 0))
      (let [t (js/Math.imul (bit-xor @a (unsigned-bit-shift-right @a 15)) (bit-or @a 1))
            t (bit-xor t (+ t (js/Math.imul (bit-xor t (unsigned-bit-shift-right t 7)) (bit-or t 61))))]
        (/ (unsigned-bit-shift-right (bit-xor t (unsigned-bit-shift-right t 14)) 0) 4294967296)))))

(def ^:private impact
  "Where the stone went through, as a fraction of the radius (x right, y down)."
  [0.34 -0.22])

(defn- draw-rose!
  "The broken rose window, radius r, into a square canvas: glass knocked out
   around an impact point and missing here and there, cracks radiating from the
   impact with a web of rings, and tracery broken where it was hit."
  [r dpr]
  (let [s (* 2 (+ r 8))
        c (canvas (* s dpr) (* s dpr))
        ctx (.getContext c "2d")
        rnd (seeded 1348)
        petals 16
        tau (* 2 js/Math.PI)
        [ix iy] (map #(* r %) impact)
        dist (fn [x y] (js/Math.hypot (- x ix) (- y iy)))
        near (fn [x y] (max 0 (- 1 (/ (dist x y) (* r 0.72)))))]  ; 1 at the impact, 0 beyond 0.72r
    (.scale ctx dpr dpr)
    (.translate ctx (/ s 2) (/ s 2))
    ;; glass: annular panes, knocked out near the impact and here and there
    (doseq [[r0 r1 n] [[0.12 0.3 petals] [0.3 0.62 petals] [0.62 0.94 (* 2 petals)]]
            i (range n)]
      (let [a0 (* tau (/ i n)) a1 (* tau (/ (inc i) n))
            am (/ (+ a0 a1) 2) rm (* r (/ (+ r0 r1) 2))
            cx (* rm (js/Math.cos am)) cy (* rm (js/Math.sin am))
            gone? (< (rnd) (+ 0.1 (* 0.9 (near cx cy))))]
        (when-not gone?
          (let [col (nth jewels (mod i (count jewels)))
                a (* (+ 0.08 (* 0.1 (rnd))) (- 1 (* 0.6 (near cx cy))))]
            (.beginPath ctx)
            (.arc ctx 0 0 (* r r1) a0 a1)
            (.arc ctx 0 0 (* r r0) a1 a0 true)
            (.closePath ctx)
            (set! (.-fillStyle ctx) (rgba col a))
            (.fill ctx)))))
    ;; tracery: rings with gaps, spokes snapped short, roundels missing near the impact
    (set! (.-strokeStyle ctx) "rgba(170,160,160,0.09)")
    (set! (.-lineWidth ctx) 1.4)
    (doseq [k [1 0.94 0.62 0.3 0.12]
            seg (range 72)]
      (let [a0 (* tau (/ seg 72)) a1 (* tau (/ (inc seg) 72))
            am (/ (+ a0 a1) 2)]
        (when-not (< (rnd) (+ 0.04 (* 0.95 (near (* r k (js/Math.cos am)) (* r k (js/Math.sin am))))))
          (.beginPath ctx) (.arc ctx 0 0 (* r k) a0 a1) (.stroke ctx))))
    (doseq [i (range petals)]
      (let [a (* tau (/ i petals))
            ca (js/Math.cos a) sa (js/Math.sin a)
            ;; walk out from the hub; the spoke snaps where it meets the damage
            reach (or (some #(when (> (near (* r % ca) (* r % sa)) 0.45) %) (range 0.3 0.95 0.05)) 0.94)
            b (+ a (/ tau petals 2))
            px (* r 0.78 (js/Math.cos b)) py (* r 0.78 (js/Math.sin b))
            qx (* r 0.46 (js/Math.cos b)) qy (* r 0.46 (js/Math.sin b))]
        (.beginPath ctx)
        (.moveTo ctx (* r 0.3 ca) (* r 0.3 sa))
        (.lineTo ctx (* r reach ca) (* r reach sa))
        (.stroke ctx)
        (when (< (near px py) 0.35)
          (.beginPath ctx) (.arc ctx px py (* r 0.2) 0 tau) (.stroke ctx))
        (when (< (near qx qy) 0.35)
          (.beginPath ctx) (.arc ctx qx qy (* r 0.1) 0 tau) (.stroke ctx))))
    ;; cracks: jagged rays from the impact, with branches, clipped to the window
    (.save ctx)
    (.beginPath ctx) (.arc ctx 0 0 (* r 0.97) 0 tau) (.clip ctx)
    (set! (.-strokeStyle ctx) "rgba(225,212,212,0.3)")
    (set! (.-lineWidth ctx) 1)
    (let [rays 13
          ends (vec (for [k (range rays)]
                      (let [a (+ (* tau (/ k rays)) (* 0.35 (- (rnd) 0.5)))
                            len (* r (+ 0.35 (* 0.8 (rnd))))
                            steps 9]
                        (.beginPath ctx)
                        (.moveTo ctx ix iy)
                        (let [pts (vec (for [j (range 1 (inc steps))]
                                         (let [d (* len (/ j steps))
                                               wob (* r 0.03 (- (rnd) 0.5))]
                                           [(+ ix (* d (js/Math.cos a)) (* wob (js/Math.sin a)))
                                            (+ iy (* d (js/Math.sin a)) (- (* wob (js/Math.cos a))))])))]
                          (doseq [[x y] pts] (.lineTo ctx x y))
                          (.stroke ctx)
                          ;; a branch or two
                          (dotimes [_ (js/Math.floor (* 2.5 (rnd)))]
                            (let [[bx by] (nth pts (+ 2 (js/Math.floor (* 5 (rnd)))))
                                  ba (+ a (* (if (< (rnd) 0.5) -1 1) (+ 0.35 (* 0.5 (rnd)))))
                                  bl (* r (+ 0.08 (* 0.2 (rnd))))]
                              (.beginPath ctx)
                              (.moveTo ctx bx by)
                              (.lineTo ctx (+ bx (* bl 0.5 (js/Math.cos ba)) (* r 0.01 (rnd)))
                                       (+ by (* bl 0.5 (js/Math.sin ba))))
                              (.lineTo ctx (+ bx (* bl (js/Math.cos ba))) (+ by (* bl (js/Math.sin ba))))
                              (.stroke ctx)))
                          [a pts]))))]
      ;; the web: jagged rings joining the rays around the impact
      (doseq [ring [1 2 4]]
        (.beginPath ctx)
        (doseq [[k [_ pts]] (map-indexed vector (conj ends (first ends)))]
          (let [[x y] (nth pts (min (dec (count pts)) ring))]
            (if (zero? k) (.moveTo ctx x y) (.lineTo ctx x y))))
        (.stroke ctx)))
    (.restore ctx)
    ;; the hole: knock everything out in a jagged patch around the impact
    (let [n 17
          pts (vec (for [k (range n)]
                     (let [a (* tau (/ k n))
                           d (* r (+ 0.11 (* 0.13 (rnd))))]
                       [(+ ix (* d (js/Math.cos a))) (+ iy (* d (js/Math.sin a)))])))]
      (set! (.-globalCompositeOperation ctx) "destination-out")
      (.beginPath ctx)
      (doseq [[k [x y]] (map-indexed vector pts)] (if (zero? k) (.moveTo ctx x y) (.lineTo ctx x y)))
      (.closePath ctx)
      (set! (.-fillStyle ctx) "#000")
      (.fill ctx)
      (set! (.-globalCompositeOperation ctx) "source-over")
      (set! (.-strokeStyle ctx) "rgba(230,215,215,0.4)")
      (set! (.-lineWidth ctx) 1.2)
      (.stroke ctx))
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
    (set! (.-shards st) (into-array (for [k (range 5)] #js {:t0 nil :delay (* k 1.7)})))
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
    (set! (.-globalAlpha ctx) 0.8)
    (.drawImage ctx (.-img rose) (- (/ rs dpr 2)) (- (/ rs dpr 2)) (/ rs dpr) (/ rs dpr))
    (set! (.-globalAlpha ctx) 1)
    ;; now and then a shard works loose from the hole and falls
    (when-not (.-reduced st)
      (let [r (.-r rose)
            [ix iy] (map #(* r %) impact)]
        (doseq [^js p (.-shards st)]
          (when (or (nil? (.-t0 p)) (> (- secs (.-t0 p)) (+ 4.5 (.-delay p))))
            (set! (.-t0 p) (+ secs (.-delay p)))
            (set! (.-delay p) (+ 2 (rand 7)))
            (set! (.-x p) (+ ix (* r 0.12 (- (rand) 0.5))))
            (set! (.-vx p) (* 30 (- (rand) 0.5)))
            (set! (.-vr p) (* 4 (- (rand) 0.5)))
            (set! (.-size p) (+ 3 (rand 7)))
            (set! (.-col p) (nth jewels (rand-int (count jewels)))))
          (let [tt (- secs (.-t0 p))]
            (when (<= 0 tt 4.5)
              (let [x (+ (.-x p) (* (.-vx p) tt))
                    y (+ iy (* 0.5 180 tt tt))
                    z (.-size p)]
                (.save ctx)
                (.translate ctx x y)
                (.rotate ctx (* (.-vr p) tt))
                (set! (.-globalAlpha ctx) (* 0.9 (max 0 (- 1 (/ tt 4.5)))))
                (.beginPath ctx)
                (.moveTo ctx 0 (- z)) (.lineTo ctx (* 0.7 z) (* 0.6 z)) (.lineTo ctx (* -0.5 z) (* 0.3 z))
                (.closePath ctx)
                (set! (.-fillStyle ctx) (rgba (.-col p) 0.5))
                (.fill ctx)
                (set! (.-strokeStyle ctx) "rgba(220,205,205,0.35)")
                (set! (.-lineWidth ctx) 0.8)
                (.stroke ctx)
                (.restore ctx)))))))
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
