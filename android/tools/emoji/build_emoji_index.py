#!/usr/bin/env python3
"""Build the emoji keyword assets from Unicode CLDR annotations.

The IME's emoji strip offers emoji for the word being typed. Unicode CLDR
publishes, for every emoji in en, hi and kn, the keywords people search it
with. This script flattens those annotations into one line per keyword:

    keyword<TAB>emoji emoji ...

Phrases ("grinning face") are split into single words, and each keyword keeps
at most the eight most relevant emoji: an emoji whose TTS name is the keyword
comes first, then the most specific ones (fewest keywords). Skin-tone takes
and ZWJ composites (gender, family, professions) are left out in favour of the
base emoji.

Downloads (CLDR JSON from unicode-org/cldr-json and the Unicode License v3
text) are cached in /tmp/cldr-emoji-index; pass --refresh to fetch them again.

    python3 -I android/tools/emoji/build_emoji_index.py
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
import urllib.request
from pathlib import Path

LANGUAGES = ("en", "hi", "kn")
MAX_EMOJI_PER_KEYWORD = 8
CACHE = Path("/tmp/cldr-emoji-index")
ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "android/app/src/main/assets"
CLDR_RAW = "https://raw.githubusercontent.com/unicode-org/cldr-json"
LATEST_RELEASE = "https://api.github.com/repos/unicode-org/cldr-json/releases/latest"
LICENSE_URL = "https://www.unicode.org/license.txt"
ZWJ = "\u200d"
SKIN_TONES = frozenset(chr(cp) for cp in range(0x1F3FB, 0x1F3FF + 1))
PUNCTUATION = ".,:;!?\"'()[]"
# CLDR spells some Indic conjuncts with an invisible ZWJ or ZWNJ; typing
# never produces those marks, so keywords keep their plain form.
STRIP_INVISIBLES = str.maketrans("", "", "\u200c\u200d")


def fetch(url: str, name: str, refresh: bool) -> bytes:
    """Download a file into /tmp, keeping the copy so re-runs work offline."""
    dest = CACHE / name
    if dest.exists() and not refresh:
        return dest.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "wisperlow-emoji-index"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    CACHE.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return data


def cldr_version(refresh: bool) -> str:
    """CLDR releases are the tags of the cldr-json repo, so the tag names the
    version to cite in the generated comment header."""
    release = json.loads(fetch(LATEST_RELEASE, "release.json", refresh))
    return release["tag_name"]


def is_variant(emoji: str) -> bool:
    """Base emoji only: skin-tone takes and ZWJ composites merely restate a
    simpler emoji that carries the same keywords."""
    return ZWJ in emoji or not SKIN_TONES.isdisjoint(emoji)


def annotation_words(annotations: dict, language: str) -> list[str]:
    """Keyword phrases as single lowercased words; NFC for the Indic scripts,
    which CLDR sometimes stores with combining marks in either form."""
    phrases = annotations.get("default") or annotations.get("tts") or []
    words = []
    for phrase in phrases:
        for token in phrase.split():
            token = token.strip(PUNCTUATION).translate(STRIP_INVISIBLES).lower()
            if language in ("hi", "kn"):
                token = unicodedata.normalize("NFC", token)
            if any(ch.isalnum() for ch in token):
                words.append(token)
    return words


def annotation_map(blob: dict) -> dict:
    """The emoji -> annotations map; annotations.json and annotationsDerived
    each wrap it in their own top-level key."""
    root = blob.get("annotations") or blob.get("annotationsDerived")
    return root["annotations"]


def language_lines(version: str, language: str, refresh: bool) -> list[str]:
    sources = (
        (
            f"{CLDR_RAW}/{version}/cldr-json/cldr-annotations-full/annotations/"
            f"{language}/annotations.json",
            f"{language}-full.json",
        ),
        (
            f"{CLDR_RAW}/{version}/cldr-json/cldr-annotations-derived-full/annotationsDerived/"
            f"{language}/annotations.json",
            f"{language}-derived.json",
        ),
    )
    # emoji -> (tts name, distinct keywords), in CLDR order.
    info: dict[str, tuple[str, list[str]]] = {}
    for url, cache_name in sources:
        for emoji, annotations in annotation_map(json.loads(fetch(url, cache_name, refresh))).items():
            if is_variant(emoji):
                continue
            tts = ((annotations.get("tts") or [""])[0] or "").lower()
            words = annotation_words(annotations, language)
            if emoji in info:
                old_tts, old_words = info[emoji]
                tts = old_tts or tts
                words = list(dict.fromkeys(old_words + words))
            info[emoji] = (tts, words)

    buckets: dict[str, dict[str, None]] = {}
    for emoji, (_, words) in info.items():
        for word in words:
            buckets.setdefault(word, {})[emoji] = None

    lines = []
    for word in sorted(buckets):
        # A stable sort keeps CLDR order between equally relevant emoji.
        best = sorted(buckets[word], key=lambda e: (word != info[e][0], len(info[e][1])))
        lines.append(f"{word}\t{' '.join(best[:MAX_EMOJI_PER_KEYWORD])}")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description="build the emoji keyword assets from CLDR")
    parser.add_argument("--refresh", action="store_true", help="re-download the cached CLDR files")
    args = parser.parse_args()

    version = cldr_version(args.refresh)

    license_path = ASSETS / "licenses/unicode-cldr.txt"
    license_path.parent.mkdir(parents=True, exist_ok=True)
    license_path.write_bytes(fetch(LICENSE_URL, "unicode-license.txt", args.refresh))
    print(f"license: {license_path.stat().st_size} bytes -> {license_path.relative_to(ROOT)}")

    for language in LANGUAGES:
        lines = language_lines(version, language, args.refresh)
        header = (
            f"# Unicode CLDR {version} emoji annotations ({language}) - Unicode License v3, "
            "see licenses/unicode-cldr.txt"
        )
        out = ASSETS / "emoji" / f"{language}.tsv"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join([header, *lines]) + "\n", encoding="utf-8")
        print(f"{language}: {len(lines)} keywords, {out.stat().st_size} bytes -> {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
