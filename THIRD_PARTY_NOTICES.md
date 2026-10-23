# Third-party notices

What the published site (`public/` after `bb release`) contains that was not
written for this repository, and code copied into it. Build tools that only
run at build time (shadow-cljs's compiler, the Closure Compiler, jsdom) are
not distributed and not listed.

## Bundled into `public/js/main.js`

| Component | Version | License | Source |
| --- | --- | --- | --- |
| ClojureScript (`cljs.core`, `clojure.string`) | 1.12.145 | EPL-1.0 | <https://github.com/clojure/clojurescript> |
| Google Closure Library (43 `goog/*` files) | 0.0-20250515-f04e4c0e | Apache-2.0 | <https://github.com/google/closure-library> |
| shadow-cljs module glue (`shadow/module/main/append.js`) | 3.5.3 | EPL-1.0 | <https://github.com/thheller/shadow-cljs> |
| hammer | v0.1.3 | MIT | <https://github.com/sstoehrm/hammer> |

- ClojureScript and shadow-cljs: Copyright Rich Hickey and contributors, and
  Thomas Heller. Eclipse Public License 1.0, [LICENSES/EPL-1.0.txt](LICENSES/EPL-1.0.txt).
  The source code is available at the links above.
- Google Closure Library: Copyright The Closure Library Authors. Apache
  License 2.0, [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
- hammer: Copyright (c) 2026 Sören Stöhrmann, MIT License, the same terms as
  this repository's [LICENSE](LICENSE).

## Fonts in `public/fonts/`

Latin and Latin Extended subsets as served by Google Fonts, under the SIL Open
Font License 1.1 (license text next to each font):

| Font | License text |
| --- | --- |
| Cinzel, Copyright 2020 The Cinzel Project Authors | [OFL-cinzel.txt](public/fonts/OFL-cinzel.txt) |
| Cormorant Garamond, Copyright 2015 the Cormorant Project Authors | [OFL-cormorantgaramond.txt](public/fonts/OFL-cormorantgaramond.txt) |
| JetBrains Mono, Copyright 2020 The JetBrains Mono Project Authors | [OFL-jetbrainsmono.txt](public/fonts/OFL-jetbrainsmono.txt) |

## Copied or adapted code

| File | From | License |
| --- | --- | --- |
| `scripts/import.clj` (the per-run summary rules) | token-comparision `harness/src/harness/report.clj` (`run-summaries`) | MIT, same author |
