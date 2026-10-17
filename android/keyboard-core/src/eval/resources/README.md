# Evaluation data

Everything here is for the typing simulator (`:keyboard-core:typingEval`) and its
unit tests only. **Never train the engine on these files, and never ship them in the
app**: the word list and language model must keep learning from real text, and this
corpus is deliberately small and biased towards the sentences the eval checks.

- `*.txt` — sentences, one per line. Blank lines and lines starting with `#` are
  skipped. Every `.txt` in this directory is loaded on every run, sorted by file
  name, so additional corpora (`everyday.txt`, `prompting.txt`, `texting.txt`, ...)
  join the baseline automatically.
- `*misspellings*.tsv` — `wrong<TAB>right` pairs, one per line, `#` comments allowed.
  The right side must be a single word. The spelling metric types the wrong side
  exactly, and the noise presets may type it in place of the right word.

The runner commits its numbers next to itself in `android/keyboard-core/eval/`
(`baseline.json`, `baseline.md`); those are results, not data.
