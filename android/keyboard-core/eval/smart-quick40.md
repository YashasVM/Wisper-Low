# Typing eval: smart

**Model:** unigram:en.txt+en_modern.txt  
**Data:** 40 sentences, 460 words, 416 misspelling pairs, 1 seed(s)  
**Fingerprint:** sentences `5bd0a7fdc98f`, misspellings `c2a4e961d8be`, assets `c56f158ef85e`

| metric | normal | sloppy |
| --- | --- | --- |
| no-strip word accuracy | 92.0% | 79.3% |
| no-strip word accuracy (exact) | 92.0% | 79.3% |
| false autocorrections (typed correctly, changed) | 1.9% | 1.2% |
| recovery on mistyped words | 78.6% | 68.6% |
| raw typed-wrong word rate | 31.5% | 64.3% |
|   of which touch mislandings | 29.8% | 62.6% |
| keystroke savings, ideal strip user | 28.6% | 22.2% |
| ideal strip user word accuracy | 96.7% | 89.8% |
| next-word top-1 / top-3 | 6.2% / 12.7% | 5.7% / 12.8% |
| strip latency mean / p95 (us) | 1677.6 us / 6454.2 us | 1475.4 us / 6120.1 us |
| misspellings typed exactly, fixed | 70.2% | 70.2% |
| clean words left alone | 99.8% | 99.8% |

Latency is a single-machine measurement and is not comparable across runs or machines. Metrics come from this corpus subset only; no engine-vs-engine parity is claimed.
