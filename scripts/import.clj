(ns import
  "bb import <token-comparision checkout> — writes public/results.json.
   The summaries follow harness/src/harness/report.clj (run-summaries) in
   token-comparision, so the numbers match its reports and comparison.html."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [sh]]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def topic-labels {:web "Web development" :cli "CLI tools"})

;; Only the current hammer stacks (clojure+hammer, babashka+hammer) are shown;
;; versioned or skill variants such as clojure+hammer-0.1.0-v4 are experiments.
(defn- hammer-variant? [line] (boolean (re-find #"\+hammer-" (str (:stack line)))))

;; ---- copied from token-comparision harness/src/harness/report.clj

(defn- usage-input [u]
  (+ (or (:input_tokens u) 0) (or (:cache_creation_input_tokens u) 0) (or (:cache_read_input_tokens u) 0)))

(defn- model-usage-input [mu]
  (+ (or (:inputTokens mu) 0) (or (:cacheCreationInputTokens mu) 0) (or (:cacheReadInputTokens mu) 0)))

(defn- line-input-tokens [line]
  (if (seq (:model_usage line))
    (reduce + (map model-usage-input (vals (:model_usage line))))
    (usage-input (:usage line))))

(defn- line-output-tokens [line]
  (if (seq (:model_usage line))
    (reduce + (map #(or (:outputTokens %) 0) (vals (:model_usage line))))
    (or (:output_tokens (:usage line)) 0)))

(defn- agent-label [lines]
  (let [vs (distinct (keep #(when (:agent %) (str/trim (str (:agent %) " " (:agent_version %)))) lines))]
    (when (seq vs) (str/join ", " (sort vs)))))

(defn- run-summaries [lines steps-by-project]
  (vec
   (for [[[project stack run] ls] (sort-by key (group-by (juxt :project :stack :run_id) lines))
         :let [sum (fn [f] (reduce + (map f ls)))
               passed (set (map :step (filter :passed ls)))
               n (get steps-by-project project)]]
     {:project project :stack stack :run_id run
      :model (:model (first ls)) :effort (or (:effort (first ls)) "default")
      :agent (agent-label ls)
      :input_tokens (sum line-input-tokens)
      :output_tokens (sum line-output-tokens)
      :cost_usd (double (sum #(or (:cost_usd %) 0)))
      :wall_min (/ (double (sum #(or (:duration_ms %) 0))) 60000.0)
      :attempts (count ls)
      :tool_calls (when (every? :activity ls) (sum #(get-in % [:activity :tool_calls] 0)))
      :steps_passed (count passed)
      :complete (boolean (and n (= passed (set (range 1 (inc n))))))})))

;; ----

(defn- read-lines [f]
  (->> (str/split-lines (slurp (str f)))
       (remove str/blank?)
       (map #(json/parse-string % true))))

(defn- projects [root]
  (->> (fs/list-dir (fs/path root "projects"))
       (keep #(let [f (fs/path % "project.edn")] (when (fs/exists? f) (edn/read-string (slurp (str f))))))
       (sort-by :id)
       (mapv #(select-keys % [:id :name :kind :steps]))))

(defn -main [& [root]]
  (when-not (and root (fs/exists? (fs/path root "results" "tokens.jsonl")))
    (binding [*out* *err*] (println "usage: bb import <path to a token-comparision checkout>"))
    (System/exit 1))
  (let [ps (projects root)
        lines (remove hammer-variant? (read-lines (fs/path root "results" "tokens.jsonl")))
        sha (str/trim (:out (sh {:dir (str root)} "git" "rev-parse" "--short" "HEAD")))
        topics (vec (for [[kind label] [[:web (topic-labels :web)] [:cli (topic-labels :cli)]]
                          :let [ids (mapv :id (filter #(= kind (:kind %)) ps))]
                          :when (seq ids)]
                      {:id (name kind) :label label :projects ids}))
        out {:source (str "token-comparision@" sha)
             :imported (str (java.time.LocalDate/now))
             :topics topics
             :projects (mapv #(update % :kind name) ps)
             :runs (run-summaries lines (into {} (map (juxt :id :steps) ps)))}]
    (spit "public/results.json" (json/generate-string out {:pretty true}))
    (println (format "public/results.json: %d runs, %d projects, %d topics from %s"
                     (count (:runs out)) (count ps) (count topics) (:source out)))))
