# Typing eval: smart

**Model:** unigram:en.txt+en_modern.txt  
**Data:** 40 sentences, 460 words, 416 misspelling pairs, 1 seed(s)  
**Fingerprint:** sentences `5bd0a7fdc98f`, misspellings `c2a4e961d8be`, assets `c56f158ef85e`

| metric | normal | sloppy |
| --- | --- | --- |
| no-strip word accuracy | 92.0% | 75.9% |
| no-strip word accuracy (exact) | 92.0% | 75.7% |
| false autocorrections (typed correctly, changed) | 0.0% | 0.0% |
| recovery on mistyped words | 74.5% | 62.5% |
| raw typed-wrong word rate | 31.5% | 64.3% |
|   of which touch mislandings | 29.8% | 62.6% |
| keystroke savings, ideal strip user | 28.4% | 22.1% |
| ideal strip user word accuracy | 97.6% | 88.3% |
| next-word top-1 / top-3 | 6.7% / 13.7% | 6.9% / 13.4% |
| strip latency mean / p95 (us) | 1325.6 us / 3531.9 us | 1460.4 us / 5932.0 us |
| misspellings typed exactly, fixed | 70.2% | 70.2% |
| clean words left alone | 99.8% | 99.8% |

Latency is a single-machine measurement and is not comparable across runs or machines. Metrics come from this corpus subset only; no engine-vs-engine parity is claimed.
