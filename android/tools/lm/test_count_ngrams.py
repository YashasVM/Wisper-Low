#!/usr/bin/env python3
"""Tests for count_ngrams.py.

Run with::

    python3 -m unittest test_count_ngrams -v
"""

import gzip
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE_PATH = os.path.join(HERE, "count_ngrams.py")
_spec = importlib.util.spec_from_file_location("count_ngrams", MODULE_PATH)
cn = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cn)


def read_tsv(path):
    with open(path, encoding="utf-8") as f:
        return [tuple(line.rstrip("\n").split("\t")) for line in f]


class ToolTestCase(unittest.TestCase):
    """Base class with a helper that runs the tool in-process."""

    def run_tool(self, lines, extra=(), compress=None):
        tmp = tempfile.mkdtemp(prefix="count_ngrams_test_")
        self.addCleanup(shutil.rmtree, tmp, True)
        src = os.path.join(tmp, "corpus.txt")
        with open(src, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
            if lines:
                f.write("\n")
        if compress == "gz":
            gz = src + ".gz"
            with open(src, "rb") as fi, gzip.open(gz, "wb") as fo:
                fo.write(fi.read())
            src = gz
        out = os.path.join(tmp, "out")
        args = (
            ["--out", out, "--min1", "1", "--min2", "1", "--min3", "1"]
            + list(extra)
            + [src]
        )
        stats = cn.main(args)
        self.assertTrue(os.path.isdir(out))
        return tmp, out, stats

    def rows(self, out, name):
        return [tuple(r[:-1]) for r in read_tsv(os.path.join(out, name))]

    def counts(self, out, name):
        return {r[0]: r[1] for r in read_tsv(os.path.join(out, name))}


class TokenisationTest(ToolTestCase):
    def test_inner_apostrophes_and_curly_quotes(self):
        _, out, _ = self.run_tool(["don't rock'n'roll dogs'"])
        uni = self.rows(out, "unigrams.tsv")
        words = {r[0] for r in uni}
        self.assertIn("don't", words)
        self.assertIn("rock'n'roll", words)
        self.assertIn("dogs", words)
        self.assertNotIn("dogs'", words)

    def test_curly_apostrophe_is_folded(self):
        _, out, _ = self.run_tool(["don\u2019t stop"])
        words = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertIn("don't", words)

    def test_hyphens_split_words_but_keep_context(self):
        _, out, _ = self.run_tool(["well-known fact"])
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertEqual({"well", "known", "fact"}, uni - {"<s>"})
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("well", "known"), bi)

    def test_indic_scripts_keep_combining_marks(self):
        _, out, _ = self.run_tool(["\u0928\u092e\u0938\u094d\u0924\u0947 \u0926\u0941\u0928\u093f\u092f\u093e\u0964"])
        words = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertIn("\u0928\u092e\u0938\u094d\u0924\u0947", words)
        self.assertIn("\u0926\u0941\u0928\u093f\u092f\u093e", words)

    def test_kannada_words(self):
        _, out, _ = self.run_tool(["\u0ca8\u0cae\u0cb8\u0ccd\u0c95\u0cbe\u0cb0 \u0cb5\u0cbf\u0cb6\u0ccd\u0cb5"])
        words = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertIn("\u0ca8\u0cae\u0cb8\u0ccd\u0c95\u0cbe\u0cb0", words)
        self.assertIn("\u0cb5\u0cbf\u0cb6\u0ccd\u0cb5", words)

    def test_handles_tags_and_emoji_are_not_words(self):
        _, out, _ = self.run_tool(["hello @user #tag \U0001f600 world"])
        words = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertEqual({"hello", "world", "<s>"}, words)


class CasingTest(ToolTestCase):
    def test_sentence_initial_casing_is_ignored(self):
        _, out, _ = self.run_tool(
            ["The cat sat.", "The dog ran.", "the bird flew."]
        )
        uni = self.counts(out, "unigrams.tsv")
        self.assertEqual(uni["the"], "3")

    def test_i_keeps_its_case(self):
        _, out, _ = self.run_tool(["I am here.", "You and I are.", "I go."])
        uni = self.counts(out, "unigrams.tsv")
        self.assertEqual(uni["I"], "3")

    def test_brand_case_is_kept(self):
        _, out, _ = self.run_tool(["iPhone is nice.", "I use iPhone daily."])
        uni = self.counts(out, "unigrams.tsv")
        self.assertEqual(uni["iPhone"], "2")

    def test_only_sentence_initial_falls_back_to_lowercase(self):
        _, out, _ = self.run_tool(["Hello world."])
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertIn("hello", uni)


class ContextBreakTest(ToolTestCase):
    def test_numbers_break_context(self):
        _, out, _ = self.run_tool(["I have 3 cats and 4 dogs."])
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("<s>", "i"), bi)
        self.assertIn(("i", "have"), bi)
        self.assertIn(("cats", "and"), bi)
        self.assertNotIn(("have", "3"), bi)
        self.assertNotIn(("3", "cats"), bi)
        self.assertNotIn(("and", "4"), bi)
        self.assertNotIn(("4", "dogs"), bi)

    def test_url_breaks_context(self):
        _, out, _ = self.run_tool(["visit https://example.com today"])
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("<s>", "visit"), bi)
        self.assertNotIn(("visit", "today"), bi)
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertNotIn("example", uni)
        self.assertNotIn("com", uni)

    def test_bare_domain_is_a_breaker(self):
        _, out, _ = self.run_tool(["visit example.com today"])
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertNotIn("example", uni)
        self.assertNotIn("com", uni)

    def test_email_breaks_context(self):
        _, out, _ = self.run_tool(["mail me@example.com please"])
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("<s>", "mail"), bi)
        self.assertNotIn(("mail", "please"), bi)


class SentenceStartTest(ToolTestCase):
    def test_sentence_start_token(self):
        _, out, _ = self.run_tool(["Hello world."])
        uni = self.rows(out, "unigrams.tsv")
        self.assertIn(("<s>",), uni)
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("<s>", "hello"), bi)
        self.assertIn(("hello", "world"), bi)
        tri = self.rows(out, "trigrams.tsv")
        self.assertIn(("<s>", "hello", "world"), tri)

    def test_line_ends_start_a_new_sentence(self):
        _, out, _ = self.run_tool(["one two", "three four"])
        bi = self.rows(out, "bigrams.tsv")
        self.assertIn(("<s>", "one"), bi)
        self.assertIn(("<s>", "three"), bi)
        self.assertNotIn(("two", "three"), bi)

    def test_line_with_no_words_has_no_sentence_start(self):
        _, out, _ = self.run_tool(["123 ...", "hello"])
        uni = self.rows(out, "unigrams.tsv")
        self.assertEqual([r for r in uni if r[0] == "<s>"], [("<s>",)])

    def test_sentence_start_never_cased(self):
        _, out, _ = self.run_tool(["The cat."])
        uni = self.counts(out, "unigrams.tsv")
        self.assertEqual(uni["<s>"], "1")


class SpillingTest(ToolTestCase):
    CORPUS = [
        "the cat sat on the mat",
        "the dog ran in the park",
        "a cat and a dog",
        "the cat and the dog",
    ]

    def test_chunk_spilling_matches_single_chunk(self):
        _, single, _ = self.run_tool(self.CORPUS, extra=["--chunk-entries", "100000"])
        _, spilled, _ = self.run_tool(self.CORPUS, extra=["--chunk-entries", "2"])
        for name in ("unigrams.tsv", "bigrams.tsv", "trigrams.tsv"):
            self.assertEqual(
                self.rows(single, name),
                self.rows(spilled, name),
                "%s differs after spilling" % name,
            )

    def test_stats_counts_are_reported(self):
        _, _, stats = self.run_tool(self.CORPUS)
        self.assertEqual(stats["lines"], 4)
        self.assertEqual(stats["sentences"], 4)
        self.assertGreater(stats["tokens"], 0)
        self.assertIn("vocabulary_size", stats)
        self.assertIn("unigrams", stats)
        self.assertIn("bigrams", stats)
        self.assertIn("trigrams", stats)
        self.assertIn("elapsed_seconds", stats)


class PruningTest(ToolTestCase):
    CORPUS = ["a b c", "a b c", "a b c", "a b d"]

    def test_vocab_keeps_top_n_and_drops_their_ngrams(self):
        _, out, _ = self.run_tool(self.CORPUS, extra=["--vocab", "3"])
        uni = self.rows(out, "unigrams.tsv")
        self.assertEqual(
            {r[0] for r in uni}, {"a", "b", "c", "<s>"}, uni
        )
        bi = self.rows(out, "bigrams.tsv")
        self.assertNotIn(("c", "d"), bi)
        tri = self.rows(out, "trigrams.tsv")
        self.assertNotIn(("b", "c", "d"), tri)

    def test_min_cutoffs(self):
        # "z" appears once, "q" twice; default min1 is 2.
        corpus = ["z q", "q q"]
        _, out, _ = self.run_tool(
            corpus, extra=["--min1", "2", "--min2", "3", "--min3", "3"]
        )
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertIn("q", uni)
        self.assertNotIn("z", uni)
        # The bigram "<s> q" appears twice, below the default min2 of 3.
        bi = self.rows(out, "bigrams.tsv")
        self.assertNotIn(("<s>", "q"), bi)

    def test_min2_and_min3(self):
        corpus = ["a b c d", "a b c d", "a b c d"]
        _, out, _ = self.run_tool(corpus, extra=["--min2", "3", "--min3", "3"])
        self.assertIn(("a", "b"), self.rows(out, "bigrams.tsv"))
        self.assertIn(("a", "b", "c"), self.rows(out, "trigrams.tsv"))
        _, out2, _ = self.run_tool(corpus, extra=["--min2", "4", "--min3", "4"])
        self.assertEqual(self.rows(out2, "bigrams.tsv"), [])
        self.assertEqual(self.rows(out2, "trigrams.tsv"), [])


class InputTest(ToolTestCase):
    def test_gzip_input(self):
        _, out, _ = self.run_tool(["hello world"], compress="gz")
        self.assertIn(("hello",), self.rows(out, "unigrams.tsv"))

    def test_max_lines(self):
        _, out, _ = self.run_tool(
            ["one two", "three four", "five six"], extra=["--max-lines", "2"]
        )
        uni = {r[0] for r in self.rows(out, "unigrams.tsv")}
        self.assertEqual({"one", "two", "three", "four", "<s>"}, uni)


class CliTest(unittest.TestCase):
    def test_runs_under_isolated_mode(self):
        tmp = tempfile.mkdtemp(prefix="count_ngrams_cli_")
        self.addCleanup(shutil.rmtree, tmp, True)
        src = os.path.join(tmp, "in.txt")
        out = os.path.join(tmp, "out")
        with open(src, "w", encoding="utf-8") as f:
            f.write("hello world\n")
        proc = subprocess.run(
            [
                sys.executable,
                "-I",
                MODULE_PATH,
                "--out",
                out,
                "--min1",
                "1",
                "--min2",
                "1",
                "--min3",
                "1",
                src,
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn(("hello", "1"), read_tsv(os.path.join(out, "unigrams.tsv")))
        stats = json.load(open(os.path.join(out, "stats.json")))
        self.assertEqual(stats["lines"], 1)
        # Progress and diagnostics go to stderr, never stdout.
        self.assertEqual(proc.stdout, "")


if __name__ == "__main__":
    unittest.main()
