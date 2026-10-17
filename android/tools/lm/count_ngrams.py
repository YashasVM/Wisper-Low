#!/usr/bin/env python3
"""Count word unigrams, bigrams and trigrams from large text corpora.

Reads UTF-8 text (plain, gzip, bzip2 or xz), one sentence or paragraph per line,
and writes ``unigrams.tsv``, ``bigrams.tsv``, ``trigrams.tsv`` and ``stats.json``
for the Kotlin language-model estimator.

The corpus is counted in bounded in-memory dicts; when a dict reaches
``--chunk-entries`` the dict is sorted and spilled to a file in the temporary
directory.  Afterwards all chunk files are k-way merged with ``heapq.merge``
summing counts, so memory stays flat no matter how large the input is.
"""

import argparse
import bz2
import gzip
import heapq
import itertools
import json
import lzma
import os
import re
import sys
import tempfile
import time
import unicodedata
from functools import partial

SENTENCE_START = "<s>"
DEFAULT_CHUNK_ENTRIES = 200_000
DEFAULT_MIN1 = 2
DEFAULT_MIN2 = 3
DEFAULT_MIN3 = 3
MAX_FAN_IN = 128

# Curly quotes and modifier apostrophes that all mean a plain apostrophe.
_CURLY_APOSTROPHES = "\u2018\u2019\u201a\u201b\u2032\u2035\u02bc\u02bb"

# ---------------------------------------------------------------------------
# Character classes
# ---------------------------------------------------------------------------

# For every ASCII character decide what it is: "w" a word character (letter or
# combining mark), "a" an apostrophe, "x" a sentence delimiter, "s" a plain
# separator (whitespace or punctuation) or "b" a context breaker (number or
# symbol).  Anything non-ASCII falls back to the Unicode category.
_ASCII_CLASS = []
for _i in range(128):
    _c = chr(_i)
    _cat = unicodedata.category(_c)
    if _cat[0] in ("L", "M"):
        _cls = "w"
    elif _c == "'":
        _cls = "a"
    elif _c in ".!?":
        _cls = "x"
    elif _c in "@#":
        # may start an @handle or #tag, handled by the breaker patterns
        _cls = "b"
    elif _cat[0] in ("Z", "P", "C") or _c in "\t\n\r\f\v":
        _cls = "s"
    else:
        _cls = "b"
    _ASCII_CLASS.append(_cls)
_ASCII_CLASS = tuple(_ASCII_CLASS)


def char_class(ch):
    """Return the class letter of a single character."""
    if ch in "\u0964\u0965":  # Devanagari/Kannada danda and double danda
        return "x"
    code = ord(ch)
    if code < 128:
        return _ASCII_CLASS[code]
    cat = unicodedata.category(ch)
    if cat[0] in ("L", "M"):
        return "w"
    if cat[0] in ("Z", "P", "C"):
        return "s"
    return "b"


# Non-word runs that must break n-gram context.  Tried in order at a position.
_BREAKER_RES = (
    # scheme://... (also catches bare "http://host")
    re.compile(r"[A-Za-z][A-Za-z0-9+.\-]*://[^\s]+"),
    re.compile(r"www\.[^\s]+", re.IGNORECASE),
    # name@example.tld
    re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"),
    re.compile(r"@[A-Za-z0-9_]+"),  # @handle
    re.compile(r"#[A-Za-z0-9_]+"),  # #tag
    re.compile(r"\d[\d.,]*"),  # 3, 3.14, 1,000
    # dotted token whose last segment is alphabetic: example.com, sub.co.uk
    re.compile(r"[A-Za-z0-9][A-Za-z0-9\-]*(?:\.[A-Za-z0-9][A-Za-z0-9\-]*)*\.[A-Za-z]{2,}"),
)


def match_breaker_at(line, i):
    """Return the end offset of a breaker match at ``i``, or None."""
    for rx in _BREAKER_RES:
        m = rx.match(line, i)
        if m is not None:
            return m.end()
    return None


def normalize_text(text):
    """NFC-normalise and fold curly apostrophes to a plain ``'``."""
    text = unicodedata.normalize("NFC", text)
    for curly in _CURLY_APOSTROPHES:
        text = text.replace(curly, "'")
    return text


def scan_line(line):
    """Yield ``(kind, value)`` atoms: ``("w", surface)``, ``("b", text)`` for a
    context breaker and ``("x", "")`` for a sentence delimiter."""
    n = len(line)
    classes = [char_class(c) for c in line]
    i = 0
    while i < n:
        cls = classes[i]
        if cls == "w":
            end = match_breaker_at(line, i)
            if end is not None:
                yield ("b", line[i:end])
                i = end
                continue
            j = i
            while j < n:
                cj = classes[j]
                if cj == "w":
                    j += 1
                elif (
                    cj == "a"
                    and j > i
                    and j + 1 < n
                    and classes[j - 1] == "w"
                    and classes[j + 1] == "w"
                ):
                    # inner apostrophe: don't, rock'n'roll
                    j += 1
                else:
                    break
            yield ("w", line[i:j])
            i = j
        elif cls == "x":
            yield ("x", "")
            i += 1
        elif cls == "s" or cls == "a":
            i += 1
        else:  # "b"
            end = match_breaker_at(line, i)
            if end is not None:
                yield ("b", line[i:end])
                i = end
                continue
            j = i
            while j < n and classes[j] == "b":
                j += 1
            yield ("b", line[i:j])
            i = j


# ---------------------------------------------------------------------------
# Counting
# ---------------------------------------------------------------------------


class Counter:
    """Accumulates n-gram counts in bounded dicts, spilling when full."""

    def __init__(self, chunk_entries, tmpdir):
        self.chunk_entries = max(1, int(chunk_entries))
        self.tmpdir = tmpdir
        self.uni = {}
        self.bi = {}
        self.tri = {}
        self.surf = {}
        self.uni_chunks = []
        self.bi_chunks = []
        self.tri_chunks = []
        self.surf_chunks = []
        self.lines = 0
        self.sentences = 0
        self.tokens = 0

    def _size(self):
        return (
            len(self.uni) + len(self.bi) + len(self.tri) + len(self.surf)
        )

    def emit(self, tokens):
        """Count unigrams/bigrams/trigrams for one context segment."""
        keys = [t.lower() for t in tokens]
        has_start = tokens[0] == SENTENCE_START
        n = len(tokens)
        uni = self.uni
        surf = self.surf
        for i in range(n):
            key = keys[i]
            uni[key] = uni.get(key, 0) + 1
            surface = tokens[i]
            if surface == SENTENCE_START:
                continue
            if has_start and i == 1:
                # Sentence-initial surface never shapes the dominant casing.
                continue
            sk = (key, surface)
            surf[sk] = surf.get(sk, 0) + 1
        bi = self.bi
        for i in range(n - 1):
            k = (keys[i], keys[i + 1])
            bi[k] = bi.get(k, 0) + 1
        tri = self.tri
        for i in range(n - 2):
            k = (keys[i], keys[i + 1], keys[i + 2])
            tri[k] = tri.get(k, 0) + 1
        if self._size() >= self.chunk_entries:
            self.spill()

    def _write_chunk(self, d, tag):
        path = os.path.join(
            self.tmpdir, "%s_%06d.tsv" % (tag, len(getattr(self, tag + "_chunks")))
        )
        with open(path, "w", encoding="utf-8") as f:
            for key in sorted(d):
                f.write("\t".join(key) if isinstance(key, tuple) else key)
                f.write("\t")
                f.write(str(d[key]))
                f.write("\n")
        return path

    def spill(self):
        """Write every non-empty dict to a sorted chunk file and clear it."""
        if self.uni:
            self.uni_chunks.append(self._write_chunk(self.uni, "uni"))
            self.uni = {}
        if self.bi:
            self.bi_chunks.append(self._write_chunk(self.bi, "bi"))
            self.bi = {}
        if self.tri:
            self.tri_chunks.append(self._write_chunk(self.tri, "tri"))
            self.tri = {}
        if self.surf:
            self.surf_chunks.append(self._write_chunk(self.surf, "surf"))
            self.surf = {}


def process_line(line, counter):
    """Feed one input line into the counter."""
    current = []
    pending_start = True
    for kind, value in scan_line(normalize_text(line)):
        if kind == "w":
            if pending_start:
                current.append(SENTENCE_START)
                counter.sentences += 1
                pending_start = False
            current.append(value)
            counter.tokens += 1
        elif kind == "x":
            if current:
                counter.emit(current)
                current = []
            pending_start = True
        else:  # breaker: cut the segment, keep the sentence start pending
            if current:
                counter.emit(current)
                current = []
    if current:
        counter.emit(current)


# ---------------------------------------------------------------------------
# External merge
# ---------------------------------------------------------------------------


def _iter_parsed(path, parse):
    with open(path, encoding="utf-8") as f:
        for line in f:
            yield parse(line.rstrip("\n"))


def _merge_batch(paths, out_path, parse, keyfn, write_group):
    iters = [_iter_parsed(p, parse) for p in paths]
    with open(out_path, "w", encoding="utf-8") as out:
        merged = heapq.merge(*iters, key=keyfn)
        for key, group in itertools.groupby(merged, key=keyfn):
            write_group(out, key, group)


def merge_sorted_files(
    paths, out_path, parse, keyfn, write_group, tmpdir, tag, fan_in=MAX_FAN_IN
):
    """Merge already sorted files into ``out_path`` with bounded open files."""
    paths = list(paths)
    round_no = 0
    while len(paths) > fan_in:
        nxt = []
        for i in range(0, len(paths), fan_in):
            p = os.path.join(tmpdir, "%s_r%03d_%06d.tsv" % (tag, round_no, i))
            _merge_batch(paths[i : i + fan_in], p, parse, keyfn, write_group)
            nxt.append(p)
        for p in paths:
            try:
                os.remove(p)
            except OSError:
                pass
        paths = nxt
        round_no += 1
    _merge_batch(paths, out_path, parse, keyfn, write_group)


def _parse_count(line, arity):
    parts = line.split("\t")
    return tuple(parts[:arity]), int(parts[arity])


def _key_of(item):
    return item[0]


def _write_count(out, key, group):
    total = 0
    for _, count in group:
        total += count
    out.write("\t".join(key))
    out.write("\t")
    out.write(str(total))
    out.write("\n")


def external_sort_by_count(in_path, out_path, tmpdir, chunk_entries):
    """Sort ``word<TAB>count`` lines by count descending, memory bounded."""
    chunks = []
    buf = []

    def flush():
        if not buf:
            return
        buf.sort(key=lambda kc: (-kc[0], kc[1]))
        p = os.path.join(tmpdir, "unisort_%06d.tsv" % len(chunks))
        with open(p, "w", encoding="utf-8") as f:
            for count, key in buf:
                f.write("%d\t%s\n" % (count, key))
        chunks.append(p)
        buf.clear()

    with open(in_path, encoding="utf-8") as f:
        for line in f:
            key, count_s = line.rstrip("\n").split("\t")
            buf.append((int(count_s), key))
            if len(buf) >= chunk_entries:
                flush()
    flush()

    def parse_desc(line):
        count_s, key = line.split("\t", 1)
        count = int(count_s)
        return (-count, key), (count, key)

    def write_desc(out, key, group):
        for _, (count, word) in group:
            out.write("%s\t%d\n" % (word, count))

    merge_sorted_files(chunks, out_path, parse_desc, _key_of, write_desc, tmpdir, "unisort")


def total_counts(path, arity):
    total = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            total += int(line.rstrip("\n").split("\t")[arity])
    return total


def build_surface_map(path, wanted):
    """Map each wanted key to its dominant non-sentence-initial surface."""
    surfaces = {}
    with open(path, encoding="utf-8") as f:
        current_key = None
        best_surface = None
        best_count = -1
        for line in f:
            key, surface, count_s = line.rstrip("\n").split("\t")
            count = int(count_s)
            if key != current_key:
                if current_key is not None and current_key in wanted:
                    surfaces[current_key] = best_surface
                current_key = key
                best_surface = surface
                best_count = count
            elif count > best_count or (count == best_count and surface < best_surface):
                best_surface = surface
                best_count = count
        if current_key is not None and current_key in wanted:
            surfaces[current_key] = best_surface
    return surfaces


def surface_of(key, surfaces):
    if key == SENTENCE_START:
        return SENTENCE_START
    return surfaces.get(key, key)


def write_ngrams(in_path, out_path, arity, min_count, vocab, surfaces):
    written = 0
    with open(in_path, encoding="utf-8") as f, open(
        out_path, "w", encoding="utf-8"
    ) as out:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            keys = parts[:arity]
            count = int(parts[arity])
            if count < min_count:
                continue
            if any(k not in vocab for k in keys):
                continue
            out.write("\t".join(surface_of(k, surfaces) for k in keys))
            out.write("\t")
            out.write(str(count))
            out.write("\n")
            written += 1
    return written


# ---------------------------------------------------------------------------
# Input and CLI
# ---------------------------------------------------------------------------


def open_text(path):
    if path == "-":
        return sys.stdin
    lower = path.lower()
    if lower.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8-sig", errors="replace")
    if lower.endswith(".bz2"):
        return bz2.open(path, "rt", encoding="utf-8-sig", errors="replace")
    if lower.endswith(".xz"):
        return lzma.open(path, "rt", encoding="utf-8-sig", errors="replace")
    return open(path, "rt", encoding="utf-8-sig", errors="replace")


def log(message):
    sys.stderr.write("[count_ngrams] %s\n" % message)
    sys.stderr.flush()


def parse_args(argv=None):
    p = argparse.ArgumentParser(
        description="Count word unigrams, bigrams and trigrams for the "
        "Wisperlow language model."
    )
    p.add_argument("--out", required=True, help="output directory")
    p.add_argument(
        "inputs", nargs="+", help="text files (.txt, .gz, .bz2, .xz); '-' for stdin"
    )
    p.add_argument("--max-lines", type=int, default=None, help="stop after N lines")
    p.add_argument(
        "--chunk-entries",
        type=int,
        default=DEFAULT_CHUNK_ENTRIES,
        help="spill the counting dicts after this many entries",
    )
    p.add_argument(
        "--vocab", type=int, default=None, help="keep only the N most frequent words"
    )
    p.add_argument("--min1", type=int, default=DEFAULT_MIN1)
    p.add_argument("--min2", type=int, default=DEFAULT_MIN2)
    p.add_argument("--min3", type=int, default=DEFAULT_MIN3)
    p.add_argument("--temp-dir", default=None, help="scratch directory for chunk files")
    p.add_argument("--keep-temp", action="store_true", help="do not delete chunk files")
    p.add_argument(
        "--progress-every",
        type=int,
        default=100000,
        help="log progress every N lines",
    )
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    tmpdir = args.temp_dir or tempfile.mkdtemp(prefix="count_ngrams_")
    if not os.path.isdir(tmpdir):
        os.makedirs(tmpdir, exist_ok=True)
    log("reading %d input file(s), scratch %s" % (len(args.inputs), tmpdir))

    counter = Counter(args.chunk_entries, tmpdir)
    start = time.time()
    done = 0
    for path in args.inputs:
        with open_text(path) as f:
            for line in f:
                if args.max_lines is not None and done >= args.max_lines:
                    break
                process_line(line, counter)
                done += 1
                counter.lines += 1
                if args.progress_every and done % args.progress_every == 0:
                    log(
                        "line %d: %d tokens, %d sentences, %d chunks"
                        % (
                            done,
                            counter.tokens,
                            counter.sentences,
                            len(counter.uni_chunks),
                        )
                    )
        if args.max_lines is not None and done >= args.max_lines:
            break
    counter.spill()
    log(
        "counted %d lines, %d sentences, %d tokens in %.1fs"
        % (counter.lines, counter.sentences, counter.tokens, time.time() - start)
    )

    uni_merged = os.path.join(tmpdir, "uni_merged.tsv")
    bi_merged = os.path.join(tmpdir, "bi_merged.tsv")
    tri_merged = os.path.join(tmpdir, "tri_merged.tsv")
    surf_merged = os.path.join(tmpdir, "surf_merged.tsv")
    merge_sorted_files(
        counter.uni_chunks,
        uni_merged,
        partial(_parse_count, arity=1),
        _key_of,
        _write_count,
        tmpdir,
        "uni",
    )
    merge_sorted_files(
        counter.bi_chunks,
        bi_merged,
        partial(_parse_count, arity=2),
        _key_of,
        _write_count,
        tmpdir,
        "bi",
    )
    merge_sorted_files(
        counter.tri_chunks,
        tri_merged,
        partial(_parse_count, arity=3),
        _key_of,
        _write_count,
        tmpdir,
        "tri",
    )
    merge_sorted_files(
        counter.surf_chunks,
        surf_merged,
        partial(_parse_count, arity=2),
        _key_of,
        _write_count,
        tmpdir,
        "surf",
    )

    uni_sorted = os.path.join(tmpdir, "uni_sorted.tsv")
    external_sort_by_count(uni_merged, uni_sorted, tmpdir, args.chunk_entries)

    # Vocabulary: top --vocab words (excluding <s>, which is always kept).
    vocab = set()
    with open(uni_sorted, encoding="utf-8") as f:
        rank = 0
        for line in f:
            key = line.rstrip("\n").split("\t", 1)[0]
            if key == SENTENCE_START:
                vocab.add(key)
                continue
            if args.vocab is not None and rank >= args.vocab:
                continue
            rank += 1
            vocab.add(key)
    surfaces = build_surface_map(surf_merged, vocab)

    unigrams_path = os.path.join(args.out, "unigrams.tsv")
    uni_rows = 0
    with open(uni_sorted, encoding="utf-8") as f, open(
        unigrams_path, "w", encoding="utf-8"
    ) as out:
        for line in f:
            key, count_s = line.rstrip("\n").split("\t")
            if key != SENTENCE_START and int(count_s) < args.min1:
                continue
            if key not in vocab:
                continue
            out.write(surface_of(key, surfaces))
            out.write("\t")
            out.write(count_s)
            out.write("\n")
            uni_rows += 1

    bi_rows = write_ngrams(
        bi_merged,
        os.path.join(args.out, "bigrams.tsv"),
        2,
        args.min2,
        vocab,
        surfaces,
    )
    tri_rows = write_ngrams(
        tri_merged,
        os.path.join(args.out, "trigrams.tsv"),
        3,
        args.min3,
        vocab,
        surfaces,
    )

    elapsed = time.time() - start
    stats = {
        "lines": counter.lines,
        "sentences": counter.sentences,
        "tokens": counter.tokens,
        "vocabulary_size": uni_rows,
        "unigrams": uni_rows,
        "bigrams": bi_rows,
        "trigrams": tri_rows,
        "unigram_occurrences": total_counts(uni_merged, 1),
        "bigram_occurrences": total_counts(bi_merged, 2),
        "trigram_occurrences": total_counts(tri_merged, 3),
        "elapsed_seconds": round(elapsed, 3),
    }
    with open(os.path.join(args.out, "stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2, sort_keys=True)
        f.write("\n")

    if not args.keep_temp and args.temp_dir is None:
        for name in os.listdir(tmpdir):
            try:
                os.remove(os.path.join(tmpdir, name))
            except OSError:
                pass
        try:
            os.rmdir(tmpdir)
        except OSError:
            pass
    log("wrote %d unigrams, %d bigrams, %d trigrams in %.1fs" % (uni_rows, bi_rows, tri_rows, elapsed))
    return stats


if __name__ == "__main__":
    main()
