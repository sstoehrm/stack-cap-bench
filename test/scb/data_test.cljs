(ns scb.data-test
  (:require [cljs.test :refer [deftest is]]
            [scb.data :as data]
            [scb.fixture :refer [results]]))

(def opus-high "claude-opus-5-5|high")
(def fable-high "claude-fable-5-1|high")

(deftest table-means-per-project-then-across
  (let [{:keys [stacks configs cells best]} (data/table results "web" "cost_usd")]
    (is (= ["svelte-java" "react-go"] stacks) "stack order, not alphabetical")
    (is (= [opus-high fable-high] (mapv :key configs)) "model order")
    (is (= 3 (:value (get cells ["svelte-java" opus-high]))) "p2 mean 4, p3 2 → 3")
    (is (= [2 2] (:coverage (get cells ["svelte-java" opus-high]))))
    (is (= 6 (:value (get cells ["react-go" opus-high]))) "the incomplete p3 run is left out")
    (is (= [1 2] (:coverage (get cells ["react-go" opus-high]))))
    (is (= {opus-high 3 fable-high 1} best))))

(deftest standings-and-leanest
  (let [tbl (data/table results "web" "cost_usd")]
    (is (= [{:stack "svelte-java" :mean-rank 0 :configs 2 :wins 2 :losses 0}
            {:stack "react-go" :mean-rank 1 :configs 2 :wins 0 :losses 2}]
           (data/standings [tbl])))
    (is (= "svelte-java" (data/leanest tbl)))))

(deftest topic-totals-count-every-run
  (is (= {:runs 7 :complete 6 :cost 20.0 :tokens 7700 :stacks 2}
         (data/topic-totals results "web"))))

(deftest chart-model-groups-bars-by-stack-and-model
  (let [m (data/chart-model results {:topic "web" :project "all" :models nil :all results})
        [sj rg] (:stacks m)]
    (is (= ["svelte-java" "react-go"] (mapv :id (:stacks m))))
    (is (= [0 1] [(-> sj :series first :bars first :slot) (-> rg :series first :bars first :slot)]))
    (is (= ["Opus 5.5" "Fable 5.1"] (mapv :model (:series sj))))
    (is (= [0 1] (mapv :style (:series sj))) "a model's texture is its place among all models")
    (is (= 3 (-> sj :series first :bars first :value)))
    (is (= ["high"] (mapv :effort (:efforts m))))))

(deftest chart-model-project-filter-and-hidden-models
  (let [m (data/chart-model results {:topic "web" :project "3" :models #{"claude-opus-5-5"} :all results})]
    (is (= ["svelte-java"] (mapv :id (:stacks m))) "react-go has no complete run in project 3")
    (is (= ["Opus 5.5"] (mapv :model (-> m :stacks first :series))))
    (is (= 2 (-> m :stacks first :series first :bars first :value)))))
