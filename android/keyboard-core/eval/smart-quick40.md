# Typing eval: smart

**Model:** unigram:en.txt+en_modern.txt  
**Data:** 40 sentences, 460 words, 416 misspelling pairs, 1 seed(s)  
**Fingerprint:** sentences `5bd0a7fdc98f`, misspellings `c2a4e961d8be`, assets `c56f158ef85e`

| metric | normal | sloppy |
| --- | --- | --- |
| no-strip word accuracy | 89.3% | 77.4% |
| no-strip word accuracy (exact) | 89.3% | 77.4% |
| false autocorrections (typed correctly, changed) | 5.4% | 7.3% |
| recovery on mistyped words | 77.9% | 68.9% |
| raw typed-wrong word rate | 31.5% | 64.3% |
|   of which touch mislandings | 29.8% | 62.6% |
| keystroke savings, ideal strip user | 28.6% | 22.2% |
| ideal strip user word accuracy | 96.7% | 89.8% |
| next-word top-1 / top-3 | 6.2% / 12.5% | 5.7% / 12.8% |
| strip latency mean / p95 (us) | 1351.5 us / 5200.1 us | 1542.8 us / 6491.0 us |
| misspellings typed exactly, fixed | 70.9% | 70.9% |
| clean words left alone | 94.2% | 94.2% |

Latency is a single-machine measurement and is not comparable across runs or machines. Metrics come from this corpus subset only; no engine-vs-engine parity is claimed.
