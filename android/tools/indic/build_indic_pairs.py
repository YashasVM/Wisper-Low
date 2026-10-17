#!/usr/bin/env python3
"""Build small Latin-romanization pair tables for the Wisperlow typing engine.

Reads the Aksharantar train-split JSONL (``<code>_train.json`` inside the
per-language zip) and, optionally, the Dakshina v1.0 word-lexicon train TSV
(``<code>.translit.sampled.train.tsv``) and writes one UTF-8 NFC TSV per
language::

    roman<TAB>native<TAB>positive_count

Sources and licensing
---------------------

* **Aksharantar** (AI4Bharat IndicXlit) — only the *mined* rows (Wikidata,
  Samanantar, IndicCorp) are used. The paper states that "all the mined data
  is available under the CC0 license" (Madhani et al., Findings of EMNLP
  2023). The manually created rows and the imported third-party "Dakshina"
  rows inside Aksharantar carry CC BY / CC BY-SA and are excluded here.
* **Dakshina v1.0** (Google Research) — the word-lexicon *train* TSV only.
  The Dakshina README licenses the dataset CC BY-SA 4.0 (Roark et al., LREC
  2020, pp. 2413-2423).

Because the shipped asset may incorporate Dakshina (CC BY-SA 4.0), the
*combined* pair asset is distributed under CC BY-SA 4.0, while the
Aksharantar-derived rows remain available under their original CC0
dedication. See ``android/app/src/main/assets/licenses/indic-romanization.txt``
for the full attestation file.

Hard rules enforced by this builder:

- **Train split only.** For Aksharantar only the ``<code>_train.json`` member
  is ever opened. For Dakshina only a ``...train.tsv`` word-lexicon file is
  accepted; dev/test lexicons and the full-sentence ``romanized`` tree are
  refused at the CLI and again inside the library. No evaluation data can
  leak into the asset.
- **Deterministic.** No timestamp enters the output; every sort is fully
  specified; identical inputs and options produce byte-identical files.
- **Capped.** At most ``--max-pairs`` pairs and ``--max-bytes`` bytes per
  language (defaults 100 000 and 6 000 000, matching the per-language asset
  budget in ``docs/keyboard/research/transliteration.md``).
- **Source priority.** Valid Dakshina train-lexicon pairs are selected first
  (all of them, capped at ``MAX_ROMANS_PER_NATIVE`` romanizations per native
  word), then the remaining budget is filled with the Aksharantar mined
  pairs. The two sources' attestation counts come from different collection
  methods and are *not* true frequencies of one another, so they are never
  summed or compared; on the (unobserved) collision of an identical pair the
  Dakshina row wins and its count is kept.

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
# language's script. ``dakshina_name`` is the Dakshina v1.0 lexicon train file.
LANGUAGES = {
    "hi": {
        "display": "Hindi",
        "member_prefix": "hin",
        "block": (0x0900, 0x097F),  # Devanagari
        "script_name": "Devanagari",
        "asset": "hi.tsv",
        "dataset_page": "https://indicnlp.ai4bharat.org/aksharantar/",
        "dakshina_name": "hi.translit.sampled.train.tsv",
    },
    "kn": {
        "display": "Kannada",
        "member_prefix": "kan",
        "block": (0x0C80, 0x0CFF),  # Kannada
        "script_name": "Kannada",
        "asset": "kn.tsv",
        "dataset_page": "https://indicnlp.ai4bharat.org/aksharantar/",
        "dakshina_name": "kn.translit.sampled.train.tsv",
    },
}

# Sources the Aksharantar paper licenses CC0 ("all the mined data"). The
# manually created rows (benchmark / manual transliterations = CC BY 4.0) and
# the imported third-party rows (source "Dakshina" = CC BY-SA 4.0) must never
# enter this asset. "Existing" is a compilation of prior transliteration
# corpora whose per-row licenses cannot be verified, so it is excluded too.
MINED_SOURCES = frozenset({"Wikidata", "Samanantar", "IndicCorp"})

# Dakshina provenance (checked against the upstream README).
DAKSHINA_DATASET_PAGE = "https://github.com/google-research-datasets/dakshina"
DAKSHINA_ARCHIVE = "https://storage.googleapis.com/dakshina/dakshina_dataset_v1.0.tar"
DAKSHINA_LICENSE = "CC BY-SA 4.0"
DAKSHINA_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
DAKSHINA_PAPER = (
    'Roark et al. 2020, "Processing South Asian Languages Written in the '
    'Latin Script: the Dakshina Dataset", LREC, pp. 2413-2423'
)
DAKSHINA_PAPER_URL = "https://www.aclweb.org/anthology/2020.lrec-1.294"

# Per-row acceptance constants.
MAX_ROMAN_CHARS = 64      # length of the Latin key string
MAX_NATIVE_CHARS = 128    # length of the native-script word in codepoints
MAX_ROMANS_PER_NATIVE = 8  # keeps one popular word from flooding the cap

_ZOM = re.compile(r"[\u200c\u200d]")  # ZWJ / ZWNJ — dropped (docs §3.1)
_ASCII_LOWER = re.compile(r"^[a-z]+$")
_POS_INT = re.compile(r"^[0-9]+$")    # Dakshina attestation count
# URL / email / junk guard, applied before the charset check so the rejection
# reason is legible. A plain domain-like word (``paytm``) survives; ``a@b``,
# ``http://`` schemes and dotted domains do not.
_JUNK_RE = re.compile(r"@|://|www\.|\.(com|net|org|in|io|gov|edu|xyz|info)\b", re.IGNORECASE)
# Filename tokens that mark a Dakshina split. A train lexicon must carry the
# ``train`` token and must not carry ``dev``/``test``.
_DAKSHINA_SPLIT_TOKENS = re.compile(r"[^0-9a-z]+")


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


def sha256_file(path, chunk=1 << 20):
    """Streamed sha256 of a plain file (its raw bytes)."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
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


def validate_dakshina_path(path):
    """Refuse anything but a Dakshina word-lexicon *train* TSV.

    Guards both the CLI and direct library calls: the full-sentence
    ``romanized`` tree (which holds validation-derived sentences) and the
    dev/test lexicon splits are rejected outright.
    """
    if not isinstance(path, str) or not path:
        raise SystemExit("error: empty --dakshina path")
    norm = os.path.normpath(path)
    parts = [p.lower() for p in norm.split(os.sep) if p not in ("", os.curdir)]
    base = parts[-1] if parts else ""
    if "romanized" in parts:
        raise SystemExit(
            "error: %s is Dakshina full-sentence romanized data; only the "
            "word-lexicon train TSV is allowed" % path)
    tokens = set(t for t in _DAKSHINA_SPLIT_TOKENS.split(base) if t)
    if "dev" in tokens or "test" in tokens:
        raise SystemExit(
            "error: %s is a Dakshina dev/test split; only the train lexicon "
            "is allowed (no evaluation data in the asset)" % path)
    if "train" not in tokens:
        raise SystemExit(
            "error: %s does not look like a Dakshina train lexicon "
            "(expected ...translit.sampled.train.tsv)" % path)
    if not os.path.isfile(path):
        raise SystemExit("error: no such Dakshina train lexicon: %s" % path)
    return norm


def build_dakshina_pairs(tsv_path, lang):
    """Aggregate (roman, native) -> attestation count from a Dakshina train TSV.

    The Dakshina lexicon layout is ``native<TAB>roman<TAB>attestations`` (one
    pair per line, so aggregation is an identity; any duplicate exact pair is
    summed). Rows are run through the same validation as the Aksharantar
    rows. Returns ``(pairs, stats)``; only the given train file is read.
    """
    validate_dakshina_path(tsv_path)
    block = LANGUAGES[lang]["block"]

    stats = {
        "path": tsv_path,
        "rows": 0,
        "accepted_rows": 0,
        "reject_reasons": defaultdict(int),
    }
    pairs = defaultdict(int)

    with open(tsv_path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            stats["rows"] += 1
            fields = line.split("\t")
            if len(fields) != 3:
                stats["reject_reasons"]["bad_columns"] += 1
                continue
            native_raw, roman_raw, count_raw = fields
            if not _POS_INT.match(count_raw.strip()) or int(count_raw) <= 0:
                stats["reject_reasons"]["bad_count"] += 1
                continue
            roman = unicodedata.normalize("NFC", roman_raw).strip().lower()
            native = unicodedata.normalize("NFC", native_raw)
            native = _ZOM.sub("", native).strip()
            reason = classify_reject(roman, native, block)
            if reason is not None:
                stats["reject_reasons"][reason] += 1
                continue
            pairs[(roman, native)] += int(count_raw)
            stats["accepted_rows"] += 1

    return pairs, stats


def merge_pairs(primary, secondary):
    """Union of two pair maps; ``primary`` wins on an identical pair.

    ``primary`` is the higher-priority source (Dakshina). Counts are never
    summed across sources because they come from different collection
    methods; on collision the primary count is kept.
    """
    merged = dict(secondary)
    merged.update(primary)
    return merged


# ---------------------------------------------------------------------------
# Selection and rendering
# ---------------------------------------------------------------------------

def rank_natives(pairs):
    """Natives ordered by attestation frequency, ties lexicographic (det.)."""
    freq = defaultdict(int)
    for (roman, native), count in pairs.items():
        freq[native] += count
    return sorted(freq, key=lambda n: (-freq[n], n))


def _ranked_romans(primary_counts, all_counts, limit):
    """Primary romans first (by count then alpha), then the rest, capped.

    ``primary_counts`` is ``{roman: count}`` from the higher-priority source,
    ``all_counts`` the merged ``{roman: count}`` for the native. A roman in
    both appears once, with its primary count.
    """
    ordered = []
    seen = set()
    for roman, count in sorted(primary_counts.items(), key=lambda c: (-c[1], c[0])):
        ordered.append((roman, count))
        seen.add(roman)
    for roman, count in sorted(all_counts.items(), key=lambda c: (-c[1], c[0])):
        if roman in seen:
            continue
        ordered.append((roman, count))
        seen.add(roman)
    return ordered[:limit]


def render_body(pairs, max_pairs, max_bytes, priority=None):
    """Render the TSV rows under the pair and byte caps.

    Natives are emitted in two phases so the priority source cannot be
    squeezed out by the cap:

    1. every native that has at least one ``priority`` (Dakshina) pair, in
       descending total priority count, ties lexicographic by the native;
    2. the remaining natives, in descending merged count, ties lexicographic.

    Within a native, priority romans come first (by count, then alphabetically)
    and the remaining romanizations follow, up to ``MAX_ROMANS_PER_NATIVE``.
    Both orderings are fully specified, so the output is deterministic.
    ``priority=None`` reduces to the previous single-source behaviour.
    """
    priority = priority or {}
    by_native = defaultdict(dict)
    for (roman, native), count in pairs.items():
        by_native[native][roman] = count
    pri_by_native = defaultdict(dict)
    for (roman, native), count in priority.items():
        pri_by_native[native][roman] = count

    phase_a = sorted(
        (n for n in by_native if n in pri_by_native),
        key=lambda n: (-sum(pri_by_native[n].values()), n))
    phase_b = sorted(
        (n for n in by_native if n not in pri_by_native),
        key=lambda n: (-sum(by_native[n].values()), n))

    body = []
    pair_count = 0
    byte_count = 0
    native_count = 0
    for native in phase_a + phase_b:
        ranked = _ranked_romans(
            pri_by_native.get(native, {}), by_native[native],
            MAX_ROMANS_PER_NATIVE)
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


def render_header(lang, split, max_pairs, max_bytes, stats, pairs_selected,
                  natives_selected, zip_path=None, dakshina_path=None,
                  dakshina_stats=None):
    """Human-readable provenance header placed at the top of each TSV."""
    cfg = LANGUAGES[lang]
    lines = [
        "# Wisperlow romanization pairs — %s (%s)." % (cfg["display"], lang),
        "# Format: roman<TAB>native<TAB>positive_count (UTF-8, NFC-normalized).",
        "#   roman  lowercase a-z Latin string (typing-engine key alphabet)",
        "#   native %s word: letters + combining marks, ZWJ/ZWNJ removed" % cfg["script_name"],
        "#   count  positive attestation count of this exact roman/native pair",
        "#   within the referencing source. Counts from the two sources are",
        "#   never summed or compared (different collection methods); on an",
        "#   identical pair the Dakshina row wins. Roman alternatives of one",
        "#   native word are separate rows, ranked by count then alphabetically.",
        "# Source priority: all Dakshina train-lexicon pairs are emitted first",
        "#   (natives ordered by Dakshina count, descending; ties lexicographic);",
        "#   the Aksharantar mined pairs fill the remaining budget after them",
        "#   (natives ordered by mined count, descending; ties lexicographic).",
        "#",
    ]

    if zip_path:
        member = stats["member"]
        digest = sha256_member(zip_path, member)
        lines += [
            "# Source data [1] (CC0): Aksharantar train split (%s from %s)." % (
                member, os.path.basename(zip_path)),
            "#   Dataset page: %s (AI4Bharat IndicXlit project)." % cfg["dataset_page"],
            "#   Input member sha256 (raw JSONL bytes): %s." % digest,
        ]
    else:
        lines.append("# Source data [1] (CC0): none (no Aksharantar zip given).")

    if dakshina_path:
        dak_digest = sha256_file(dakshina_path)
        dak_rows = dakshina_stats["rows"] if dakshina_stats else 0
        dak_ok = dakshina_stats["accepted_rows"] if dakshina_stats else 0
        lines += [
            "# Source data [2] (%s): Dakshina v1.0 train word lexicon" % DAKSHINA_LICENSE,
            "#   (%s; %d rows, %d accepted pairs)."
            % (os.path.basename(dakshina_path), dak_rows, dak_ok),
            "#   Dataset page: %s" % DAKSHINA_DATASET_PAGE,
            "#   Archive: %s" % DAKSHINA_ARCHIVE,
            "#   Input file sha256 (raw TSV bytes): %s." % dak_digest,
            "#   Attribution: %s (%s)." % (DAKSHINA_PAPER, DAKSHINA_PAPER_URL),
        ]

    lines += [
        "#",
        "# Split used: %s only. The Aksharantar dev/test members and the" % split,
        "#   Dakshina dev/test lexicons and full-sentence romanized tree were",
        "#   never read, so no evaluation data contaminates this file.",
        "#",
    ]

    if dakshina_path:
        lines += [
            "# License: CC BY-SA 4.0 (this combined asset).",
            "#   This file is a modified/adapted work that combines rows from",
            "#   two datasets. Because it incorporates the Dakshina dataset",
            "#   (licensed %s), the combined asset is distributed under" % DAKSHINA_LICENSE,
            "#   %s: %s" % (DAKSHINA_LICENSE, DAKSHINA_LICENSE_URL),
            "#   The Aksharantar-derived rows [1] remain available from their",
            "#   source under CC0 1.0 (public domain dedication):",
            "#     Evidence: Madhani et al., `Aksharantar: Towards building open",
            "#     transliteration tools for the next billion users`",
            "#     (arXiv:2205.03018; Findings of EMNLP 2023) —",
            "#     \"all the mined data is available under the CC0 license\" (fn. 4:",
            "#     https://creativecommons.org/publicdomain/zero/1.0/).",
            "#     Rows used here carry source Wikidata, Samanantar, or IndicCorp.",
            "#   Modifications: word-level (roman, native) pairs were filtered,",
            "#   NFC-normalized, lowercased, ZWJ/ZWNJ-stripped, capped at",
            "#   %d romanizations per native, re-ranked, and merged across sources;" % MAX_ROMANS_PER_NATIVE,
            "#   no sentence or context text is retained. The surrounding",
            "#   Wisperlow application is MIT-licensed and is not relicensed by",
            "#   this data asset. See assets/licenses/indic-romanization.txt for",
            "#   the full attestation file.",
        ]
    else:
        lines += [
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
        ]

    lines += [
        "#",
        "# Caps: %d pairs max per language (used %d), %d bytes max per language."
        % (max_pairs, pairs_selected, max_bytes),
        "# Natives emitted: %d. Deterministic build via build_indic_pairs.py" % natives_selected,
        "# (identical inputs and options produce byte-identical output).",
        "#",
        "# Generation command (run from the repository root; point the zip and",
        "#   Dakshina flags at your local copies of the files named above):",
    ]
    cmd = ["#   python3 -I android/tools/indic/build_indic_pairs.py \\"]
    if zip_path:
        cmd.append("#       --%s-zip %s \\" % (lang, os.path.basename(zip_path)))
    if dakshina_path:
        cmd.append("#       --%s-dakshina %s \\" % (lang, os.path.basename(dakshina_path)))
    cmd.append("#       --out-dir android/app/src/main/assets/indic \\")
    cmd.append("#       --max-pairs %d --max-bytes %d --split %s"
               % (max_pairs, max_bytes, split))
    lines += cmd
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Build hi.tsv/kn.tsv romanization pair tables from Aksharantar "
                    "zips and optional Dakshina train lexicons.")
    parser.add_argument("--hi-zip", help="path to aksharantar hin.zip (Hindi)")
    parser.add_argument("--kn-zip", help="path to aksharantar kan.zip (Kannada)")
    parser.add_argument("--hi-dakshina",
                        help="path to hi.translit.sampled.train.tsv (Hindi, train only)")
    parser.add_argument("--kn-dakshina",
                        help="path to kn.translit.sampled.train.tsv (Kannada, train only)")
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

    # Reject dev/test (and sentence) Dakshina inputs before doing any work.
    targets = []
    for lang in ("hi", "kn"):
        zip_path = getattr(args, "%s_zip" % lang)
        dak_path = getattr(args, "%s_dakshina" % lang)
        if dak_path:
            dak_path = validate_dakshina_path(dak_path)
        if zip_path or dak_path:
            targets.append((lang, zip_path, dak_path))
    if not targets:
        parser.error("pass at least one of --hi-zip/--kn-zip/--hi-dakshina/--kn-dakshina")

    if not args.dry_run:
        os.makedirs(args.out_dir, exist_ok=True)

    for lang, zip_path, dak_path in targets:
        if zip_path:
            mined_pairs, stats = build_pairs(zip_path, lang, args.split)
        else:
            mined_pairs = {}
            stats = {"member": "(none)", "rows": 0, "mined_rows": 0,
                     "reject_reasons": defaultdict(int)}

        if dak_path:
            dak_pairs, dak_stats = build_dakshina_pairs(dak_path, lang)
        else:
            dak_pairs, dak_stats = {}, None

        merged = merge_pairs(dak_pairs, mined_pairs)
        body, out_pairs, out_bytes, natives_selected = render_body(
            merged, args.max_pairs, args.max_bytes, priority=dak_pairs)
        header = render_header(
            lang=lang,
            split=args.split,
            max_pairs=args.max_pairs,
            max_bytes=args.max_bytes,
            stats=stats,
            pairs_selected=out_pairs,
            natives_selected=natives_selected,
            zip_path=zip_path,
            dakshina_path=dak_path,
            dakshina_stats=dak_stats,
        )
        text = header + body
        text_bytes = len(text.encode("utf-8"))

        rejects = dict(stats["reject_reasons"])
        dak_rejects = dict(dak_stats["reject_reasons"]) if dak_stats else {}
        print(
            "%s: mined-rows=%d mined-pairs=%d | dakshina-rows=%d dakshina-pairs=%d "
            "rejects=%s | merged-pairs=%d -> pairs=%d bytes=%d"
            % (lang, stats["rows"], len(mined_pairs),
               dak_stats["rows"] if dak_stats else 0, len(dak_pairs),
               dak_rejects or "none", len(merged), out_pairs, text_bytes),
            file=sys.stderr,
        )
        print("  mined rejects: %s" % (", ".join(
            "%s=%d" % (k, v) for k, v in sorted(rejects.items())) or "none"),
            file=sys.stderr)
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
