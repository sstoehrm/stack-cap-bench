(ns scb.test-env
  "Required first by every test namespace: jsdom globals, the browser APIs
   the page uses, and a recording fake 2d context (jsdom has none)."
  (:require ["jsdom" :refer [JSDOM]]))

(defonce log (atom []))

(defn ops "Recorded 2d calls, [op & args], since the last reset." [] @log)

(defn- fake-2d [^js canvas]
  (js/Proxy.
   #js {:canvas canvas}
   #js {:get (fn [^js t k]
               (cond
                 (or (not (string? k)) (js-in k t)) (aget t k)
                 (= k "measureText") (fn [s] #js {:width (* 7 (count s))})
                 (#{"createLinearGradient" "createRadialGradient"} k) (fn [& _] #js {:addColorStop (fn [& _])})
                 :else (fn [& args] (swap! log conj (into [(keyword k)] args)) nil)))}))

(defn match-media
  "Every matchMedia query answers `matches?` (prefers-reduced-motion, colour scheme)."
  [matches?]
  (set! js/globalThis.matchMedia (fn [_] #js {:matches matches? :addEventListener (fn [& _])})))

(defonce env
  (let [d (JSDOM. "<!DOCTYPE html><html><head><link id=\"css-serious\"><link id=\"css-evil\"></head><body></body></html>" #js {:url "http://localhost/"})
        w (.-window d)
        proto (.. w -HTMLCanvasElement -prototype)]
    (set! js/globalThis.window w)
    (set! js/globalThis.document (.-document w))
    (set! js/globalThis.localStorage (.-localStorage w))
    (set! js/globalThis.location (.-location w))
    (set! js/globalThis.getComputedStyle (.bind (.-getComputedStyle w) w))
    (match-media false)
    (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16)))
    (set! (.-getContext proto)
          (fn [kind] (this-as ^js c (when (= kind "2d") (or (.-__fake c) (set! (.-__fake c) (fake-2d c)))))))
    (js/Object.defineProperty proto "clientWidth" #js {:get (fn [] 800)})
    (js/Object.defineProperty proto "clientHeight" #js {:get (fn [] 400)})
    d))
