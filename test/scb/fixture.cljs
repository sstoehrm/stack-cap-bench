(ns scb.fixture "A small results.json: two topics, four stacks, two models.")

(defn- run [project stack model effort cost & [complete]]
  {:project project :stack stack :run_id (str stack "-" cost) :model model :effort effort
   :agent "claude-code 2.1.282" :input_tokens 1000 :output_tokens 100 :cost_usd cost
   :wall_min 10 :attempts 5 :tool_calls 50 :steps_passed 5 :complete (not= complete :incomplete)})

(def results
  {:source "fixture" :imported "2026-10-04"
   :topics [{:id "web" :label "Web development" :projects [2 3]}
            {:id "cli" :label "CLI tools" :projects [1]}]
   :projects [{:id 1 :name "logan" :kind "cli" :steps 5}
              {:id 2 :name "spendly" :kind "web" :steps 5}
              {:id 3 :name "kanban" :kind "web" :steps 5}]
   :runs [(run 2 "svelte-java" "claude-opus-5-5" "high" 3.0)
          (run 2 "svelte-java" "claude-opus-5-5" "high" 5.0)
          (run 3 "svelte-java" "claude-opus-5-5" "high" 2.0)
          (run 2 "react-go" "claude-opus-5-5" "high" 6.0)
          (run 3 "react-go" "claude-opus-5-5" "high" 1.0 :incomplete)
          (run 2 "svelte-java" "claude-fable-5-1" "high" 1.0)
          (run 2 "react-go" "claude-fable-5-1" "high" 2.0)
          (run 1 "go" "claude-opus-5-5" "high" 1.0)
          (run 1 "rust" "claude-opus-5-5" "high" 2.0)
          (run 1 "rust" "claude-opus-5-5" "low" 0.5)]})
