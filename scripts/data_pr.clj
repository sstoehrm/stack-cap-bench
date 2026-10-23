(ns data-pr
  "bb data-pr <token-comparision checkout> [--dry-run] [--base origin/main]
   Imports into a throwaway worktree of origin/main, pushes a data/<sha>
   branch and opens a PR with auto-merge on: main only takes PRs that pass
   the required checks, so new data lands once CI is green. Leaves this
   checkout alone. Prints the PR URL, or that nothing changed."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell sh]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def results "public/results.json")

;; *file* is only bound while the namespace loads
(def ^:private repo (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(defn- data
  "results.json without the import date, which changes daily on its own."
  [s]
  (when s (dissoc (json/parse-string s) "imported")))

(defn- git-show [dir ref path]
  (let [r (sh {:dir dir :continue true} "git" "show" (str ref ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn -main [& argv]
  (let [{:keys [args opts]} (cli/parse-args argv {:coerce {:dry-run :boolean}})
        root (some-> (first args) fs/absolutize str)
        base (:base opts "origin/main")]
    (when-not (and root (fs/exists? (fs/path root "results" "tokens.jsonl")))
      (binding [*out* *err*] (println "usage: bb data-pr <path to a token-comparision checkout> [--dry-run]"))
      (System/exit 1))
    (shell {:dir repo} "git" "fetch" "-q" "origin" "main")
    (let [wt (str (fs/create-temp-dir {:prefix "scb-data-"}))]
      (try
        (shell {:dir repo} "git" "worktree" "add" "-q" "--detach" wt base)
        (shell {:dir wt} "bb" "import" root)
        (let [now (slurp (str (fs/path wt results)))
              {:strs [source runs]} (json/parse-string now)
              sha (second (str/split source #"@"))
              msg (format "data: %s (%d runs)" source (count runs))
              branch (str "data/" sha)]
          (if (= (data now) (data (git-show wt base results)))
            (println "no change:" source "is already on" base)
            (do
              ;; detached: no local branch is left behind in this checkout
              (shell {:dir wt} "git" "commit" "-q" "-m" msg "--" results)
              (if (:dry-run opts)
                (println "dry run:" msg "on" branch "(not pushed)")
                (do
                  (shell {:dir wt} "git" "push" "-q" "--force" "origin" (str "HEAD:refs/heads/" branch))
                  ;; a rerun for the same sha updates the open PR instead of failing
                  (let [existing (sh {:dir wt :continue true} "gh" "pr" "view" branch "--json" "url,state" "-q" "select(.state==\"OPEN\") | .url")
                        url (or (not-empty (str/trim (:out existing)))
                                (-> (sh {:dir wt} "gh" "pr" "create" "--base" "main" "--head" branch
                                        "--title" msg
                                        "--body" (str "Imported with `bb data-pr` from " source "."))
                                    :out str/trim))]
                    ;; GitHub can refuse auto-merge right after a push, while it computes mergeability
                    (loop [attempt 1]
                      (let [r (sh {:dir wt :continue true} "gh" "pr" "merge" url "--auto" "--squash" "--delete-branch")]
                        (cond (zero? (:exit r)) nil
                              (< attempt 4) (do (Thread/sleep 10000) (recur (inc attempt)))
                              :else (throw (ex-info (str "auto-merge not enabled on " url ": " (:err r)) {})))))
                    (println url)))))))
        (finally
          (sh {:dir repo :continue true} "git" "worktree" "remove" "--force" wt))))))
