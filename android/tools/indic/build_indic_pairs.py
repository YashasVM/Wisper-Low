#!/usr/bin/env python3
"""Build small Latin-romanization pair tables for the Wisperlow typing engine.

Reads Aksharantar train-split JSONL (``<code>_train.json`` inside the
per-language zip) and writes one UTF-8 NFC TSV per language::

    roman<TAB>native<TAB>positive_count

Only the *mined* Aksharantar sources (Wikidata, Samanantar, IndicCorp) are
used. The Aksharantar paper (Madhani et al., Findings of EMNLP 2023) states
that "all the mined data is available under the CC0 license", while the
manually collected rows and the imported third-party "Dakshina" rows carry
CC BY / CC BY-SA — those are excluded so the shipped asset is CC0-only.

Hard rules enforced by this builder:

- **Train split only.** Only the ``<code>_train.json`` member is ever read;
  test/valid members are never opened, so no evaluation data can leak into
  the asset (``--split`` only accepts ``train``).
- **Deterministic.** No timestamp enters the output; every sort is fully
  specified; identical inputs and options produce byte-identical files.
- **Capped.** At most ``--max-pairs`` pairs and ``--max-bytes`` bytes per
  language (defaults 100 000 and 6 000 000, matching the per-language asset
  budget in ``docs/keyboard/research/transliteration.md``).

Pure Python 3 standard library; no dependencies.
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import zipfile
from collections import defaultdict

# ---------------------------------------------------------------------------
# Language configuration
# ---------------------------------------------------------------------------

# ``member_prefix`` is the dataset's internal language code (hin/kan), not the
# Wisperlow tag (hi/kn). ``block`` is the inclusive Unicode range of the
# language's script.
LANGUAGES = {
    "hi": {
        "display": "Hindi",
        "member_prefix": "hin",
        "block": (0x0900, 0x097F),  # Devanagari
        "script_name": "Devanagari",
        "asset": "hi.tsv",
        "dataset_page": "https://indicnlp.ai4bharat.org/aksharantar/",
    },
    "kn": {
        "display": "Kannada",
        "member_prefix": "kan",
        "block": (0x0C80, 0x0CFF),  # Kannada
        "script_name": "Kannada",
        "asset": "kn.tsv",
        "dataset_page": "https://indicnlp.ai4bharat.org/aksharantar/",
    },
}

# Sources the paper licenses CC0 ("all the mined data"). The manually created
# rows (benchmark / manual transliterations = CC BY 4.0) and the imported
# third-party rows (source "Dakshina" = CC BY-SA 4.0) must never enter this
# asset. "Existing" is a compilation of prior transliteration corpora whose
# per-row licenses cannot be verified, so it is excluded as well.
MINED_SOURCES = frozenset({"Wikidata", "Samanantar", "IndicCorp"})

# Per-row acceptance constants.
MAX_ROMAN_CHARS = 64      # length of the Latin key string
MAX_NATIVE_CHARS = 128    # length of the native-script word in codepoints
MAX_ROMANS_PER_NATIVE = 8  # keeps one popular word from flooding the cap

_ZOM = re.compile(r"[\u200c\u200d]")  # ZWJ / ZWNJ — dropped (docs §3.1)
_ASCII_LOWER = re.compile(r"^[a-z]+$")
# URL / email / junk guard, applied before the charset check so the rejection
# reason is legible. A plain domain-like word (``paytm``) survives; ``a@b``,
# ``http://`` schemes and dotted domains do not.
_JUNK_RE = re.compile(r"@|://|www\.|\.(com|net|org|in|io|gov|edu|xyz|info)\b", re.IGNORECASE)


# ---------------------------------------------------------------------------
# Row validation
# ---------------------------------------------------------------------------

def classify_reject(roman, native, block=None):
    """Return a short rejection reason, or None when the pair is acceptable.

    ``roman`` must already be NFC, lower-cased and stripped; ``native`` must
    already be NFC, stripped and ZWJ/ZWNJ-free. ``block`` is the script's
    ``(lo, hi)`` unicode range; when given, any codepoint outside it makes
    the word ``mixed_script``. Check order only decides which reason is
    reported first.
    """
    if not roman or not native:
        return "empty_field"
    if len(roman) > MAX_ROMAN_CHARS or len(native) > MAX_NATIVE_CHARS:
        return "too_long"
    if _JUNK_RE.search(roman) or _JUNK_RE.search(native):
        return "url_email_junk"
    for ch in roman:
        if unicodedata.category(ch)[0] == "C":  # control/format/unassigned
            return "control_char"
    if not _ASCII_LOWER.match(roman):
        return "non_latin_roman"
    for ch in native:
        cat = unicodedata.category(ch)
        if cat[0] == "C":
            return "control_char"
        if cat[0] not in "LM":  # letters and combining marks only
            return "bad_native_category"
    if block is not None:
        lo, hi = block
        if any(not (lo <= ord(ch) <= hi) for ch in native):
            return "mixed_script"
    return None


def valid_native_script(native, block):
    """True when every codepoint sits in the language's script block."""
    lo, hi = block
    return all(lo <= ord(ch) <= hi for ch in native)


# ---------------------------------------------------------------------------
# Pair extraction
# ---------------------------------------------------------------------------

def sha256_member(zip_path, member, chunk=1 << 20):
    """Streamed sha256 of one zip member (the decompressed bytes)."""
    h = hashlib.sha256()
    with zipfile.ZipFile(zip_path) as z:
        with z.open(member) as f:
            while True:
                data = f.read(chunk)
                if not data:
                    break
                h.update(data)
    return h.hexdigest()


def build_pairs(zip_path, lang, split):
    """Aggregate exact (roman, native) pairs from one Aksharantar zip.

    Returns ``(pairs, stats)``; ``pairs`` maps (roman, native) -> attestation
    count. Only the requested split member is ever read.
    """
    cfg = LANGUAGES[lang]
    member = "%s_%s.json" % (cfg["member_prefix"], split)
    block = cfg["block"]

    stats = {
        "member": member,
        "rows": 0,
        "mined_rows": 0,
        "reject_reasons": defaultdict(int),
    }
    pairs = defaultdict(int)

    with zipfile.ZipFile(zip_path) as z:
        if member not in set(z.namelist()):
            raise SystemExit("error: %s has no member %r" % (zip_path, member))
        with z.open(member) as raw:
            reader = io.TextIOWrapper(raw, encoding="utf-8", errors="replace")
            for line in reader:
                line = line.strip()
                if not line:
                    continue
                stats["rows"] += 1
                try:
                    rec = json.loads(line)
                except ValueError:
                    stats["reject_reasons"]["bad_json"] += 1
                    continue
                if not isinstance(rec, dict) or rec.get("source") not in MINED_SOURCES:
                    stats["reject_reasons"]["not_mined_source"] += 1
                    continue
                stats["mined_rows"] += 1
                roman = unicodedata.normalize("NFC", rec.get("english word") or "")
                roman = roman.strip().lower()
                native = unicodedata.normalize("NFC", rec.get("native word") or "")
                native = _ZOM.sub("", native).strip()
                reason = classify_reject(roman, native, block)
                if reason is not None:
                    stats["reject_reasons"][reason] += 1
                    continue
                pairs[(roman, native)] += 1

    return pairs, stats


# ---------------------------------------------------------------------------
# Selection and rendering
# ---------------------------------------------------------------------------

def rank_natives(pairs):
    """Natives ordered by attestation frequency, ties lexicographic (det.)."""
    freq = defaultdict(int)
    for (roman, native), count in pairs.items():
        freq[native] += count
    return sorted(freq, key=lambda n: (-freq[n], n))


def render_body(pairs, max_pairs, max_bytes):
    """Render the TSV rows under the pair and byte caps.

    Natives are ranked by frequency (most attested first); within a native,
    roman alternatives are ranked by attestation count. Both orderings are
    fully specified, so the output is deterministic.
    """
    by_native = defaultdict(dict)
    for (roman, native), count in pairs.items():
        by_native[native][roman] = count
    body = []
    pair_count = 0
    byte_count = 0
    native_count = 0
    for native in rank_natives(pairs):
        romans = by_native[native]
        ranked = sorted(romans.items(), key=lambda c: (-c[1], c[0]))
        ranked = ranked[:MAX_ROMANS_PER_NATIVE]
        if pair_count + len(ranked) > max_pairs:
            break
        segment = "".join("%s\t%s\t%d\n" % (r, native, c) for r, c in ranked)
        seg_bytes = len(segment.encode("utf-8"))
        if byte_count + seg_bytes > max_bytes:
            break
        body.append(segment)
        pair_count += len(ranked)
        byte_count += seg_bytes
        native_count += 1
    return "".join(body), pair_count, byte_count, native_count


def render_header(lang, zip_path, split, max_pairs, max_bytes, stats, pairs_selected, natives_selected):
    """Human-readable provenance header placed at the top of each TSV."""
    cfg = LANGUAGES[lang]
    member = stats["member"]
    digest = sha256_member(zip_path, member)
    src = "the Aksharantar train split (%s from %s)" % (member, os.path.basename(zip_path))
    return "\n".join(
        [
            "# Wisperlow romanization pairs — %s (%s)." % (cfg["display"], lang),
            "# Format: roman<TAB>native<TAB>positive_count (UTF-8, NFC-normalized).",
            "#   roman  lowercase a-z Latin string (typing-engine key alphabet)",
            "#   native %s word: letters + combining marks, ZWJ/ZWNJ removed" % cfg["script_name"],
            "#   count  positive attestation count of this exact roman/native pair",
            "#   in the referenced split rows. Roman alternatives of one native",
            "#   word are separate rows, ranked by count then alphabetically.",
            "# Natives are ranked by total attestation frequency (descending),",
            "# ties broken lexicographically by the native word.",
            "#",
            "# Source data: %s." % src,
            "# Dataset page: %s (AI4Bharat IndicXlit project)." % cfg["dataset_page"],
            "# Input member sha256 (raw JSONL bytes): %s." % digest,
            "#",
            "# Split used: %s only. The dev and test members of the dataset" % split,
            "#   were never read, so no evaluation data contaminates this file.",
            "#",
            "# License: CC0 1.0 (public domain dedication).",
            "#   Evidence: Madhani et al., `Aksharantar: Towards building open",
            "#   transliteration tools for the next billion users`",
            "#   (arXiv:2205.03018; Findings of EMNLP 2023) —",
            "#   \"all the mined data is available under the CC0 license\" (fn. 4:",
            "#   https://creativecommons.org/publicdomain/zero/1.0/).",
            "#   Rows used here carry source Wikidata, Samanantar, or IndicCorp.",
            "#   Excluded: source Dakshina (CC BY-SA 4.0, third-party), AK-Freq and",
            "#   manual/benchmark rows (CC BY 4.0), and source Existing (mixed prior",
            "#   corpora). See assets/licenses/indic-romanization.txt for the full",
            "#   attestation file.",
            "#",
            "# Caps: %d pairs max per language (used %d), %d bytes max per language."
            % (max_pairs, pairs_selected, max_bytes),
            "# Natives emitted: %d. Deterministic build via build_indic_pairs.py" % natives_selected,
            "# (identical inputs and options produce byte-identical output).",
            "#",
            "# Generation command (run from the repository root; point the zip",
            "#   flag at your local copy of the archive named above):",
            "#   python3 -I android/tools/indic/build_indic_pairs.py \\",
            "#       --%s-zip %s \\" % (lang, os.path.basename(zip_path)),
            "#       --out-dir android/app/src/main/assets/indic \\",
            "#       --max-pairs %d --max-bytes %d --split %s" % (max_pairs, max_bytes, split),
        ]
    ) + "\n"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Build hi.tsv/kn.tsv romanization pair tables from Aksharantar zips.")
    parser.add_argument("--hi-zip", help="path to aksharantar hin.zip (Hindi)")
    parser.add_argument("--kn-zip", help="path to aksharantar kan.zip (Kannada)")
    parser.add_argument("--out-dir", required=True, help="output directory")
    parser.add_argument("--max-pairs", type=int, default=100000,
                        help="max pairs per language (default 100000)")
    parser.add_argument("--max-bytes", type=int, default=6000000,
                        help="max file bytes per language (default 6000000)")
    parser.add_argument("--split", default="train", choices=("train",),
                        help="dataset split; only train is allowed so no eval "
                             "rows can ever reach the asset")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be written without writing files")
    args = parser.parse_args(argv)

    langs = []
    if args.hi_zip:
        langs.append(("hi", args.hi_zip))
    if args.kn_zip:
        langs.append(("kn", args.kn_zip))
    if not langs:
        parser.error("pass --hi-zip and/or --kn-zip")

    if not args.dry_run:
        os.makedirs(args.out_dir, exist_ok=True)

    for lang, zip_path in langs:
        pairs, stats = build_pairs(zip_path, lang, args.split)

        body, out_pairs, out_bytes, natives_selected = render_body(
            pairs, args.max_pairs, args.max_bytes)
        header = render_header(
            lang=lang,
            zip_path=zip_path,
            split=args.split,
            max_pairs=args.max_pairs,
            max_bytes=args.max_bytes,
            stats=stats,
            pairs_selected=out_pairs,
            natives_selected=natives_selected,
        )
        text = header + body
        text_bytes = len(text.encode("utf-8"))

        rejects = ", ".join(
            "%s=%d" % (k, v) for k, v in sorted(stats["reject_reasons"].items()))
        print(
            "%s: rows=%d mined=%d unique-roman-pairs=%d -> pairs=%d bytes=%d "
            "(rejects: %s)"
            % (lang, stats["rows"], stats["mined_rows"], len(pairs), out_pairs,
               text_bytes, rejects or "none"),
            file=sys.stderr,
        )
        if text_bytes > args.max_bytes:
            print("error: %s file would exceed --max-bytes" % lang, file=sys.stderr)
            return 1
        if args.dry_run:
            continue
        out_path = "%s/%s" % (args.out_dir.rstrip("/"), LANGUAGES[lang]["asset"])
        with open(out_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote %s (%d bytes, %d pairs)" % (out_path, text_bytes, out_pairs),
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
