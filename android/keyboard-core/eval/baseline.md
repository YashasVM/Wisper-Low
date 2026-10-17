# Typing eval: baseline

40 sentences, 480 words, 40 misspelling pairs, 3 seed(s).

| metric | normal | sloppy |
| --- | --- | --- |
| no-strip word accuracy | 88.1% | 69.2% |
| no-strip word accuracy (exact) | 88.0% | 69.2% |
| false corrections on clean words | 10.7% | 28.1% |
| recovery on noisy words | 68.2% | 36.7% |
| keystroke savings, ideal strip user | 25.8% | 17.2% |
| ideal strip user word accuracy | 92.6% | 78.2% |
| next-word top-1 / top-3 | 1.9% / 3.1% | 2.0% / 2.8% |
| strip latency mean / p95 (us) | 510.6 us / 3643.0 us | 600.0 us / 3782.2 us |
| misspellings typed exactly, fixed | 92.5% | 92.5% |
| clean words left alone | 100.0% | 100.0% |
