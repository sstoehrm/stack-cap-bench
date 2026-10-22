# Vulnerability gate

Scans each project's CycloneDX SBOM (and the container image, if one is built)
with [OSV-Scanner](https://google.github.io/osv-scanner/) and fails the build only on vulnerabilities
that are known or likely to be exploited, not on CVSS alone.

## Policy

A finding **fails the build** if it is

1. in the [CISA KEV catalog](https://www.cisa.gov/known-exploited-vulnerabilities-catalog), or
2. has an [EPSS](https://www.first.org/epss/) score **≥ 0.1** *and* CVSS **≥ 7.0**.

Everything else is **report only**: it is listed in the job summary and in
`gate-report.json` in the `vuln-gate` artifact, which also holds the SBOMs and
the raw OSV-Scanner reports.

KEV (CISA JSON feed) and EPSS (FIRST API) are fetched on every run, and the
workflow also runs daily: a finding can start failing without any code change.

| Edge case | Rule |
| --- | --- |
| Several CVSS scores (v2, v3.x and v4.0, across aliased advisories) | the highest base score counts (OSV-Scanner's `max_severity`, computed from the vectors) |
| No CVSS score at all | the advisory's severity HIGH or CRITICAL (GHSA) counts as ≥ 7.0; distro records in image scans (Debian, Ubuntu) carry neither, so only KEV can fail them |
| CVE has no EPSS score yet (new CVEs) | rule 2 cannot fire; only KEV fails it |
| ID is not a CVE (GHSA-only) | KEV and EPSS are keyed by CVE, so report only, unless OSV lists a CVE alias |
| KEV or EPSS cannot be fetched, a report or the allowlist is invalid | the gate fails (exit 2), it never passes blind |

Exit codes of `gate.clj`: `0` passed, `1` blocking findings, `2` could not decide.

## Suppressing a finding

Add it to `.vuln-ignore.yaml` at the repository root:

```yaml
ignore:
  - id: CVE-2025-52999       # CVE or GHSA ID, as in the summary table
    reason: >-
      Only used by the build tool on trusted input; bump when upstream ships the fix.
    expires: 2026-12-31      # YYYY-MM-DD
```

- `id`, `reason` and `expires` are all required, and `expires` must be a real
  date; an invalid entry fails the gate.
- `id` is the finding's ID or one of its CVE/GHSA aliases, never a vendor
  advisory (an RHSA can cover many CVEs).
- This file is the only suppression the gate honours: `osv-scanner.toml`
  and `OSV_*` variables are ignored.
- The entry suppresses the finding through `expires`. From the day after, the
  finding is evaluated again and fails the build if it still meets the policy;
  the summary lists the expired entry. Extend the date only with a fresh reason.
- Suppressed findings and entries that match nothing stay visible in the
  summary, so stale entries can be removed.

## Using it in a repository

The SBOM steps are the only per-repository part. Write one CycloneDX JSON
SBOM per project, then call the gate:

```yaml
permissions:
  contents: read
jobs:
  vuln-gate:
    runs-on: ubuntu-24.04            # Linux X64 runners only
    steps:
      - uses: actions/checkout@<sha>
      # ... per-repo SBOM steps, writing sbom/*.cdx.json ...
      - uses: sstoehrm/stack-cap-bench/.github/actions/vuln-gate@<commit sha>
        with:
          sboms: sbom/*.cdx.json     # one path or glob per line
          image: ghcr.io/acme/app:${{ github.sha }}   # optional, e.g. after docker build
          # ignore-file: .vuln-ignore.yaml
          # artifact-name: vuln-gate
```

Inside this repository the same action is `uses: ./.github/actions/vuln-gate`
(see `.github/workflows/vuln-gate.yml`). Pin other repositories to a commit
SHA; `gate.clj` travels with the action at that commit. Make the job a
required status check on `main` to make it a gate for merges. On pull
requests the PR supplies `.vuln-ignore.yaml` (and, in this repository, the
action itself), so put both under CODEOWNERS review.

SBOM steps per stack:

| Stack | SBOM step |
| --- | --- |
| npm | `npx --yes @cyclonedx/cyclonedx-npm@6.0.1 --package-lock-only --output-format JSON --output-file sbom/npm.cdx.json` |
| Maven | `mvn -B org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom -DoutputFormat=json` (writes `target/bom.json`) |
| Gradle | apply the `org.cyclonedx.bom` plugin, run `./gradlew cyclonedxBom` (writes `build/reports/`) |
| Python | `pipx run --spec cyclonedx-bom cyclonedx-py environment --of JSON -o sbom/py.cdx.json` (or `requirements`, `poetry`) |
| Go | `go run github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@<version> mod -json -output sbom/go.cdx.json` |
| deps.edn | `uses: …/.github/actions/sbom-classpath` with `classpath: clojure -Spath -A:<aliases>` |
| Leiningen | same, `classpath: lein with-profile -dev classpath` |
| babashka bb.edn | same, `classpath: bb -e '(println (babashka.classpath/get-classpath))'` |

Only the npm and deps.edn rows run in this repository; pin the current release
of the other tools when adopting a row.

`sbom-classpath` resolves the classpath (downloading every transitive JAR into
`~/.m2`), copies the JARs aside and runs [Syft](https://github.com/anchore/syft)
over them. Syft reads the Maven coordinates (`pom.properties`) inside each JAR,
including JARs shaded into others; for a JAR without them it guesses from the
file name, which may not match advisories, and the step warns about each one. Git and local source deps (`:git/sha`, `:local/root`) are
source trees without coordinates: they are not in the SBOM, and the step warns
about each one. Cache `~/.m2/repository` and `~/.gitlibs` so the resolve is
cheap; the gate caches the OSV-Scanner databases itself.

OSV-Scanner runs offline against databases it downloads per ecosystem, so
the dependency list stays in CI instead of going to osv.dev. Its release binary
is pinned by SHA-256 and its SLSA provenance verified with slsa-verifier;
babashka is pinned by SHA-256. SBOMs must be named `*.cdx.json`, which is how
OSV-Scanner recognises CycloneDX. Keep the gate job free of secrets
(`permissions: contents: read`, `persist-credentials: false`) and separate
from publish and deploy jobs.

## Tests

```sh
bb .github/actions/vuln-gate/gate_test.clj
```
