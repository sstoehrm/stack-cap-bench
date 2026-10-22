(ns gate-test
  "bb .github/actions/vuln-gate/gate_test.clj"
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing run-tests]]))

(load-file (str (fs/path (fs/parent *file*) "gate.clj")))

(def today (java.time.LocalDate/parse "2026-10-22"))

(defn- vuln [id & {:as m}]
  (merge {"VulnerabilityID" id "PkgName" "acme:lib" "InstalledVersion" "1.0" "Severity" "HIGH"} m))

(defn- report [& vulns]
  {"Results" [{"Target" "sbom.cdx.json" "Vulnerabilities" (vec vulns)}]})

(defn- classify [vulns & {:keys [kev epss allow] :or {kev #{} epss {} allow []}}]
  (gate/classify (gate/findings [(apply report vulns)])
                 {:kev kev :epss epss :allowlist allow :today today}))

(defn- ids [fs] (set (map :id fs)))

(defn- cvss [score] {"CVSS" {"nvd" {"V3Score" score}}})

(deftest gate-policy
  (testing "KEV fails regardless of CVSS and EPSS"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" (cvss 3.1))] :kev #{"CVE-2026-0001"}))))))
  (testing "EPSS >= 0.1 and CVSS >= 7.0 fails, both bounds inclusive"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" (cvss 7.0))] :epss {"CVE-2026-0001" 0.1}))))))
  (testing "high CVSS with low EPSS is report only"
    (let [r (classify [(vuln "CVE-2026-0001" (cvss 9.8))] :epss {"CVE-2026-0001" 0.09})]
      (is (empty? (:blocking r)))
      (is (= #{"CVE-2026-0001"} (ids (:report r))))))
  (testing "high EPSS with CVSS below 7.0 is report only"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" (cvss 6.9))] :epss {"CVE-2026-0001" 0.5})))))
  (testing "CVE without an EPSS score is report only"
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" (cvss 9.8))]))))))

(deftest cvss-choice
  (testing "highest base score across sources and CVSS versions"
    (is (= 8.7 (:cvss (first (gate/findings [(report (vuln "CVE-2026-0001" "CVSS" {"ghsa" {"V40Score" 8.7}
                                                                            "redhat" {"V3Score" 7.5}}))]))))))
  (testing "no CVSS block: HIGH/CRITICAL severity counts as >= 7.0"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" "Severity" "CRITICAL")] :epss {"CVE-2026-0001" 0.2})))))
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" "Severity" "MEDIUM")] :epss {"CVE-2026-0001" 0.2}))))))

(deftest non-cve-ids
  (testing "GHSA-only findings have no KEV/EPSS and are report only"
    (let [r (classify [(vuln "GHSA-xxxx-yyyy-zzzz" (cvss 9.8))])]
      (is (empty? (:blocking r)))
      (is (= 1 (count (:report r))))))
  (testing "a CVE in VendorIDs is used for KEV/EPSS"
    (is (= #{"GHSA-xxxx-yyyy-zzzz"}
           (ids (:blocking (classify [(vuln "GHSA-xxxx-yyyy-zzzz" "VendorIDs" ["CVE-2026-0001"])] :kev #{"CVE-2026-0001"})))))))

(deftest dedupe
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
    (is (empty? (:blocking (classify [(vuln "CVE-2026-0001" "VendorIDs" ["GHSA-a"])] :kev #{"CVE-2026-0001"}
                                     :allow [(assoc entry :id "GHSA-a")])))))
  (testing "a vendor advisory ID does not suppress the CVEs it lists"
    (is (= #{"CVE-2026-0001"} (ids (:blocking (classify [(vuln "CVE-2026-0001" "VendorIDs" ["RHSA-2026:1"])] :kev #{"CVE-2026-0001"}
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
