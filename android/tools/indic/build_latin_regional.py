#!/usr/bin/env python3
"""Build bounded Latin regional suggestion tables from licensed training corpora."""
import argparse
import collections
import csv
import gzip
import hashlib
import json
import re
import sqlite3
import tempfile
from pathlib import Path

# Exclude explicit-content lines in web dumps, rather than promoting their frequent
# neighbours into default suggestions. This small list is an auditable first filter,
# not a complete classifier. Literal typing/personal dictionaries remain unaffected.
EXPLICIT = frozenset('sex porn porno xxx fuck fucking fucked pussy cock lund choot chut chudai gand gaand tullu tunne thunne shata dengu kamakathegalu kamakathe shrungara'.split())
EXPLICIT_PHRASES = frozenset([('kama','kathegalu'), ('kama','kathe'), ('kama','stories'), ('nin','amman')])
WORD = re.compile(r'[a-z]{1,32}\Z')
SOURCE_URLS = {
    'real': 'https://huggingface.co/datasets/HuggingFaceFW/fineweb-2',
    'synthetic': 'https://huggingface.co/datasets/ai4bharat/sangraha',
    'fallback': 'https://github.com/google-research-datasets/dakshina',
}


def segments(line):
    """Never splice a URL, number, mixed-script token or sentence boundary."""
    line = re.sub(r'\S*(?:https?://|www\.|@)\S*', ' _ ', line.lower())
    for sentence in re.split(r'[.!?;\n]', line):
        words = []
        for raw in sentence.split():
            token = raw.strip(',():[]{}"“”')
            if WORD.fullmatch(token):
                words.append(token)
            elif words:
                yield words
                words = []
        if words:
            yield words


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def create_db(connection):
    connection.execute('CREATE TABLE words (word TEXT PRIMARY KEY, conversation INTEGER DEFAULT 0, real INTEGER DEFAULT 0, synthetic INTEGER DEFAULT 0, fallback INTEGER DEFAULT 0) WITHOUT ROWID')
    connection.execute('CREATE TABLE seen_conversation (digest TEXT PRIMARY KEY) WITHOUT ROWID')
    connection.execute('CREATE TABLE pairs (previous TEXT, following TEXT, conversation INTEGER DEFAULT 0, real INTEGER DEFAULT 0, synthetic INTEGER DEFAULT 0, PRIMARY KEY(previous,following)) WITHOUT ROWID')


def conversation_lines(path):
    # Only explicit TRAIN filenames/schemas are accepted; no automatic discovery
    # of adjacent CSV files, normalization annotations, dev or test splits.
    with open(path, encoding='utf-8-sig', newline='') as stream:
        if path.name == 'train_hope.csv':
            for row in csv.DictReader(stream):
                yield row.get('sentence', '') if row.get('class') in {'Hope','Not-Hope'} else None
        elif path.name == 'kannada_offensive_train.csv':
            for row in csv.reader(stream, delimiter='\t'):
                yield row[0] if len(row)==2 and row[1]=='Not_offensive' else None
        elif path.name == 'TN_train.csv':
            for row in csv.DictReader(stream):
                yield row.get('Sentences', '')  # Original input, never canonicalized targets.
        else:
            raise ValueError('Unsupported conversational TRAIN filename/schema')


def ingest(connection, path, kind, limit):
    """Disk-backed exact counts with small batches; source order cannot evict words."""
    words, pairs = collections.Counter(), collections.Counter()
    stats = dict(lines=0, accepted=0, explicit_lines=0, filtered_labels=0, duplicates=0, tokens=0)
    def flush():
        connection.executemany(f'INSERT INTO words(word,{kind}) VALUES(?,?) ON CONFLICT(word) DO UPDATE SET {kind}={kind}+excluded.{kind}', words.items())
        connection.executemany(f'INSERT INTO pairs(previous,following,{kind}) VALUES(?,?,?) ON CONFLICT(previous,following) DO UPDATE SET {kind}={kind}+excluded.{kind}', ((a,b,c) for (a,b),c in pairs.items()))
        connection.commit()
        words.clear(); pairs.clear()
    opener = gzip.open if str(path).endswith('.gz') else open
    stream = conversation_lines(path) if kind=='conversation' else opener(path, 'rt', encoding='utf-8')
    try:
        for line in stream:
            if stats['lines'] >= limit:
                break
            stats['lines'] += 1
            if line is None:
                stats['filtered_labels'] += 1
                continue
            if kind=='conversation':
                digest=hashlib.sha256(' '.join(line.lower().split()).encode('utf-8')).hexdigest()
                changed=connection.execute('INSERT OR IGNORE INTO seen_conversation VALUES(?)',(digest,)).rowcount
                if changed==0:
                    stats['duplicates'] += 1
                    continue
            groups = list(segments(line))
            if any(word in EXPLICIT for group in groups for word in group) or any(pair in EXPLICIT_PHRASES for group in groups for pair in zip(group, group[1:])):
                stats['explicit_lines'] += 1
                continue
            stats['accepted'] += 1
            for group in groups:
                stats['tokens'] += len(group)
                words.update({w:min(c,3) for w,c in collections.Counter(group).items()})
                pairs.update({p:min(c,3) for p,c in collections.Counter(zip(group,group[1:])).items()})
            if stats['accepted'] % 5000 == 0:
                flush()
        flush()
    finally:
        stream.close()
    return stats


def ingest_fallback(connection, path):
    """Dakshina TRAIN romanizations provide membership only, never frequency."""
    if not path.name.endswith('.translit.sampled.train.tsv'):
        raise ValueError('Fallback must be a Dakshina *.translit.sampled.train.tsv lexicon')
    words = set()
    with open(path, encoding='utf-8') as stream:
        for line in stream:
            cols = line.rstrip('\n').split('\t')
            if len(cols) != 3:
                continue
            roman = cols[1].lower()
            if WORD.fullmatch(roman) and roman not in EXPLICIT:
                words.add(roman)
    connection.executemany('INSERT INTO words(word,fallback) VALUES(?,1) ON CONFLICT(word) DO UPDATE SET fallback=1', ((w,) for w in sorted(words)))
    connection.commit()
    return dict(unique_romanizations=len(words), frequency_weight=0, membership_floor=1)


def write_tables(connection, out, language, max_words, max_pairs, metadata, common_english=frozenset(), conversation_pairs_only=False):
    # Real attestation dominates synthetic popularity. Synthetic contribution is
    # capped for each word/pair; a Wikipedia spelling cannot win by repetition.
    score = 'CASE WHEN conversation>0 THEN 1000000 + min(conversation*16+min(real,3)+min(synthetic,3),999999) ELSE min(real*4+min(synthetic,3)+CASE WHEN real=0 AND synthetic=0 THEN fallback ELSE 0 END,999999) END'
    rows = list(connection.execute(f'SELECT word,{score} AS score FROM words WHERE conversation>0 OR real>0 OR synthetic>=2 OR fallback>0 ORDER BY score DESC,word LIMIT ?', (max_words,)))
    vocab = {w for w,_ in rows}
    pair_rows = []
    pair_condition = 'conversation>=1' if conversation_pairs_only else 'conversation>=1 OR real>=2 OR synthetic>=5'
    pair_score = 'conversation*16' if conversation_pairs_only else 'conversation*16+real*4+min(synthetic,3)'
    for a,b,count in connection.execute(f'SELECT previous,following,{pair_score} AS score FROM pairs WHERE {pair_condition} ORDER BY score DESC,previous,following'):
        if a in vocab and b in vocab and not (a in common_english and b in common_english):
            pair_rows.append((a,b,count))
            if len(pair_rows)>=max_pairs: break
    out.mkdir(parents=True, exist_ok=True)
    header = '# Latin regional suggestion scores; counts are weighted/capped, not raw corpus frequencies.\n# '+json.dumps(metadata, sort_keys=True, ensure_ascii=True)+'\n'
    for suffix,table in [('',rows),('_bigrams',pair_rows)]:
        path = out/f'{language}_latn{suffix}.tsv'
        with open(path,'w',encoding='utf-8',newline='\n') as stream:
            stream.write(header)
            for row in table:
                stream.write('\t'.join(map(str,row))+'\n')
    return dict(words=len(rows),bigrams=len(pair_rows),top_words=rows[:20])


def validate_corpus_path(path):
    # The known downloaded Dakshina sentence exports are validation material.
    # They are not interchangeable with the explicit train-lexicon fallback.
    name=path.name.lower()
    if any(term in name for term in ['dakshina','romanized_sentences','sentence_pairs','token_pairs','validation']) or re.search(r'(?:^|[_.-])(dev|test|eval)(?:[_.-]|$)',name):
        raise ValueError('Held-out/reference corpus path is not allowed for Latin training: '+str(path))


def build(args):
    with tempfile.TemporaryDirectory(prefix='latin-regional-') as tmp:
        con=sqlite3.connect(str(Path(tmp)/'counts.sqlite3'))
        con.execute('PRAGMA journal_mode=OFF'); con.execute('PRAGMA synchronous=OFF')
        create_db(con)
        metadata=dict(language=args.language,version=1,weight='conversation word:1000000+min(16*conversation+min(real,3)+min(synthetic,3),999999); other word:min(4*real+min(synthetic,3),999999); fallback-only:1; pairs:16*conversation only',sources=[])
        if not args.conversation_train:
            metadata['weight'] = metadata['weight'].replace('pairs:16*conversation only','pairs:4*real+min(synthetic,3)')
        for kind,path,limit in [('real',args.real,args.real_lines),('synthetic',args.synthetic,args.synthetic_lines)]:
            if path:
                validate_corpus_path(path)
                entry=dict(kind=kind,file=path.name,sha256=sha256(path),url=SOURCE_URLS[kind],limit=limit)
                entry.update(ingest(con,path,kind,limit)); metadata['sources'].append(entry)
        for path in args.conversation_train:
            url = ('https://zenodo.org/records/5006517' if path.name=='train_hope.csv' else 'https://zenodo.org/records/4750858' if path.name=='kannada_offensive_train.csv' else 'https://github.com/lingo-iitgn/COMI-LINGUA')
            entry=dict(kind='conversation',file=path.name,sha256=sha256(path),url=url,limit=300000)
            entry.update(ingest(con,path,'conversation',300000)); metadata['sources'].append(entry)
        common_english=set()
        if args.english:
            with open(args.english,encoding='utf-8') as stream:
                for line in stream:
                    if line.startswith('#'):continue
                    fields=line.strip().split('\t')
                    if len(fields)==2 and WORD.fullmatch(fields[0].lower()):common_english.add(fields[0].lower())
                    if len(common_english)>=2000:break
            metadata['common_english_bigram_filter']=dict(file=args.english.name,sha256=sha256(args.english),words=len(common_english))
        if args.fallback:
            entry=dict(kind='fallback',file=args.fallback.name,sha256=sha256(args.fallback),url=SOURCE_URLS['fallback'])
            entry.update(ingest_fallback(con,args.fallback)); metadata['sources'].append(entry)
        result=write_tables(con,args.out_dir,args.language,args.max_words,args.max_pairs,metadata,common_english,conversation_pairs_only=bool(args.conversation_train))
        print(json.dumps(dict(metadata=metadata,output=result),sort_keys=True))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--language',choices=['hi','kn'],required=True)
    parser.add_argument('--real',type=Path,required=True)
    parser.add_argument('--synthetic',type=Path)
    parser.add_argument('--fallback',type=Path)
    parser.add_argument('--conversation-train',type=Path,action='append',default=[])
    parser.add_argument('--english',type=Path,help='Ordered frequency word TSV; suppress pairs whose two tokens are both in its top2000')
    parser.add_argument('--real-lines',type=int,default=300000)
    parser.add_argument('--synthetic-lines',type=int,default=100000)
    parser.add_argument('--max-words',type=int,default=30000)
    parser.add_argument('--max-pairs',type=int,default=50000)
    parser.add_argument('--out-dir',type=Path,required=True)
    args=parser.parse_args()
    for name in ['real_lines','synthetic_lines','max_words','max_pairs']:
        if getattr(args,name)<=0: parser.error(name+' must be positive')
    build(args)

if __name__=='__main__': main()
