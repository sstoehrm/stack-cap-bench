(ns scb.core-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [scb.test-env :as env]
            [hammer.core :refer [dispatch mount!]]
            [hammer.http :as http]
            [hammer.testing :as t]
            [scb.core :as core]
            [scb.fixture :refer [results]]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!))
                     :after (fn [] (http/set-fetch! nil))})

(def db0 {:data nil :error nil :metric "cost_usd" :topic nil :project "all" :tone nil
          :mode "serious" :theme "auto" :brave false :models nil :hover nil :look-rev 0})

(defn- load! [respond check]
  (async done
    (let [el (js/document.createElement "div")
          urls (atom [])]
      (http/set-fetch! (fn [url _] (swap! urls conj url) (respond)))
      (mount! [core/app] el db0)
      (dispatch [:init])
      (t/flush!)
      (js/setTimeout (fn [] (t/flush!) (check el @urls) (done)) 30))))

(deftest loads-results-over-http
  (load! #(js/Promise.resolve (js/Response. (js/JSON.stringify (clj->js results)) #js {:status 200}))
         (fn [el urls]
           (is (= ["results.json"] urls))
           (is (= ["Web development" "CLI tools" "Compare"]
                  (mapv #(.-textContent %) (.querySelectorAll el "section.topic h2")))))))

(deftest http-error-shows-error
  (load! #(js/Promise.resolve (js/Response. "nope" #js {:status 404}))
         (fn [el _] (is (= "Could not load results.json: HTTP 404" (.-textContent (.querySelector el ".state")))))))

(deftest network-failure-shows-error
  (load! #(js/Promise.reject (js/TypeError. "Failed to fetch"))
         (fn [el _] (is (= "Could not load results.json: network" (.-textContent (.querySelector el ".state")))))))

(deftest non-json-shows-error
  (load! #(js/Promise.resolve (js/Response. "<html>" #js {:status 200}))
         (fn [el _] (is (= "Could not load results.json: not JSON" (.-textContent (.querySelector el ".state")))))))

(deftest empty-body-shows-error
  (load! #(js/Promise.resolve (js/Response. "" #js {:status 200}))
         (fn [el _] (is (= "Could not load results.json: no data" (.-textContent (.querySelector el ".state")))))))

(deftest topic-change-clears-hover
  (let [el (js/document.createElement "div")]
    (mount! [core/app] el db0)
    (dispatch [:loaded results])
    (t/flush!)
    (dispatch [:hover "react-go"])
    (t/flush!)
    (is (some? (.querySelector el ".tip")))
    (dispatch [:topic "cli"])
    (t/flush!)
    (is (nil? (.querySelector el ".tip")))))

(deftest nave-only-in-evil-mode
  (let [el (js/document.createElement "div")]
    (mount! [core/app] el db0)
    (dispatch [:loaded results])
    (t/flush!)
    (is (nil? (.querySelector el "#nave")))
    (dispatch [:mode "evil"])
    (t/flush!)
    (is (= "true" (.getAttribute (.querySelector el "canvas#nave") "aria-hidden")))
    (reset! env/log [])
    (t/frame! 16)
    (is (some #(= :drawImage (first %)) (env/ops)) "stone and rose composited")
    (dispatch [:mode "serious"])
    (t/flush!)
    (is (nil? (.querySelector el "#nave")))))
