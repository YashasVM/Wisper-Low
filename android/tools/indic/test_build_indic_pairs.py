#!/usr/bin/env python3
"""Tests for build_indic_pairs.py.

All fixtures are tiny zips created in a temp directory — no real dataset is
needed. Run with::

    python3 -m unittest test_build_indic_pairs -v
"""

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import unicodedata
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE_PATH = os.path.join(HERE, "build_indic_pairs.py")
_spec = importlib.util.spec_from_file_location("build_indic_pairs", MODULE_PATH)
bp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bp)

HI_BLOCK = bp.LANGUAGES["hi"]["block"]
KN_BLOCK = bp.LANGUAGES["kn"]["block"]


def make_zip(path, members):
    """Write a zip in the Aksharantar layout: one JSONL member per split."""
    with zipfile.ZipFile(path, "w") as z:
        for name, rows in members.items():
            data = "\n".join(json.dumps(r) if isinstance(r, dict) else r
                             for r in rows)
            z.writestr(name, data)


def row(native, roman, source="IndicCorp"):
    return {"unique_identifier": "x",
            "native word": native,
            "english word": roman,
            "source": source,
            "score": None}


TRAIN = "hin_train.json"
VALID = "hin_valid.json"
TEST = "hin_test.json"


class ClassifyRejectTests(unittest.TestCase):
    """Row-level filters: format, junk, control text, script."""

    def test_accepts_clean_pair(self):
        self.assertIsNone(bp.classify_reject("namaste", "नमस्ते", HI_BLOCK))

    def test_empty_fields_rejected(self):
        self.assertEqual(bp.classify_reject("", "नम", HI_BLOCK), "empty_field")
        self.assertEqual(bp.classify_reject("nam", "", HI_BLOCK), "empty_field")

    def test_too_long_rejected(self):
        self.assertEqual(
            bp.classify_reject("a" * (bp.MAX_ROMAN_CHARS + 1), "नम", HI_BLOCK),
            "too_long")
        self.assertEqual(
            bp.classify_reject("nam", "नम" * (bp.MAX_NATIVE_CHARS // 2 + 1) + " ",
                               HI_BLOCK), "too_long")

    def test_url_and_email_junk_rejected(self):
        for junk in ("a@b", "abc@example.com", "http://x", "www.example",
                     "example.com", "foo.in", "path://x", "a.b.info"):
            self.assertEqual(bp.classify_reject(junk, "नम", HI_BLOCK),
                             "url_email_junk", junk)

    def test_control_text_rejected(self):
        # Cc control, Cf format char on either side
        self.assertEqual(bp.classify_reject("na\x00m", "नम", HI_BLOCK),
                         "control_char")
        self.assertEqual(bp.classify_reject("nam", "नम\x07", HI_BLOCK),
                         "control_char")
        # ZWJ/ZWNJ have been stripped from the native already, but a ZWJ in
        # the roman is a format char and is rejected as control text.
        self.assertEqual(bp.classify_reject("ka\u200cml", "कमल", HI_BLOCK),
                         "control_char")
        # Teletex-style text direction mark inside the roman
        self.assertEqual(bp.classify_reject("ab\u200e", "नम", HI_BLOCK),
                         "control_char")

    def test_roman_keys_lowercase_latin_only(self):
        self.assertEqual(bp.classify_reject("Namaste", "नमस्ते", HI_BLOCK),
                         "non_latin_roman")
        self.assertEqual(bp.classify_reject("sanée", "नमस्ते", HI_BLOCK),
                         "non_latin_roman")
        self.assertEqual(bp.classify_reject("123", "नम", HI_BLOCK),
                         "non_latin_roman")

    def test_native_categories_rejected(self):
        self.assertEqual(bp.classify_reject("na", "न.म", HI_BLOCK),
                         "bad_native_category")   # punctuation
        self.assertEqual(bp.classify_reject("na", "न1म", HI_BLOCK),
                         "bad_native_category")   # digits
        self.assertEqual(bp.classify_reject("na", "न।", HI_BLOCK),
                         "bad_native_category")   # danda

    def test_mixed_script_native_rejected(self):
        self.assertEqual(bp.classify_reject("na", "नma", HI_BLOCK),
                         "mixed_script")          # Latin inside Devanagari
        self.assertEqual(bp.classify_reject("na", "हelo", HI_BLOCK),
                         "mixed_script")
        # Kannada letters are valid under the Kannada block, junk under hi's
        self.assertEqual(bp.classify_reject("na", "ನಮ", HI_BLOCK),
                         "mixed_script")
        self.assertIsNone(bp.classify_reject("na", "ನಮ", KN_BLOCK))

    def test_valid_native_script_utility(self):
        self.assertTrue(bp.valid_native_script("नमस्ते", HI_BLOCK))
        self.assertFalse(bp.valid_native_script("नa", HI_BLOCK))


class BuildPairsTests(unittest.TestCase):
    """Aggregation, NFC, ZWJ/ZWNJ handling and split discipline."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bip-test-")
        self.zip = os.path.join(self.dir, "aksharantar-hin.zip")
        make_zip(self.zip, {
            TRAIN: [
                row("नमस्ते", "namaste"),
                row("नमस्ते", "namaste"),   # duplicate exact pair
                row("नमस्ते", "namaste"),   # and again -> count 3
                row("नमस्ते", "namastea"),  # roman alternative preserved
                row(unicodedata.normalize("NFD", "बऩाल"), "banaal"),  # NFD native
                row("कमल", "ka\u200cml"),   # ZWJ in roman -> control text
                row("क\u200dम", "ka"),      # ZWJ in native -> stripped
                row("काला", "k@la"),        # email-ish junk
                row("काला", "kāla"),        # non-ASCII roman
                row("कल", "http://x"),      # URL
                row("कल", "123"),           # digits-only roman
                row("कल", "kal"),           # plain
                row("क+ल", "kal"),          # punctuation/native junk
                row("हelo", "helo"),        # mixed-script native
                "not json {",               # malformed JSONL row
                row("कल", "kalx", source="Dakshina"),   # third-party rows
                row("कल", "kaly", source="AK-Freq"),    # manual/benchmark rows
                row("कल", "kalz", source="Existing"),   # prior-corpora rows
            ],
            VALID: [row("इवाल्म", "evalvalid")],  # dev split: must not be read
            TEST: [row("इवाल्स", "evaltest")],    # test split: must not be read
        })

    def tearDown(self):
        shutil.rmtree(self.dir)

    def pairs(self):
        return bp.build_pairs(self.zip, "hi", "train")

    def test_exact_pairs_aggregated_alternatives_kept(self):
        pairs, _ = self.pairs()
        self.assertEqual(pairs[("namaste", "नमस्ते")], 3)
        self.assertEqual(pairs[("namastea", "नमस्ते")], 1)
        self.assertEqual(len([k for k in pairs if k[1] == "नमस्ते"]), 2)

    def test_nfc_normalization(self):
        pairs, _ = self.pairs()
        # "बऩाल" has a canonical decomposition (न + nukta ≡ ऩ), so NFD input
        # must come out NFC-composed.
        composed = unicodedata.normalize("NFC", "बऩाल")
        self.assertNotEqual(
            unicodedata.normalize("NFD", "क़ानून"), composed)  # fixture sanity
        self.assertIn(("banaal", composed), pairs)
        # every emitted native is NFC
        for native in {k[1] for k in pairs}:
            self.assertEqual(unicodedata.normalize("NFC", native), native)

    def test_zwj_zwnj_stripped_from_native(self):
        pairs, _ = self.pairs()
        self.assertIn(("ka", "कम"), pairs)
        self.assertNotIn(("ka", "क\u200dम"), pairs)

    def test_rejected_rows_reasons(self):
        _, stats = self.pairs()
        reasons = stats["reject_reasons"]
        self.assertEqual(reasons["not_mined_source"], 3)
        self.assertEqual(reasons["url_email_junk"], 2)   # k@la, http://x
        self.assertEqual(reasons["control_char"], 1)     # ka+ZWJ
        self.assertEqual(reasons["non_latin_roman"], 2)  # kāla, 123
        self.assertEqual(reasons["bad_native_category"], 1)  # क+ल
        self.assertEqual(reasons["mixed_script"], 1)     # हelo
        self.assertEqual(reasons["bad_json"], 1)

    def test_train_split_only(self):
        pairs, stats = self.pairs()
        self.assertEqual(stats["member"], TRAIN)
        # rows that only exist in the dev/test members never appear
        self.assertFalse(any(k[0] == "evalvalid" for k in pairs))
        self.assertFalse(any(k[0] == "evaltest" for k in pairs))
        self.assertFalse(any(k[1] in ("इवाल्म", "इवाल्स") for k in pairs))
        # exactly the five acceptances from the train rows happened; 14 of
        # the 17 dict rows were mined-source (the 3 others are not)
        self.assertEqual(len(pairs), 5)
        self.assertEqual(stats["mined_rows"], 14)

    def test_missing_member_refuses_to_fall_back(self):
        # A language config whose train member is absent errors out even
        # though dev/test members exist — never silently read another split.
        naked = os.path.join(self.dir, "naked.zip")
        make_zip(naked, {VALID: [row("कल", "kal")], TEST: [row("कल", "kal")]})
        self.assertRaises(SystemExit, bp.build_pairs, naked, "hi", "train")


class RenderAndCapTests(unittest.TestCase):
    def body(self, pairs, max_pairs=100000, max_bytes=6000000):
        return bp.render_body(pairs, max_pairs, max_bytes)

    def test_body_line_format(self):
        body, n_pairs, n_bytes, n_natives = self.body(
            {("namaste", "नमस्ते"): 3, ("namastea", "नमस्ते"): 1})
        lines = body.splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(all(len(l.split("\t")) == 3 for l in lines))
        roman, native, count = lines[0].split("\t")
        self.assertEqual((roman, native, int(count)),
                         ("namaste", "नमस्ते", 3))
        self.assertEqual((n_pairs, n_natives), (2, 1))

    def test_bytes_accounting(self):
        body, n_pairs, n_bytes, _ = self.body(
            {("namaste", "नमस्ते"): 3})
        self.assertEqual(len(body.encode("utf-8")), n_bytes)

    def test_natives_ranked_by_attestations_then_lexical(self):
        # "नव" has 3 attestations, "नम" only 1, so "नव" comes first even
        # though it is lexicographically larger.
        body, *_ = self.body({("naj", "नम"): 1, ("nov", "नव"): 3})
        self.assertLess(body.index("नव\t"), body.index("नम\t"))

        # equal attestations -> lexicographic tie-break
        body, *_ = self.body({("naj", "नम"): 2, ("nov", "नव"): 2})
        self.assertLess(body.index("नम\t"), body.index("नव\t"))

    def test_roman_alternatives_ranked_by_count_then_alpha(self):
        body, *_ = self.body(
            {("zz", "नम"): 1, ("aa", "नम"): 2, ("mm", "नम"): 1})
        lines = body.splitlines()
        self.assertEqual([l.split("\t")[0] for l in lines], ["aa", "mm", "zz"])
        self.assertEqual([int(l.split("\t")[2]) for l in lines], [2, 1, 1])

    def test_at_most_8_romans_per_native(self):
        pairs = {(chr(97 + i) + "a", "नम"): 1 for i in range(10)}
        body, n_pairs, _, _ = self.body(pairs)
        self.assertEqual(n_pairs, bp.MAX_ROMANS_PER_NATIVE)

    def test_pair_cap(self):
        pairs = {(chr(97 + i), "अ" + chr(0x0901 + i)): 1 for i in range(20)}
        body, n_pairs, _, _ = self.body(pairs, max_pairs=5)
        self.assertEqual(n_pairs, 5)
        self.assertEqual(len(body.splitlines()), 5)

    def test_byte_cap(self):
        pairs = {(chr(97 + i) + "aaaa", "अ" * (i + 1)): 1 for i in range(20)}
        body, n_pairs, n_bytes, _ = self.body(pairs, max_bytes=120)
        self.assertLessEqual(n_bytes, 120)
        self.assertGreater(n_pairs, 0)

    def test_deterministic(self):
        pairs = {(a, b): 1 for a in ("aa", "ab", "ac")
                 for b in ("अन", "मन", "कल")}
        out1 = self.body(pairs)
        out2 = self.body(pairs)
        self.assertEqual(out1, out2)


class HeaderTests(unittest.TestCase):
    def test_header_is_comment_only_and_cites_license_url_and_split(self):
        d = tempfile.mkdtemp(prefix="bip-head-")
        try:
            zip_path = os.path.join(d, "aksharantar-hin.zip")
            make_zip(zip_path, {TRAIN: [row("नमस्ते", "namaste")]})
            pairs, stats = bp.build_pairs(zip_path, "hi", "train")
            body, p, b, n = bp.render_body(pairs, 100, 10000)
            header = bp.render_header(
                lang="hi", zip_path=zip_path, split="train", max_pairs=100,
                max_bytes=10000, stats=stats, pairs_selected=p,
                natives_selected=n)
            self.assertTrue(all(l.startswith("#")
                                for l in header.splitlines()))
            for needle in (
                    "hin_train.json",
                    "train",
                    "CC0 1.0",
                    "https://creativecommons.org/publicdomain/zero/1.0/",
                    "python3 -I android/tools/indic/build_indic_pairs.py",
                    "max-pairs 100",
            ):
                self.assertIn(needle, header)
        finally:
            shutil.rmtree(d)


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bip-e2e-")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_cli_writes_deterministic_tsv(self):
        zip_path = os.path.join(self.dir, "aksharantar-hin.zip")
        make_zip(zip_path, {TRAIN: [
            row("नमस्ते", "namaste"),
            row("कल", "kal"),
            row("कल", "kal"),
        ]})
        base = [sys.executable, "-I", MODULE_PATH, "--hi-zip", zip_path,
                "--max-pairs", "100", "--split", "train", "--out-dir"]
        outs = []
        for i, outdir in enumerate(("o1", "o2")):
            out = os.path.join(self.dir, outdir)
            subprocess.run(base + [out], check=True, capture_output=True)
            outs.append(open(os.path.join(out, "hi.tsv"), "rb").read())
        self.assertEqual(outs[0], outs[1])
        self.assertIn("namaste\tनमस्ते\t1".encode("utf-8"), outs[0])
        self.assertIn("kal\tकल\t2".encode("utf-8"), outs[0])
        # header lines are comments; data lines are exactly 3 TSV columns
        data = [l for l in outs[0].split(b"\n") if l and not l.startswith(b"#")]
        self.assertTrue(all(len(l.split(b"\t")) == 3 for l in data))

    def test_cli_rejects_non_train_split(self):
        zip_path = os.path.join(self.dir, "aksharantar-hin.zip")
        make_zip(zip_path, {TEST: [row("कल", "kal")]})
        out = os.path.join(self.dir, "o")
        result = subprocess.run(
            [sys.executable, "-I", MODULE_PATH, "--hi-zip", zip_path,
             "--max-pairs", "100", "--split", "test", "--out-dir", out],
            capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(os.path.exists(os.path.join(out, "hi.tsv")))

    def test_kannada_config_uses_kan_train_member(self):
        zip_path = os.path.join(self.dir, "aksharantar-kan.zip")
        make_zip(zip_path, {"kan_train.json": [row("ನಮ", "na", source="IndicCorp")],
                            "kan_test.json": [row("ಇಲ", "ila")]})
        pairs, stats = bp.build_pairs(zip_path, "kn", "train")
        self.assertEqual(stats["member"], "kan_train.json")
        self.assertEqual(pairs, {("na", "ನಮ"): 1})


def dak_row(native, roman, count=1):
    # count is str-formatted so malformed fixtures ("x", "-2") can be built too.
    return "%s\t%s\t%s" % (native, roman, count)


def make_dakshina(path, rows):
    """Write a Dakshina word-lexicon TSV: native<TAB>roman<TAB>attestations."""
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + ("\n" if rows else ""))


class DakshinaPathTests(unittest.TestCase):
    """TRAIN-only discipline for the Dakshina file, at CLI and library level."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bip-dak-path-")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_accepts_train_lexicon_names(self):
        for name in ("hi.translit.sampled.train.tsv", "kn_train.tsv",
                     "hi.train.tsv"):
            path = os.path.join(self.dir, name)
            make_dakshina(path, [dak_row("नम", "nam")])
            self.assertEqual(bp.validate_dakshina_path(path), path)

    def test_rejects_dev_and_test_filenames(self):
        for name in ("hi.translit.sampled.dev.tsv", "hi.translit.sampled.test.tsv",
                     "hi_dev.tsv", "hi_test.tsv"):
            path = os.path.join(self.dir, name)
            make_dakshina(path, [dak_row("नम", "nam")])
            self.assertRaises(SystemExit, bp.validate_dakshina_path, path)

    def test_rejects_full_sentence_romanized_tree(self):
        rom = os.path.join(self.dir, "romanized")
        os.makedirs(rom)
        path = os.path.join(rom, "hi.translit.sampled.train.tsv")
        make_dakshina(path, [dak_row("नम", "nam")])
        self.assertRaises(SystemExit, bp.validate_dakshina_path, path)

    def test_rejects_missing_file_and_non_train_name(self):
        self.assertRaises(SystemExit, bp.validate_dakshina_path,
                          os.path.join(self.dir, "hi.translit.sampled.train.tsv"))
        path = os.path.join(self.dir, "hi.lexicon.tsv")
        make_dakshina(path, [dak_row("नम", "nam")])
        self.assertRaises(SystemExit, bp.validate_dakshina_path, path)


class DakshinaParseTests(unittest.TestCase):
    """Parsing, dedup, positive counts, NFC and malformed-row rejection."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bip-dak-parse-")
        self.path = os.path.join(self.dir, "hi.translit.sampled.train.tsv")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def build(self, rows):
        make_dakshina(self.path, rows)
        return bp.build_dakshina_pairs(self.path, "hi")

    def test_counts_preserved_and_duplicates_summed(self):
        pairs, stats = self.build([
            dak_row("नम", "nam", 3),
            dak_row("नम", "nam", 2),      # same exact pair -> 5
            dak_row("नम", "namah", 4),    # variant spelling, separate pair
            dak_row("कल", "kal", 1),
        ])
        self.assertEqual(pairs[("nam", "नम")], 5)
        self.assertEqual(pairs[("namah", "नम")], 4)
        self.assertEqual(pairs[("kal", "कल")], 1)
        self.assertEqual(stats["accepted_rows"], 4)
        self.assertTrue(all(c > 0 for c in pairs.values()))

    def test_nfc_normalisation_of_both_sides(self):
        nfd = unicodedata.normalize("NFD", "बऩाल")
        pairs, _ = self.build([dak_row(nfd, "banaal", 2)])
        self.assertIn(("banaal", unicodedata.normalize("NFC", nfd)), pairs)
        for native in {k[1] for k in pairs}:
            self.assertEqual(unicodedata.normalize("NFC", native), native)

    def test_malformed_and_invalid_rows_counted(self):
        pairs, stats = self.build([
            dak_row("नम", "nam", 1),        # ok
            "नम\tnam",                      # 2 columns
            "नम\tnam\t1\textra",            # 4 columns
            dak_row("कमल", "kamal", 0),     # zero count
            dak_row("कमल", "kamal", -2),    # negative count
            dak_row("कमल", "kamal", "x"),   # non-integer count
            dak_row("काला", "k@la", 1),     # junk roman
            dak_row("काला", "kāla", 1),     # non-ascii roman
            dak_row("हelo", "helo", 1),     # mixed script
        ])
        reasons = stats["reject_reasons"]
        self.assertEqual(reasons["bad_columns"], 2)
        self.assertEqual(reasons["bad_count"], 3)
        self.assertEqual(reasons["url_email_junk"], 1)
        self.assertEqual(reasons["non_latin_roman"], 1)
        self.assertEqual(reasons["mixed_script"], 1)
        self.assertEqual(pairs, {("nam", "नम"): 1})

    def test_zwj_zwnj_stripped_from_native(self):
        pairs, _ = self.build([dak_row("क\u200dम", "kam", 2)])
        self.assertIn(("kam", "कम"), pairs)
        self.assertNotIn(("kam", "क\u200dम"), pairs)


class SourcePriorityTests(unittest.TestCase):
    """Dakshina coverage is guaranteed ahead of the mined fill; dedup is clean."""

    def test_all_dakshina_pairs_emitted_before_mined(self):
        dak = {("a", "अ"): 1, ("i", "इ"): 5, ("u", "उ"): 2}
        mined = {("ka", "क"): 900, ("kha", "ख"): 800, ("ga", "ग"): 700}
        merged = bp.merge_pairs(dak, mined)
        body, n_pairs, _, _ = bp.render_body(merged, 5, 100000, priority=dak)
        lines = body.splitlines()
        self.assertEqual(n_pairs, 5)
        # The three Dakshina natives occupy the top rows (ranked by count).
        self.assertEqual([l.split("\t")[1] for l in lines[:3]], ["इ", "उ", "अ"])
        # Only the highest-ranked mined pair fits in the remaining budget.
        self.assertIn("ka\tक\t900", lines)

    def test_dakshina_only_when_cap_exhausted(self):
        dak = {("a", "अ"): 1, ("i", "इ"): 1}
        mined = {("ka", "क"): 900}
        merged = bp.merge_pairs(dak, mined)
        body, _, _, _ = bp.render_body(merged, 2, 100000, priority=dak)
        self.assertNotIn("क", body)
        self.assertIn("अ", body)
        self.assertIn("इ", body)

    def test_identical_pair_is_deduped_with_dakshina_count(self):
        dak = {("a", "अ"): 7}
        mined = {("a", "अ"): 999, ("b", "ब"): 3}
        merged = bp.merge_pairs(dak, mined)
        self.assertEqual(merged[("a", "अ")], 7)  # not summed across sources
        body, n_pairs, _, _ = bp.render_body(merged, 100, 100000, priority=dak)
        self.assertEqual(n_pairs, 2)
        self.assertEqual(body.count("a\tअ"), 1)
        self.assertIn("a\tअ\t7", body)
        self.assertIn("b\tब\t3", body)

    def test_priority_romans_precede_mined_for_same_native(self):
        dak = {("namaste", "नमस्ते"): 1}
        mined = {("namaste", "नमस्ते"): 500, ("namasthe", "नमस्ते"): 900}
        merged = bp.merge_pairs(dak, mined)
        body, _, _, _ = bp.render_body(merged, 100, 100000, priority=dak)
        lines = body.splitlines()
        self.assertEqual(lines[0], "namaste\tनमस्ते\t1")
        self.assertEqual(lines[1], "namasthe\tनमस्ते\t900")

    def test_single_source_render_is_unchanged(self):
        # priority=None must reproduce the frequency ranking of the old builder.
        pairs = {("naj", "नम"): 1, ("nov", "नव"): 3}
        body, *_ = bp.render_body(pairs, 100, 100000)
        self.assertLess(body.index("नव\t"), body.index("नम\t"))


class CombinedCliTests(unittest.TestCase):
    """End-to-end merge of Aksharantar + Dakshina with deterministic output."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="bip-comb-")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def _inputs(self):
        zip_path = os.path.join(self.dir, "aksharantar-hin.zip")
        make_zip(zip_path, {TRAIN: [row("कल", "kal"), row("कल", "kal"),
                                    row("कमल", "kamal")]})
        dak = os.path.join(self.dir, "hi.translit.sampled.train.tsv")
        make_dakshina(dak, [dak_row("है", "hai", 6),
                            dak_row("नमस्ते", "namaste", 5),
                            dak_row("कल", "kal", 3)])
        return zip_path, dak

    def test_cli_merges_sources_deterministically(self):
        zip_path, dak = self._inputs()
        base = [sys.executable, "-I", MODULE_PATH, "--hi-zip", zip_path,
                "--hi-dakshina", dak, "--max-pairs", "1000",
                "--split", "train", "--out-dir"]
        outs = []
        for i, outdir in enumerate(("o1", "o2")):
            out = os.path.join(self.dir, outdir)
            subprocess.run(base + [out], check=True, capture_output=True)
            outs.append(open(os.path.join(out, "hi.tsv"), "rb").read())
        self.assertEqual(outs[0], outs[1])

        text = outs[0].decode("utf-8")
        header = "\n".join(l for l in text.splitlines() if l.startswith("#"))
        self.assertIn("CC BY-SA 4.0", header)
        self.assertIn("CC0 1.0", header)  # Aksharantar rows credit preserved
        self.assertIn("Roark et al. 2020", header)
        self.assertIn("hi.translit.sampled.train.tsv", header)
        data = [l for l in text.splitlines() if l and not l.startswith("#")]
        self.assertTrue(all(len(l.split("\t")) == 3 for l in data))
        self.assertIn("hai\tहै\t6", data)
        self.assertIn("namaste\tनमस्ते\t5", data)
        # The shared kal pair is deduped: exactly one row, Dakshina count wins.
        self.assertEqual([l for l in data if l.split("\t")[:2] == ["kal", "कल"]],
                         ["kal\tकल\t3"])

    def test_cli_rejects_dev_dakshina_filename(self):
        zip_path, _ = self._inputs()
        dev = os.path.join(self.dir, "hi.translit.sampled.dev.tsv")
        make_dakshina(dev, [dak_row("नम", "nam")])
        out = os.path.join(self.dir, "o-dev")
        result = subprocess.run(
            [sys.executable, "-I", MODULE_PATH, "--hi-dakshina", dev,
             "--out-dir", out], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(os.path.exists(os.path.join(out, "hi.tsv")))


class RealAssetAcceptanceTests(unittest.TestCase):
    """The committed assets keep the attested Dakshina train pairs."""

    ASSETS = os.path.join(HERE, "reference")

    def _load(self, name):
        path = os.path.join(self.ASSETS, name)
        if not os.path.exists(path):  # pragma: no cover - assets are committed
            self.skipTest("asset not present: %s" % path)
        pairs = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                if not line or line.startswith("#"):
                    continue
                roman, native, count = line.rstrip("\n").split("\t")
                pairs[(roman, native)] = int(count)
        return pairs

    def test_hindi_function_words_present_and_nfc(self):
        pairs = self._load("hi.tsv")
        for roman, native in (("hai", "है"), ("mein", "में"),
                              ("ka", "का"), ("namaste", "नमस्ते")):
            self.assertIn((roman, native), pairs, "%s -> %s missing" % (roman, native))
            self.assertGreater(pairs[(roman, native)], 0)
            self.assertEqual(unicodedata.normalize("NFC", native), native)
            self.assertTrue(bp.valid_native_script(native, HI_BLOCK))

    def test_kannada_everyday_words_present_and_nfc(self):
        pairs = self._load("kn.tsv")
        for roman, native in (("idu", "ಇದು"), ("mattu", "ಮತ್ತು"),
                              ("namaskara", "ನಮಸ್ಕಾರ"),
                              ("namaskaara", "ನಮಸ್ಕಾರ")):
            self.assertIn((roman, native), pairs, "%s -> %s missing" % (roman, native))
            self.assertGreater(pairs[(roman, native)], 0)
            self.assertEqual(unicodedata.normalize("NFC", native), native)
            self.assertTrue(bp.valid_native_script(native, KN_BLOCK))


if __name__ == "__main__":
    unittest.main()
