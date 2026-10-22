#!/usr/bin/env bb
(ns gate
  "Vulnerability gate over OSV-Scanner JSON reports (osv-scanner scan source / scan image --format json).

   bb gate.clj [--ignore .vuln-ignore.yaml] [--summary FILE] [--out FILE] REPORT.json...

   Fails (exit 1) when a finding not suppressed by the allowlist
     a) is in the CISA KEV catalog, or
     b) has EPSS >= 0.1 and CVSS >= 7.0.
   Everything else is report only. Exit 2: the gate could not decide
   (invalid allowlist, unreadable report, KEV or EPSS unavailable)."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.string :as str])
  (:import (java.time LocalDate ZoneOffset)))

(def kev-url "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json")
(def epss-url "https://api.first.org/data/v1/epss")
(def epss-threshold 0.1)
(def cvss-threshold 7.0)

;; ---- findings

(defn- cve? [id] (boolean (re-matches #"CVE-\d{4}-\d+" (str id))))

(def ^:private severity-rank {"LOW" 1 "MODERATE" 2 "MEDIUM" 2 "HIGH" 3 "CRITICAL" 4})

(defn- fixed-versions
  "Fixed versions the advisories list for this package, in their order."
  [vulns pkg]
  (->> (for [v vulns
             a (get v "affected")
             :when (= (get-in a ["package" "name"]) (get pkg "name"))
             r (get a "ranges")
             e (get r "events")
             :let [fixed (get e "fixed")]
             :when fixed]
         fixed)
       distinct
       seq))

(defn findings
  "Vulnerabilities from parsed `osv-scanner --format json` reports, one per
   alias group (as OSV-Scanner groups them), package and version. :cvss is the
   group's max_severity, which OSV-Scanner computes from the CVSS v3/v4 vectors."
  [reports]
  (->> (for [report reports
             result (get report "results")
             p (get result "packages")
             :let [pkg (get p "package")
                   by-id (into {} (map (juxt #(get % "id") identity)) (get p "vulnerabilities"))]
             g (get p "groups")
             :let [[id :as ids] (get g "ids")
                   vulns (keep by-id ids)]]
         {:id id
          :aliases (vec (remove #{id} (distinct (concat ids (get g "aliases")))))
          :pkg (get pkg "name")
          :version (get pkg "version")
          :fixed (some->> (fixed-versions vulns pkg) (str/join ", "))
          :severity (->> vulns (keep #(get-in % ["database_specific" "severity"])) (sort-by severity-rank) last)
          :cvss (parse-double (str (get g "max_severity")))
          :title (some #(get % "summary") vulns)
          :targets [(some-> (get-in result ["source" "path"]) fs/file-name str)]})
       (group-by (juxt :id :pkg :version))
       (map (fn [[_ [f & more]]] (update f :targets #(vec (distinct (concat % (mapcat :targets more)))))))
       (sort-by (juxt :id :pkg :version))
       vec))

(defn- names
  "IDs an allowlist entry may name: the finding's own and its CVE/GHSA aliases,
   not vendor advisories (one RHSA can list many CVEs)."
  [f]
  (set (cons (:id f) (filter #(re-matches #"(CVE|GHSA)-.+" (str %)) (:aliases f)))))

(defn finding-cves [f] (filterv cve? (cons (:id f) (:aliases f))))

;; ---- policy

(defn- high?
  "CVSS >= 7.0; without any CVSS score, the advisory's HIGH/CRITICAL severity stands in."
  [f]
  (if (:cvss f)
    (>= (:cvss f) cvss-threshold)
    (contains? #{"HIGH" "CRITICAL"} (:severity f))))

(defn- annotate [f kev epss]
  (let [cves (finding-cves f)
        scores (keep epss cves)]
    (assoc f
           :kev (boolean (some kev cves))
           :epss (when (seq scores) (apply max scores)))))

(defn- gating? [f]
  (boolean (or (:kev f)
               (and (:epss f) (>= (:epss f) epss-threshold) (high? f)))))

(defn classify
  "Splits findings into :blocking, :suppressed (by an unexpired allowlist entry)
   and :report, and lists :expired and :unused (matching no finding) allowlist entries.
   kev: set of CVE IDs, epss: CVE ID -> score, today: LocalDate (entries are valid
   through their expiry date)."
  [findings {:keys [kev epss allowlist today]}]
  (let [{active false expired true} (group-by #(.isAfter ^LocalDate today (:expires %)) allowlist)
        allowed-by (fn [f] (first (filter #(contains? (names f) (:id %)) active)))
        annotated (map #(assoc (annotate % kev epss) :allow (allowed-by %)) findings)
        {gating true other false} (group-by gating? (remove :allow annotated))
        seen (set (mapcat names findings))]
    {:blocking (vec gating)
     :suppressed (filterv :allow annotated)
     :report (vec other)
     :expired (vec expired)
     :unused (filterv #(not (contains? seen (:id %))) active)}))

;; ---- allowlist

(defn- ->date
  "Strict YYYY-MM-DD; YAML's own timestamps roll over (2026-13-45 is 2027-02-14)."
  [raw]
  (when-let [d (some->> raw str/trim (re-matches #"[\"']?(\d{4}-\d{2}-\d{2})[\"']?") second)]
    (try (LocalDate/parse d) (catch Exception _ nil))))

(defn- raw-expiries
  "Source text of each entry's `expires` scalar, by entry index."
  [s]
  (let [doc (:unmark (yaml/parse-string s :mark true))
        items (some (fn [[k v]] (when (= "ignore" (:unmark k)) (:unmark v))) (when (map? doc) doc))]
    (vec (for [item (when (sequential? items) items)]
           (when (map? (:unmark item))
             (some (fn [[k v]] (when (= "expires" (:unmark k))
                                 (subs s (-> v :start :index) (-> v :end :index))))
                   (:unmark item)))))))

(defn- blank-str? [x] (or (not (string? x)) (str/blank? x)))

(defn parse-allowlist
  "Parses .vuln-ignore.yaml: {ignore: [{id, reason, expires: YYYY-MM-DD}]}.
   Returns {:entries [...] :errors [...]}; any error means the gate cannot pass."
  [s]
  (let [doc (try (yaml/parse-string s) (catch Exception e {::error (ex-message e)}))
        items (:ignore doc)
        raw (when (and (map? doc) (sequential? items)) (raw-expiries s))]
    (cond
      (::error doc) {:entries [] :errors [(str "not valid YAML: " (::error doc))]}
      (nil? doc) {:entries [] :errors []}
      (not (and (map? doc) (or (nil? items) (sequential? items))))
      {:entries [] :errors ["top level must be a map with an `ignore:` list"]}
      :else
      (reduce (fn [acc [i e]]
                (let [label (str "entry " (inc i) (when (map? e) (str " (" (:id e) ")")))
                      id (when (map? e) (some-> (:id e) str str/trim))
                      expires (when (map? e) (->date (get raw i)))
                      errors (cond-> []
                               (not (map? e)) (conj "must be a map")
                               (and (map? e) (str/blank? id)) (conj "id is required")
                               (and (map? e) (blank-str? (:reason e))) (conj "reason is required")
                               (and (map? e) (nil? expires)) (conj "expires must be a date (YYYY-MM-DD)"))]
                  (if (seq errors)
                    (update acc :errors conj (str label ": " (str/join ", " errors)))
                    (update acc :entries conj {:id id :reason (str/trim (:reason e)) :expires expires}))))
              {:entries [] :errors []}
              (map-indexed vector items)))))

;; ---- feeds

(defn- fetch-json [url]
  (loop [attempt 1]
    (let [r (try {:ok (json/parse-string (:body (http/get url {:timeout 60000
                                                               :headers {"Accept" "application/json"}})))}
                 (catch Exception e {:error e}))]
      (cond (contains? r :ok) (:ok r)
            (< attempt 3) (do (Thread/sleep (* 5000 attempt)) (recur (inc attempt)))
            :else (throw (ex-info (str "fetching " url " failed: " (:error r)) {:exit 2}))))))

(defn fetch-kev []
  (let [feed (fetch-json kev-url)
        ids (set (keep #(get % "cveID") (get feed "vulnerabilities")))]
    (when (empty? ids) (throw (ex-info "KEV feed has no entries" {:exit 2})))
    {:ids ids :version (get feed "catalogVersion")}))

(defn epss-batches
  "The EPSS API returns at most 100 rows per request."
  [cves]
  (partition-all 100 (distinct cves)))

(defn fetch-epss [cves]
  (reduce (fn [acc batch]
            (let [resp (fetch-json (str epss-url "?cve=" (str/join "," batch)))
                  data (get resp "data")]
              (when-not (and (= "OK" (get resp "status")) (= (count data) (get resp "total")))
                (throw (ex-info (str "unexpected EPSS response: " (pr-str (dissoc resp "data"))) {:exit 2})))
              (into acc (map (fn [row] [(get row "cve") (parse-double (get row "epss"))])) data)))
          {}
          (epss-batches cves)))

;; ---- output

(defn- why [f]
  (str/join ", " (cond-> []
                   (:kev f) (conj "KEV")
                   (and (:epss f) (>= (:epss f) epss-threshold) (high? f)) (conj "EPSS+CVSS"))))

(defn- cvss-str [f] (if (:cvss f) (format "%.1f" (:cvss f)) (str "– (" (:severity f) ")")))
(defn- epss-str [f] (if (:epss f) (format "%.3f" (:epss f)) "–"))
(defn- md [s] (str/replace (str s) "|" "\\|"))

(defn- table [header rows]
  (str "| " (str/join " | " header) " |\n"
       "|" (str/join "|" (repeat (count header) "---")) "|\n"
       (str/join (for [r rows] (str "| " (str/join " | " (map md r)) " |\n")))))

(defn- finding-row [f]
  [(:id f) (:pkg f) (:version f) (or (:fixed f) "–") (cvss-str f) (epss-str f) (if (:kev f) "yes" "")])

(def report-rows-max 300)

(defn summary [{:keys [blocking suppressed report expired unused]} {:keys [kev-version]}]
  (let [cols ["ID" "Package" "Installed" "Fixed in" "CVSS" "EPSS" "KEV"]]
    (str "## Vulnerability gate: " (if (seq blocking) "❌ failed" "✅ passed") "\n\n"
         "Fails on findings in CISA KEV, or with EPSS ≥ " epss-threshold " and CVSS ≥ " cvss-threshold
         ". KEV catalog " kev-version ".\n\n"
         (when (seq blocking)
           (str "### Blocking (" (count blocking) ")\n\n"
                (table (conj cols "Why") (map #(conj (finding-row %) (why %)) blocking)) "\n"))
         (when (seq expired)
           (str "### Expired allowlist entries (" (count expired) ")\n\n"
                (table ["ID" "Expired after" "Reason"] (map (juxt :id :expires :reason) expired)) "\n"))
         (when (seq suppressed)
           (str "### Suppressed (" (count suppressed) ")\n\n"
                (table (conj cols "Expires" "Reason")
                       (map #(conj (finding-row %) (-> % :allow :expires) (-> % :allow :reason)) suppressed)) "\n"))
         (when (seq unused)
           (str "### Unused allowlist entries (" (count unused) ")\n\n"
                (table ["ID" "Expires" "Reason"] (map (juxt :id :expires :reason) unused)) "\n"))
         "### Report only (" (count report) ")\n\n"
         (if (seq report)
           (str (table cols (map finding-row (take report-rows-max report)))
                (when (> (count report) report-rows-max)
                  (str "\n… " (- (count report) report-rows-max) " more in the gate-report.json artifact.\n")))
           "None.\n"))))

(defn- jsonable [result]
  (let [entry #(update % :expires str)
        finding #(cond-> % (:allow %) (update :allow entry))]
    (-> result
        (update :blocking #(mapv finding %))
        (update :suppressed #(mapv finding %))
        (update :report #(mapv finding %))
        (update :expired #(mapv entry %))
        (update :unused #(mapv entry %)))))

;; ---- main

(def cli-spec {:ignore {:default ".vuln-ignore.yaml"}
               :summary {}
               :out {}
               :today {:desc "YYYY-MM-DD, defaults to today (UTC)"}})

(defn- run [{:keys [opts args]}]
  (when (empty? args) (throw (ex-info "no OSV-Scanner JSON reports given" {:exit 2})))
  (let [today (if (:today opts) (LocalDate/parse (:today opts)) (LocalDate/now ZoneOffset/UTC))
        {:keys [entries errors]} (if (fs/exists? (:ignore opts))
                                   (parse-allowlist (slurp (:ignore opts)))
                                   {:entries [] :errors []})
        _ (when (seq errors)
            (throw (ex-info (str (:ignore opts) " is invalid:\n  " (str/join "\n  " errors)) {:exit 2})))
        fs (findings (map #(json/parse-string (slurp %)) args))
        kev (fetch-kev)
        epss (fetch-epss (mapcat finding-cves fs))
        result (classify fs {:kev (:ids kev) :epss epss :allowlist entries :today today})
        text (summary result {:kev-version (:version kev)})]
    (println text)
    (when (:summary opts) (spit (:summary opts) text :append true))
    (when (:out opts)
      (spit (:out opts) (json/generate-string (assoc (jsonable result)
                                                     :passed (empty? (:blocking result))
                                                     :kev-catalog-version (:version kev)
                                                     :policy {:epss epss-threshold :cvss cvss-threshold})
                                              {:pretty true})))
    (if (seq (:blocking result)) 1 0)))

(defn -main [& argv]
  (let [parsed (cli/parse-args argv {:spec cli-spec})]
    (System/exit
     (try (run parsed)
          (catch Exception e
            (binding [*out* *err*] (println "vuln-gate:" (ex-message e)))
            (when (System/getenv "GITHUB_ACTIONS")
              (println (str "::error title=Vulnerability gate could not decide::"
                            (str/replace (str (ex-message e)) "\n" "%0A"))))
            (when-let [f (-> parsed :opts :summary)]
              (spit f (str "## Vulnerability gate: ⚠️ could not decide\n\n```\n" (ex-message e) "\n```\n") :append true))
            (:exit (ex-data e) 2))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
