(ns gate-test
  "bb .github/actions/vuln-gate/gate_test.clj"
  {:clj-kondo/config '{:linters {:unresolved-namespace {:exclude [gate]}}}}
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing run-tests]]))

(load-file (str (fs/path (fs/parent *file*) "gate.clj")))

(def today (java.time.LocalDate/parse "2026-10-22"))

(defn- vuln
  "One OSV advisory; report puts each in a group of its own."
  [id & {:keys [aliases cvss severity] :or {severity "HIGH"}}]
  {:id id :aliases (vec aliases) :cvss cvss :severity severity})

(defn- osv-vuln [{:keys [id aliases severity]}]
  {"id" id "aliases" aliases "summary" (str "title of " id)
   "database_specific" {"severity" severity}
   "affected" [{"package" {"name" "acme:lib" "ecosystem" "Maven"}
                "ranges" [{"type" "ECOSYSTEM" "events" [{"introduced" "0"} {"fixed" "1.1"}]}]}
               {"package" {"name" "other:lib" "ecosystem" "Maven"}
                "ranges" [{"type" "ECOSYSTEM" "events" [{"introduced" "0"} {"fixed" "9.9"}]}]}]})

(defn- report
  "osv-scanner --format json output: one package, each vuln its own group."
  [& vulns]
  {"results" [{"source" {"path" "/work/out/sbom_npm.cdx.json" "type" "sbom"}
               "packages" [{"package" {"name" "acme:lib" "version" "1.0" "ecosystem" "Maven"}
                            "groups" (vec (for [v vulns]
                                            {"ids" [(:id v)]
                                             "aliases" (into [(:id v)] (:aliases v))
                                             "max_severity" (if (:cvss v) (str (:cvss v)) "")}))
                            "vulnerabilities" (mapv osv-vuln vulns)}]}]})

(defn- classify [vulns & {:keys [kev epss allow] :or {kev #{} epss {} allow []}}]
  (gate/classify (gate/findings [(apply report vulns)])
                 {:kev kev :epss epss :allowlist allow :today today}))

(defn- ids [fs] (set (map :id fs)))

(deftest gate-policy
  (testing "KEV fails regardless of CVSS and EPSS"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" :cvss 3.1)] :kev #{"CVE-2026-0001"}))))))
  (testing "EPSS >= 0.1 and CVSS >= 7.0 fails, both bounds inclusive"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" :cvss 7.0)] :epss {"CVE-2026-0001" 0.1}))))))
  (testing "high CVSS with low EPSS is report only"
    (let [r (classify [(vuln "CVE-2026-0001" :cvss 9.8)] :epss {"CVE-2026-0001" 0.09})]
      (is (empty? (:blocking r)))
      (is (= #{"CVE-2026-0001"} (ids (:report r))))))
  (testing "high EPSS with CVSS below 7.0 is report only"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" :cvss 6.9)] :epss {"CVE-2026-0001" 0.5})))))
  (testing "CVE without an EPSS score is report only"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" :cvss 9.8)]))))))

(deftest cvss-choice
  (testing "the group's max_severity, which OSV-Scanner computes from the CVSS v3/v4 vectors"
    (is (= 8.7 (:cvss (first (gate/findings [(report (vuln "CVE-2026-0001" :cvss 8.7))]))))))
  (testing "no CVSS score: GHSA severity HIGH/CRITICAL counts as >= 7.0"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" :severity "CRITICAL")] :epss {"CVE-2026-0001" 0.2})))))
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" :severity "MODERATE")] :epss {"CVE-2026-0001" 0.2}))))))

(deftest osv-fields
  (let [f (first (gate/findings [(report (vuln "GHSA-xxxx-yyyy-zzzz" :aliases ["CVE-2026-0001"] :cvss 9.8))]))]
    (testing "package, version and the fix for this package only"
      (is (= ["acme:lib" "1.0" "1.1"] ((juxt :pkg :version :fixed) f))))
    (testing "title and the SBOM it came from"
      (is (= ["title of GHSA-xxxx-yyyy-zzzz" ["sbom_npm.cdx.json"]] ((juxt :title :targets) f)))))
  (testing "advisories OSV-Scanner groups as aliases are one finding"
    (let [r (assoc-in (report (vuln "GHSA-a" :aliases ["CVE-2026-0001"]) (vuln "PYSEC-1" :aliases ["CVE-2026-0001"]))
                      ["results" 0 "packages" 0 "groups"]
                      [{"ids" ["GHSA-a" "PYSEC-1"] "aliases" ["CVE-2026-0001" "GHSA-a" "PYSEC-1"] "max_severity" "7.5"}])
          fs (gate/findings [r])]
      (is (= 1 (count fs)))
      (is (= ["CVE-2026-0001"] (gate/finding-cves (first fs))))))
  (testing "no vulnerabilities: no findings"
    (is (empty? (gate/findings [{"results" []} {}])))))

(deftest non-cve-ids
  (testing "GHSA-only findings have no KEV/EPSS and are report only"
    (let [r (classify [(vuln "GHSA-xxxx-yyyy-zzzz" :cvss 9.8)])]
      (is (empty? (:blocking r)))
      (is (= 1 (count (:report r))))))
  (testing "a CVE alias is used for KEV/EPSS"
    (is (= #{"GHSA-xxxx-yyyy-zzzz"}
           (ids (:blocking (classify [(vuln "GHSA-xxxx-yyyy-zzzz" :aliases ["CVE-2026-0001"])] :kev #{"CVE-2026-0001"})))))))

(deftest dedupe-across-reports
  (testing "the same vulnerability in the same package from two reports counts once"
    (is (= 1 (count (gate/findings [(report (vuln "CVE-2026-0001")) (report (vuln "CVE-2026-0001"))]))))))

(def entry {:id "CVE-2026-0001" :reason "not reachable" :expires (java.time.LocalDate/parse "2026-12-31")})

(deftest allowlist
  (testing "a valid entry suppresses a blocking finding"
    (let [r (classify [(vuln "CVE-2026-0001")] :kev #{"CVE-2026-0001"} :allow [entry])]
      (is (empty? (:blocking r)))
      (is (= #{"CVE-2026-0001"} (ids (:suppressed r))))))
  (testing "an entry is valid through its expiry date"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001")] :kev #{"CVE-2026-0001"} :allow [(assoc entry :expires today)])))))
  (testing "an expired entry no longer suppresses: the finding fails again"
    (let [r (classify [(vuln "CVE-2026-0001")] :kev #{"CVE-2026-0001"}
                      :allow [(assoc entry :expires (java.time.LocalDate/parse "2026-10-21"))])]
      (is (= #{"CVE-2026-0001"} (ids (:blocking r))))
      (is (= ["CVE-2026-0001"] (map :id (:expired r))))))
  (testing "an entry can name the GHSA alias"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" :aliases ["GHSA-a"])] :kev #{"CVE-2026-0001"}
                                     :allow [(assoc entry :id "GHSA-a")])))))
  (testing "a vendor advisory ID does not suppress the CVEs it lists"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" :aliases ["RHSA-2026:1"])] :kev #{"CVE-2026-0001"}
                                                      :allow [(assoc entry :id "RHSA-2026:1")]))))))
  (testing "entries matching no finding are listed as unused"
    (is (= ["CVE-2026-0009"] (map :id (:unused (classify [(vuln "CVE-2026-0001")] :allow [(assoc entry :id "CVE-2026-0009")])))))))

(deftest parse-allowlist
  (testing "unquoted and quoted dates both parse"
    (let [{:keys [entries errors]} (gate/parse-allowlist
                                    "ignore:\n  - id: CVE-2026-0001\n    reason: r\n    expires: 2026-12-31\n  - id: CVE-2026-0002\n    reason: r\n    expires: \"2026-11-30\"\n")]
      (is (empty? errors))
      (is (= [(java.time.LocalDate/parse "2026-12-31") (java.time.LocalDate/parse "2026-11-30")] (map :expires entries)))))
  (testing "empty file and empty list are fine"
    (is (= {:entries [] :errors []} (gate/parse-allowlist "")))
    (is (= {:entries [] :errors []} (gate/parse-allowlist "ignore: []\n"))))
  (testing "missing reason, missing or bad expiry and missing id are errors"
    (is (= 4 (count (:errors (gate/parse-allowlist
                              (str "ignore:\n"
                                   "  - {id: CVE-2026-0001, expires: 2026-12-31}\n"
                                   "  - {id: CVE-2026-0002, reason: r}\n"
                                   "  - {id: CVE-2026-0003, reason: r, expires: someday}\n"
                                   "  - {reason: r, expires: 2026-12-31}\n")))))))
  (testing "impossible or non-date expiries are errors, not rolled over"
    (doseq [d ["2026-13-45" "2026-02-30" "2026-12-31T23:30:00-05:00" "\"2026-1-5\""]]
      (is (= 1 (count (:errors (gate/parse-allowlist (str "ignore:\n  - id: CVE-2026-0001\n    reason: r\n    expires: " d "\n"))))) d)))
  (testing "a trailing comment after the date is fine"
    (is (empty? (:errors (gate/parse-allowlist "ignore:\n  - id: CVE-2026-0001\n    reason: r\n    expires: 2026-12-31   # short\n")))))
  (testing "a top level that is not {ignore: [...]} is an error"
    (is (seq (:errors (gate/parse-allowlist "- id: CVE-2026-0001\n"))))))

(deftest epss-batches
  (testing "at most 100 CVEs per request"
    (is (= [100 100 50] (map count (gate/epss-batches (map #(str "CVE-" %) (range 250))))))))

(let [{:keys [fail error]} (run-tests 'gate-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
