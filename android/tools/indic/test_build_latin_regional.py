import argparse
import gzip
import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('latin_builder',Path(__file__).with_name('build_latin_regional.py'))
builder=importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)

class LatinBuilderTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name)
        self.db=sqlite3.connect(':memory:'); builder.create_db(self.db)
    def tearDown(self):
        self.db.close(); self.tmp.cleanup()
    def ingest(self,text,kind='real',limit=100):
        path=self.root/(kind+'.txt.gz')
        with gzip.open(path,'wt') as stream:stream.write(text)
        return builder.ingest(self.db,path,kind,limit)
    def output(self,words=100,pairs=100):
        builder.write_tables(self.db,self.root,'kn',words,pairs,{})
        def read(suffix):
            return [line.split('\t') for line in (self.root/f'kn_latn{suffix}.tsv').read_text().splitlines() if not line.startswith('#')]
        return read(''),read('_bigrams')
    def test_literal_ascii_only_no_mixed_script_fragments(self):
        self.assertEqual(list(builder.segments('Good valid bad123 heyಕನ್ನಡ end')), [['good','valid'],['end']])
    def test_sentence_and_invalid_token_break_pairs(self):
        self.assertEqual(list(builder.segments('one two. three ನಾಲ್ಕು five six! seven')), [['one','two'],['three'],['five','six'],['seven']])
    def test_explicit_line_does_not_pollute_neighbour_counts(self):
        result=self.ingest('ordinary hello\nsex nearby common\n')
        self.assertEqual(result['explicit_lines'],1)
        self.assertEqual(self.output()[0],[['hello','4'],['ordinary','4']])
    def test_duplicate_repetitions_capped_per_sentence(self):
        self.ingest('hello hello hello hello hello\n')
        words,pairs=self.output()
        self.assertEqual(words,[['hello','12']]); self.assertEqual(pairs,[['hello','hello','12']])
    def test_synthetic_counts_cannot_dominate_attested_real(self):
        self.ingest('regional everyday\n')
        self.ingest('wikipedia encyclopedic\n'*20,'synthetic')
        words,_=self.output(); self.assertEqual(words[:2],[['everyday','4'],['regional','4']])
        self.assertEqual(dict(words)['wikipedia'],'3')
    def test_line_limit_honoured(self):
        result=self.ingest('first\nsecond\n',limit=1)
        self.assertEqual(result['lines'],1); self.assertEqual(self.output()[0],[['first','4']])
    def test_fallback_train_membership_ignores_annotator_count(self):
        path=self.root/'kn.translit.sampled.train.tsv'; path.write_text('ಅ\troman\t9999\nಬ\talternate\t1\n')
        builder.ingest_fallback(self.db,path)
        self.assertEqual(self.output()[0],[['alternate','1'],['roman','1']])
    def test_fallback_refuses_validation_sentence_and_eval_files(self):
        for name in ['kn.translit.sampled.dev.tsv','romanized.txt','kn.translit.sampled.test.tsv']:
            with self.assertRaises(ValueError):builder.ingest_fallback(self.db,self.root/name)
    def test_unattested_synthetic_hapax_not_in_vocabulary(self):
        self.ingest('once\ntwice\ntwice\n','synthetic')
        self.assertEqual(self.output()[0],[['twice','2']])
    def test_real_pairs_require_repeated_evidence_and_in_vocabulary(self):
        self.ingest('alpha beta\nalpha beta\nbeta gamma\n')
        self.assertEqual(self.output()[1],[['alpha','beta','8']])
        self.assertEqual(self.output(words=1)[1],[])
    def test_deterministic_ties_caps_and_output(self):
        self.ingest('beta alpha\nalpha beta\n')
        self.assertEqual(self.output(words=1)[0],[['alpha','8']])
        first=(self.root/'kn_latn.tsv').read_bytes(); self.output(words=1)
        self.assertEqual((self.root/'kn_latn.tsv').read_bytes(),first)
    def test_output_all_lowercase_latin_and_positive_counts(self):
        self.ingest('Hello WORLD ಕನ್ನಡ 99 mixed1\nHello WORLD\n')
        words,pairs=self.output()
        self.assertEqual(words,[['hello','8'],['world','8']])
        self.assertEqual(pairs,[['hello','world','8']])

    def test_urls_and_emails_do_not_create_fragments_or_bridging_pairs(self):
        self.assertEqual(list(builder.segments('one https://example.com two user@example.org three')), [['one'],['two'],['three']])
    def test_observed_explicit_story_phrase_filtered(self):
        result=self.ingest('kama kathegalu neighbours\nordinary story\n')
        self.assertEqual(result['explicit_lines'],1)
        self.assertEqual(self.output()[0],[['ordinary','4'],['story','4']])
    def test_conversation_uses_original_input_not_normalized_targets(self):
        path=self.root/'TN_train.csv'
        path.write_text('Sentences,Predicted Tags\noriginal spelling,canonicalized other\n')
        builder.ingest(self.db,path,'conversation',100)
        self.assertEqual(self.output()[0],[['original','1000016'],['spelling','1000016']])
    def test_conversation_rejects_unknown_or_eval_filename(self):
        path=self.root/'TN_test.csv'; path.write_text('Sentences\nheldout\n')
        with self.assertRaises(ValueError):builder.ingest(self.db,path,'conversation',100)
    def test_conversation_labels_and_cross_source_duplicates_filtered(self):
        path=self.root/'train_hope.csv'
        path.write_text('sentence,class\nhello regional,Hope\nignored text,not-Kannada\n')
        result=builder.ingest(self.db,path,'conversation',100)
        self.assertEqual(result['filtered_labels'],1)
        path=self.root/'kannada_offensive_train.csv'
        path.write_text('Hello   Regional\tNot_offensive\nbad content\tOffensive_Targeted_Insult_Group\n')
        result=builder.ingest(self.db,path,'conversation',100)
        self.assertEqual(result['duplicates'],1); self.assertEqual(result['filtered_labels'],1)
        self.assertEqual(self.output()[0],[['hello','1000016'],['regional','1000016']])
    def test_generic_english_pair_filter_keeps_regional_pairs(self):
        self.ingest('of the\nof the\nof regional\nof regional\n')
        builder.write_tables(self.db,self.root,'kn',100,100,{},common_english={'of','the'})
        lines=[l for l in (self.root/'kn_latn_bigrams.tsv').read_text().splitlines() if not l.startswith('#')]
        self.assertEqual(lines,['of\tregional\t8'])

    def test_conversation_pair_mode_excludes_web_only_continuations(self):
        self.ingest('web continuation\nweb continuation\n')
        path=self.root/'TN_train.csv'; path.write_text('Sentences\nregional speech\n')
        builder.ingest(self.db,path,'conversation',100)
        builder.write_tables(self.db,self.root,'kn',100,100,{},conversation_pairs_only=True)
        lines=[l for l in (self.root/'kn_latn_bigrams.tsv').read_text().splitlines() if not l.startswith('#')]
        self.assertEqual(lines,['regional\tspeech\t16'])
    def test_conversation_membership_always_above_web_fallback(self):
        self.ingest('popular\n'*100)
        path=self.root/'TN_train.csv'; path.write_text('Sentences\nconversational\n')
        builder.ingest(self.db,path,'conversation',100)
        self.assertEqual(self.output()[0],[['conversational','1000016'],['popular','400']])

    def test_known_heldout_sentence_exports_refused_as_corpus(self):
        for name in ['dakshina_romanized_sentences.txt.gz','dakshina_sentence_pairs.tsv','kn.translit.sampled.test.tsv','eval.txt.gz','corpus_dev.txt.gz']:
            with self.assertRaises(ValueError):builder.validate_corpus_path(Path(name))
    def test_real_and_synthetic_corpus_paths_allowed(self):
        builder.validate_corpus_path(Path('fineweb2_kan_Latn_sentences.txt.gz'))
        builder.validate_corpus_path(Path('sangraha_synthetic_latn_sentences.txt.gz'))

if __name__=='__main__': unittest.main()
