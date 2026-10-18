# Typing eval: baseline

**Model:** lexicon:en.txt+en_modern.txt  
**Data:** 40 sentences, 460 words, 416 misspelling pairs, 1 seed(s)  
**Fingerprint:** sentences `5bd0a7fdc98f`, misspellings `c2a4e961d8be`, assets `c56f158ef85e`

| metric | normal | sloppy |
| --- | --- | --- |
| no-strip word accuracy | 92.2% | 78.9% |
| no-strip word accuracy (exact) | 92.2% | 78.7% |
| false autocorrections (typed correctly, changed) | 0.0% | 0.0% |
| recovery on mistyped words | 75.2% | 67.2% |
| raw typed-wrong word rate | 31.5% | 64.3% |
|   of which touch mislandings | 29.8% | 62.6% |
| keystroke savings, ideal strip user | 27.3% | 18.3% |
| ideal strip user word accuracy | 97.2% | 89.1% |
| next-word top-1 / top-3 | 3.4% / 6.3% | 3.5% / 5.0% |
| strip latency mean / p95 (us) | 1076.6 us / 7274.3 us | 1205.0 us / 7872.9 us |
| misspellings typed exactly, fixed | 77.4% | 77.4% |
| clean words left alone | 99.3% | 99.3% |

Latency is a single-machine measurement and is not comparable across runs or machines. Metrics come from this corpus subset only; no engine-vs-engine parity is claimed.
