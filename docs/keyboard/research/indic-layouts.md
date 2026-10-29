# Indic Layouts: Hindi (Devanagari) & Kannada — Research & Wisperlow Recommendations

Date: 2026-10-17 · Public sources only. No app decompilation, no download/decompile of proprietary keyboards; key rows below are quoted from published layout tables (kbdlayout.info reproduces the Windows driver tables verbatim) or are Wisperlow recommendations.

Primary sources actually fetched this run:

| # | Source | URL |
|---|--------|-----|
| S1 | kbdlayout.info — Devanagari INSCRIPT layout (KBDINDEV.DLL, KLID 00000439), overview + generated KLC file | https://kbdlayout.info/KBDINDEV/ · https://kbdlayout.info/KBDINDEV/download/klc |
| S2 | kbdlayout.info — Kannada (INSCRIPT) layout (KBDINKAN.DLL, KLID 0000044b), overview + generated KLC file | https://kbdlayout.info/KBDINKAN/ · https://kbdlayout.info/KBDINKAN/download/klc |
| S3 | kbdlayout.info — "How do I type ಕ್ಷ" (verifies Shift+7 on the Kannada layout = ಕ+್+ಷ) | https://kbdlayout.info/how/%E0%B2%95%E0%B3%8D%E0%B2%B7 |
| S4 | Wikipedia — InScript keyboard (12-script standard; DOE 1986, 1988 nukta/matra revision, BIS 1992, "Enhanced InScript 5.1" 2008 CDAC task force; InScript removed from Gboard / Google Indic Keyboard) | https://en.wikipedia.org/wiki/InScript_keyboard |
| S5 | Wikipedia — Devanagari (letters, matras incl. left-side ि, ZWJ/ZWNJ via Unicode 5.1, block chart) | https://en.wikipedia.org/wiki/Devanagari |
| S6 | Wikipedia — Kannada script (vowel/diacritic table, halanta "half letter", arkaavotthu, anusvara preferred over ಙ/ಞ conjuncts) | https://en.wikipedia.org/wiki/Kannada_script |
| S7 | IISc/RCILTS Kannada Unicode design guide (halant U+0CCD, dead-consonant & RA rendering rules R0–R8, KGP 26-key standard, "no left-side vowel signs" and "punctuation borrowed from English" in Kannada) | https://brahmi.sourceforge.net/docs/KannadaComputing.html |
| S8 | Microsoft Learn — Devanagari INSCRIPT interactive layout | https://learn.microsoft.com/en-us/globalization/keyboards/kbdindev |
| S9 | Microsoft Learn — Kannada IME ("By default, Kannada is installed with the Kannada INSCRIPT keyboard") | https://learn.microsoft.com/en-us/globalization/input/kannada-ime |
| S10 | Gboard Help — Type in a different language (add language → pick layout; no published key maps) | https://support.google.com/gboard/answer/7068494 |
| S11 | Microsoft Support — Indic Phonetic keyboards ("transliterate… 'namaste' → नमस्ते"-class behaviour, based on ISO 15919) | https://support.microsoft.com/en-us/windows/hardware/input-devices/set-up-and-use-indic-phonetic-keyboards |
| S12 | Unicode UAX #29 — Grapheme cluster boundaries | https://www.unicode.org/reports/tr29/ |
| S13 | Android developer docs — InputConnection.deleteSurroundingText (operates in code points, not clusters) | https://developer.android.com/reference/android/view/inputmethod/InputConnection#deleteSurroundingText(int,int) |
| S14 | Google blog — Gboard for Android gets new languages and tools (Indic languages, transliteration) | https://blog.google/products-and-platforms/products/search/gboard-android-gets-new-languages-and-tools/ |
| S15 | Manoj Kumar Sharma (IIT KGP thesis) — dynamic matra-key generation: "the system generates the key combining consonant and dependent vowel (matra) at runtime" | https://cse.iitkgp.ac.in/~dsamanta/resources/thesis/Manoj-Kumar-Sharma-Thesis.pdf |
| S16 | Unicode code charts — Devanagari U+0900–U+097F, Kannada U+0C80–U+0CFF | https://www.unicode.org/charts/PDF/U0900.pdf · https://www.unicode.org/charts/PDF/U0C80.pdf |

Not publicly documented (say-so items below): per-key maps of **Gboard's current** Hindi/Kannada native layouts — Google's Help Center only documents adding a language and choosing a layout (S10); the blog documents Indic support and transliteration (S14). Everything Gboard-related below is at feature level; per-key claims for Gboard are explicitly unverifiable this run.

---

## Part 1 — Native-script layouts

### 1.0 Shared note: what "InScript" is

InScript is the Government of India's standard 104-key layout for 12 Indic scripts (S4), standardised 1986, revised 1988 (nukta + some matras), BIS 1992, extended 2008 ("Enhanced InScript 5.1" adding ZWJ/ZWNJ and Unicode 5.1 characters; S4). Its defining trick: **all twelve scripts share one physical map**, because the scripts share a phonetic character order — a Hindi InScript typist can type Kannada by position without knowing the letters (S4). Vowel keys carry the **matra** (dependent vowel sign) unshifted and the **independent vowel** on Shift; consonants sit to the right in varga order; four famous conjuncts (ज्ञ त्र क्ष श्र / ಜ್ಞ ತ್ರ ಕ್ಷ ಶ್ರ) and the two ra-forms get dedicated Shift keys on the number row.

### 1.1 Hindi (Devanagari) — InScript (BIS standard, as shipped in Windows)

Verified key-for-key from the Windows KBDINDEV driver table and its generated KLC (S1; rendered interactively at S8). Format per key: **base / Shift**.

**Number row** (Shift produces the conjunct keys — this is the heart of InScript):

- `1` = 1 / ऍ (candra e) · `2` = 2 / ॅ (candra e *sign*) · `3` = 3 / **्+र** (dead-र second element — क्र, त्र, प्र) · `4` = 4 / **र+्** (the reph — renders above the *next* consonant, कर्म) · `5` = **ज्ञ** · `6` = **त्र** · `7` = **क्ष** · `8` = **श्र** · `9` = 9 / ( · `0` = 0 / )
- `` ` `` (backtick) = ॊ / ऒ (short-o pair) · `\` (backslash; duplicated on the non-US 102nd key) = ॉ (candra o *sign*) / ऑ (candra o *letter*) · `-` = - / **ः (visarga)**
- Shift+1..8 outputs go through the driver's **LIGATURE table**: they emit the multi-code-point strings shown above (e.g. Shift+3 = `094D 0930`), not precomposed glyphs. ZWJ/ZWNJ/₹/देवनागरी digits are on the AltGr layers: **Ctrl+Alt+1 = ZWJ (U+200D)** · **Ctrl+Alt+2 = ZWNJ (U+200C)** · **Ctrl+Alt+4 = ₹ (U+20B9)** · **Shift+Ctrl+Alt on `1`–`0` = ०–९ Devanagari digits**.

**QWERTY row** (base / Shift — for vowel keys the *base* is the matra and *Shift* is the independent vowel): ौ / औ · ै / ऐ · ा / आ · ी / ई · ू / ऊ · ब / भ · ह / ङ · ग / घ · द / ध · ज / झ · `[` = ड / ढ · `]` = **़ (nukta sign U+093C)** / ञ

**Home row**: ो / ओ · े / ए · **् / अ** (the virama sits *unshifted*, अ on Shift!) · ि / इ · ु / उ · प / फ · र / ऱ · क / ख · त / थ · `;` = च / छ · `'` = ट / ठ

**ZXCV row**: ं / ँ (chandrabindu, Shift) · म / ण · न / ऩ · व / ऴ · ल / ळ · स / श · `,` = , / ष · `.` = . / **।** (danda; Ctrl+Alt = **॥** double danda; Shift+Ctrl+Alt = ऽ avagraha) · `/` = य / य़ · `=` = ृ / ऋ (Ctrl+Alt = ॄ, Shift+Ctrl+Alt = ॠ)

The nukta-era series also gets AltGr states: Ctrl+Alt on the क ख ग ज ड प keys (and their Shift counterparts) yields the QA/KHHA/GHHA/ZA/DDHA/DDDHA/FA characters (U+0958–U+095D, U+095E–U+095F covers य़), and Shift+Ctrl+Alt on `x` = ॐ (U+0950).

So the **Shift/second page** of InScript isn't a separate layout: Shift turns consonants into aspirates, and on vowel keys it flips matra ↔ independent vowel — the matra is the base state and the full vowel sits on Shift.

### 1.2 Kannada — InScript (as shipped in Windows, KBDINKAN)

Verified key-for-key from S2; S3 additionally confirms Shift+7 on the Kannada layout emits ಕ+್+ಷ (ಕ್ಷ) — same conjunct keys as Hindi, one map, twelve scripts.

**Number row**: `1–8` = ASCII digit / Shift = **೧–೮ (Kannada digits)**; `9` = 9 / ( · `0` = 0 / ); Shift+3 = **್+ರ (dead-ರ second element)** · Shift+4 = **ರ+್ (reph)** · Shift+5 = **ಜ್ಞ** · Shift+6 = **ತ್ರ** · Shift+7 = **ಕ್ಷ** · Shift+8 = **ಶ್ರ** — same spots as Hindi, one map, twelve scripts · `-` = - / **ಃ (visarga)**. AltGr extras: Shift+Ctrl+Alt+1/2 = ZWJ/ZWNJ, Shift+Ctrl+Alt+4 = ₹, and Ctrl+Alt on the ಏ/ಐ keys gives the length marks ೆ (U+0CD5) / ೈ (U+0CD6).

**QWERTY row** (base / Shift): ೌ / ಔ · ೈ / ಐ · ಾ / ಆ · ೀ / ಈ · ೂ / ಊ · ಬ / ಭ · ಹ / ಙ · ಗ / ಘ · ದ / ಧ · ಜ / ಝ · `[` = ಡ / ಢ · `]` = (unassigned) / ಞ

**Home row**: ೋ / ಓ · ೇ / ಏ · **್ / ಅ (virama unshifted)** · ಿ / ಇ · ು / ಉ · ಪ / ಫ (Ctrl+Alt+Shift = ಫ U+0CDE) · ರ / ಱ · ಕ / ಖ · ತ / ಥ · `;` = ಚ / ಛ · `'` = ಟ / ಠ

**ZXCV row**: ೆ / ಎ · ಂ (anusvara; Shift unassigned — Kannada has no chandrabindu) · ಮ / ಣ · ನ / (unassigned — no ಩) · ವ / (unassigned — no ಴) · ಲ / ಳ · ಸ / ಶ · `,` = , / ಷ · `.` = . / `|` (an English vertical line — *not* a danda) · `/` = ಯ / (unassigned) · `=` = ೃ / ಋ (Ctrl+Alt = ೄ, Shift+Ctrl+Alt = ೠ; also Ctrl+Alt layers for ಌ / ೠ on other keys)

Practical differences vs the Hindi map: Kannada has **no left-side vowel signs** — every matra sits right of / below its consonant (S7); several nukta-era characters (಩ ಴, and a chandrabindu) simply have **no Shift state** in the Kannada driver; and in the Kannada standard "punctuation is borrowed from English" (S7 §2.1.9) — Shift+`.` gives ASCII `|`, and ॥ appears nowhere on the driver map.

### 1.3 Gboard's Hindi and Kannada native layouts

Documented publicly (S10, S14, S4):

- Gboard lets you add Hindi or Kannada and **choose a layout** ("Add keyboard → Pick the layout you want" — S10). For Indic languages this means a **native script layout**, plus **Hinglish transliteration** for Hindi; voice and handwriting are separate modes. S14 (Google's blog) documents Gboard's Android launch of new Indic languages + transliteration tools.
- **InScript is not the layout Gboard uses**: InScript was available from Android 4.0 and was removed from Gboard and Google Indic Keyboard (S4).
- Feature-documented behaviour: long-press alternates; a suggestion strip with next-word prediction for Indic languages; glide typing; and the transliteration mode (S14; the same model class as S11's Windows phonetic keyboards).
- **No public source publishes the per-key arrangement of Gboard's current Hindi or Kannada layout** (say-so). Screenshots in press coverage suggest Gboard's native Devanagari layout follows InScript-like key positions (matras paired with vowel keys, conjunct keys available, nukta series via long-press) — treat as unverifiable this run and design your own rather than copying.

### 1.4 Popular phonetic / transliteration layouts

- **Transliteration IMEs** — the "Indic Phonetic" model documented by S11: user types Latin ("namaste"), the IME suggests नमस्ते-class candidates; rules based on ISO 15919. Google Input Tools documents the same mapping-by-phonetic-similarity approach (https://www.google.com/inputtools/services/features/transliteration.html).
- **Remington / Kruti Dev (typewriter legacy)** — still published charts (https://hinglishhindi.com/krutidev-keyboard); government typing-exam heritage, not a modern-phone design.
- **Baraha / Keyman phonetic keyboards** for Kannada (NLCI WinScript — https://help.keyman.com/keyboard/nlci_kannada_winscript/2.0; Baraha: https://baraha.com/help/Keyboards/kan-inscript.htm) — desktop-era solutions; concept transfers, nothing to copy key-for-key onto a phone.
- **KGP (Kannada Ganaka Parishat) standard 26-key layout**: the published Kannada-specific standard besides InScript — 26 letter keys, each with a Shift-state double, covering 13 swaras + 34 consonants + 4 special signs; the unassigned 52nd key reserved for nukta+ಫ and ಱ via combos (S7).

---

## Part 2 — How Indic text is entered (mechanics Wisperlow must implement)

Both scripts (S5, S6, S7, S16) are Abugidas with identical storage semantics: **an akshara/akṣara = ((C)C)CV**. A consonant is stored as `C` + optional dependent vowel sign; storage is always phonetic order, and the renderer (HarfBuzz) does the glyph gymnastics — the keyboard only inserts code points.

### 2.1 Matras (dependent vowel signs)

- **Devanagari**: matras ा ि ी ु ू ृ ॄ ॢ ॣ े ै ो ौ (plus ॉ ॅ ऎ ॊ-class short/candra signs). The ि **renders to the left** of its consonant (`क + ि → कि`) — the only left-side cluster sign here; the reph (`र+्` typed *before* the next consonant) renders **above that consonant** instead (S5/S16).
- **Kannada**: matras ಾ ಿ ೀ ು ೂ ೃ ೆ ೇ ೈ ೊ ೋ ೌ; two-part matras compose with length marks U+0CD5/U+0CD6; **no left-side matras at all** (S7 §2.1.3).

### 2.2 Virama / halant

**् U+094D** (Devanagari) and **್ U+0CCD** (Kannada): appended after a consonant to cancel the inherent अ/ಅ — a "dead"/half consonant ("अर्धाक्षर", S6/S7). Dead consonant + live consonant → **conjunct**: क+्+ष → क्ष; ಜ+್+ಞ → ಜ್ಞ. Three-dead sequences (स्त्र-style, ಸ್ತ್ರ) work the same way. Kannada's full OpenType rendering rules (dead consonant R0, arkaavottu postscript R1, RA-subscript R2/R3, ligature R4–R6, ರ+ು → ರು special form R7) are published in S7 §2.5; the Devanagari analogue (reph र् drawn above the next consonant) is in S5/S16.

**Keyboard consequence**: insert raw `C + ್ + C` sequences; the font decides whether to show a horizontal pair, a stacked conjunct, or a ligature glyph. Wisperlow must not pre-shape or reorder.

### 2.3 Famous conjuncts (own keys everywhere)

- Devanagari: **क्ष** (क+्+ष), **ज्ञ** (ज+्+ञ), **त्र** (त+्+र), **श्र** (श+्+र), plus the two र-forms — S1's ligature table fixes the exact strings: `्+र` (Shift+3) is the dead-र *second* element that attaches to the previous consonant (क्र, म्र, त्र), while `र+्` (Shift+4) is the **reph** that renders above the *next* consonant (कर्म).
- Kannada: **ಕ್ಷ**, **ಜ್ಞ**, ತ್ರ, ಶ್ರ, plus the peculiar **arkaavotthu**: dead ರ + C renders the ra-postscript *after* the consonant — "ಕರ್ನಾಟಕ" stores ರ+್+ನ but draws ರ‍್ನ as ನ + arkaavotthu (S6).

### 2.4 Nukta

**Devanagari ़ U+093C** turns क→क़, ख→ख़, ग→ग़, ज→ज़, ड→ड़, ढ→ढ़, फ→फ़, य→य़ (S16). InScript gives the nukta sign its own key (S1), but on a phone, **long-press should emit the finished character** (ड़ in one tap); keep the bare ़ sign on the 2nd page only for exotic sequences (क़+ि).

### 2.5 Anusvara, chandrabindu, visarga

- **अं / ಅಂ (anusvara, U+0902 / U+0C82)** — nasal; extremely common in Hindi, and in Kannada the *preferred* way to write pre-consonantal nasals (ಬೆಂಗಳೂರು, not ಬೆಙ್ಗಳೂರು — S6).
- **अँ (chandrabindu, U+0901)** — Shift key in InScript (S1) → long-press alternate in our mobile design.
- **अः / ಅಃ (visarga, U+0903 / U+0C83)** — the `-` key in both InScripts (S1, S2) → long-press alternate of the anusvara key.
- ॐ (U+0950) — Shift+Ctrl+Alt on `x` in InScript (S1) → 2nd page.

### 2.6 ZWJ / ZWNJ

U+200D / U+200C (S5; added to InScript in the "Enhanced InScript 5.1" round — S4). Uses: force a visible half-form, break a ligature a renderer would form. Windows InScript: Ctrl+Alt+1 = ZWJ, Ctrl+Alt+2 = ZWNJ (S1). Phone placement: symbols/2nd-page keys.

### 2.7 Danda, digits, ₹

- **। U+0964, ॥ U+0965** — InScript's `.` key (Shift/Ctrl+Alt layers, S1); Wisperlow: dedicated । key + ॥ alternate. Kannada's own standard has no danda — punctuation is English-style `.` (S7 §2.1.9) — so keep `.` default for Kannada and expose ।/॥ as alternates anyway.
- **Digits**: Devanagari ०–९ (U+0966–096F) on InScript Shift+Ctrl+Alt (S1); Kannada ೦–೯ (U+0CE6–0CEF) on Shift for keys `1–8` — the driver leaves ೯/೦ off the `9`/`0` keys (parens there instead, S2). Phone rule: **Latin digits default; native digits on long-press and on the 2nd page** (app UI and typing practice in India are dominated by Arabic numerals).
- **₹**: InScript Ctrl+Alt+4 (S1) → symbol-row alternate.

### 2.8 Context-aware matra keys (the strongest phone-layout idea)

Research background: the "Innovative Matra Keypad" line of work formalises *generating the consonant+matra combined key at runtime depending on the last typed consonant* (S15). The widely-shipped, simpler equivalent (the model behind the Google Indic-era mobile keyboards): **vowel keys display independent vowels but insert matras when a consonant is pending.** One rule and a Devanagari phone keyboard becomes typable: the user thinks "क + aa" → ा lands after क.

**Wisperlow rule set (concrete, both scripts):**

1. **Pending-consonant check** (before each vowel-key insert): if the text before the cursor ends with a consonant `C` **not** followed by a virama, **not** followed by any matra, nukta, anusvara — the vowel key inserts that vowel's **matra**.
2. Otherwise (word start, after space/punctuation, after another matra, after virama→see 3) the key inserts the **independent vowel**.
3. **After virama**: a vowel key **replaces the trailing virama** (क+् then ि-key → कि, not क्‌ि) — the "undo my halant" path that lets one key serve both jobs.
4. **Virama key** (् / ್): inserts after a pending consonant (dead form, ready for a conjunct). A second immediate press is ignored (no double virama), unless the user pressed another consonant in between.
5. **Conjunct preset keys** (क्ष ज्ञ त्र श्र / ಕ್ಷ ಜ್ಞ): insert full `C+virama+C` sequences; a following vowel key attaches its matra to the **whole trailing cluster** (क्ष then ा → क्षा). The pending-consonant check must treat a trailing `C+virama+C` as pending.
6. **Nukta** (Hindi only): long-press on ड/ढ/क/ख/ग/ज/फ/य emits the finished nukta character; ़ combining sign lives on the 2nd page.
7. **Word-boundary aids**: after inserting a matra or completing a cluster, the space/suggestion logic treats the completed akshara as one unit (no suggestions inside clusters).

### 2.9 Long-press alternates worth shipping

- Hindi: ख→ख़, ड→ड़, etc. (nukta series); aspirate pairs (क→ख, ग→घ, ज→झ, च→छ, ट→ठ, ड→ढ, द→ध, ब→भ, प→फ, त→थ); न→ण ञ; ल→ळ ऴ; श→श्र.
- Kannada: ನ→ಣ ಙ ಞ; ಲ→ಳ; ರ→ಱ; ಸ→ಷ; aspirates (ಕ→ಖ, ಗ→ಘ, ಜ→ಝ, ಚ→ಛ, ಟ→ಠ, ಡ→ಢ, ತ→ಥ, ದ→ಧ, ಬ→ಭ, ಪ→ಫ).

---

## Part 3 — Recommended Wisperlow phone layouts (KeyboardLayout.kt terms)

Design constraints from `KeyboardLayout.kt`: rows of `Key`s with `width`, `alternates`, `hint`, `longPress`; `KeyRow.inset` for stagger; one extra letters-page available per script; optional `numberRow`. Target **10–11 keys per row** so keys stay big. Consonant rows follow modern-Hindi / modern-Kannada frequency, with aspirates and second-place letters on **long-press alternates** — nothing is lost, the main grid stays compact. There is one Shift-page per script (less-common letters, nukta series, native digits, ZWJ/ZWNJ).

Vowel keys use the context rule (§2.8): label = independent vowel, corner hint = its matra; after a pending consonant it inserts the **matra**.

### 3.1 Hindi — `DEVA` main page

**Final row spec:**

```
[optional number row]  1 2 3 4 5 6 7 8 9 0          hint/long-press: १ २ ३ ४ ५ ६ ७ ८ ९ ०
R1 (11): अ    इ    ई    उ    ऊ    ए    ऐ    ओ    औ    अं   अः
R2 (11): क    र    म    त    न    य    ल    स    ह    द    ब
R3 (10, inset .5): प  ग  ज  व  च  श  ष  ट  ड  ऋ
R4 (1.5+6+1.5):   [⇧]   ्   ।   क्ष   ज्ञ   श्र   त्र   [⌫]
bottom:            [?123] [,] [☺] [     space     ] [.] [↵]
```

Alternates (long-press), key by key:

- R1 vowels are hint-driven (label = independent vowel, corner hint = matra, insertion follows §2.8): अ/ा · इ/ि · ई/ी · उ/ु · ऊ/ू · ए/े · ऐ/ै · ओ/ो · औ/ौ · अं/ं (alts: ँ chandrabindu) · अः (alts: ः, ॐ, ऽ). The long vowels आ ई ऊ are already neighbours, so no duplicated alternates elsewhere in R1.
- R2: क (hint ख) alts: ख क़ · र alts: ऱ · म · त (hint थ) alts: थ त्र · न (hint ण) alts: ण ञ ऩ · य alts: य़ · ल (hint ळ) alts: ळ ऴ · स (hint ष) alts: ष · ह · द (hint ध) alts: ध ड़ · ब (hint भ) alts: भ
- R3: प (hint फ) alts: फ फ़ · ग (hint घ) alts: घ ग़ · ज (hint झ) alts: झ ज़ · व · च (hint छ) alts: छ · श (hint श्र) alts: श्र · ष · ट (hint ठ) alts: ठ · ड (hint ढ) alts: ढ ढ़ · ऋ alts: ॠ ऌ
- R4: ् (hint: it is also how क्र/त्र-class pairs start; alts kept minimal since क्ष ज्ञ श्र त्र are dedicated keys beside it) · । alts: ॥ ऽ ॰ · क्ष ज्ञ श्र त्र (each inserts the full `C+्+C` string)
- Bottom period key alts for Devanagari: ! ? … and ॥ ₹ । (। already adjacent on R4; harmless to repeat).

In KeyboardLayout.kt terms (sketch, R2 and R4 shown exactly):

```kotlin
// R2
KeyRow(listOf(
    Key(KeyKind.CHAR, "क", hint = "ख", alternates = listOf("ख", "क़")),
    Key(KeyKind.CHAR, "र", alternates = listOf("ऱ")),
    Key(KeyKind.CHAR, "म"),
    Key(KeyKind.CHAR, "त", hint = "थ", alternates = listOf("थ", "त्र")),
    Key(KeyKind.CHAR, "न", hint = "ण", alternates = listOf("ण", "ञ")),
    Key(KeyKind.CHAR, "य", alternates = listOf("य़")),
    Key(KeyKind.CHAR, "ल", hint = "ळ", alternates = listOf("ळ")),
    Key(KeyKind.CHAR, "स", hint = "ष", alternates = listOf("ष")),
    Key(KeyKind.CHAR, "ह"),
    Key(KeyKind.CHAR, "द", hint = "ध", alternates = listOf("ध", "ड़")),
    Key(KeyKind.CHAR, "ब", hint = "भ", alternates = listOf("भ")),
))
// R4
KeyRow(listOf(
    shift, // 1.5
    Key(KeyKind.CHAR, "्", hint = "क्ष"),
    Key(KeyKind.CHAR, "।", alternates = listOf("॥", "ऽ", "॰")),
    Key(KeyKind.CHAR, "क्ष"),
    Key(KeyKind.CHAR, "ज्ञ"),
    Key(KeyKind.CHAR, "श्र"),
    Key(KeyKind.CHAR, "त्र"),
    backspace, // 1.5
))
```

**Shift (second) page `DEVA-MORE` — final:**

```
M1 (10):  ॅ   ॉ   ॆ   ॊ   ऍ   ऑ   ऎ   ऒ   ॐ   ऽ
M2 (10):  क़   ख़   ग़   ज़   ड़   ढ़   फ़   य़   ऩ   ़
M3 (10):  ०   १   २   ३   ४   ५   ६   ७   ८   ९
M4 (1.5+4+1.5):  ABC   ZWNJ   ZWJ   ॥   ₹   [⌫]
```

### 3.2 Kannada — `KNDA` main page

**Final row spec:**

```
(optional number row)  1..0          hint/long-press: ೧..೦
R1 (11): ಅ   ಇ   ಈ   ಉ   ಊ   ಎ   ಏ   ಐ   ಒ   ಓ   ಔ
R2 (11): ಕ   ಗ   ಚ   ಜ   ಟ   ಡ   ತ   ದ   ನ   ಮ   ಬ
R3 (10, inset .5): ಯ  ರ  ಲ  ವ  ಸ  ಶ  ಷ  ಹ  ಪ  ಳ
R4 (1.5+5+1.5):    [⇧]   ್   ಂ   ।   ಕ್ಷ   ಜ್ಞ   [⌫]
bottom:            [?123] [,] [☺] [space] [.] [↵]
```

(R2 order notes: ಚ outranks ಶ for the main grid in modern-Kannada frequency, ಬ is heavy while ಶ/ಷ survive on R3; ಳ keeps a main key because it is uniquely Kannada-heavy. Mind that ಶ also feeds ಶ್ರೀ, ವಿಶ್ವ-class clusters via long-press.)

Alternates:

- R1: ಅ (hint ಾ) · ಇ (hint ಿ) · ಈ (hint ೀ) · ಉ (hint ು) · ಊ (hint ೂ) · ಎ (hint ೆ) · ಏ (hint ೇ) · ಐ (hint ೈ) · ಒ (hint ೊ) · ಓ (hint ೋ) · ಔ (hint ೌ) — the long vowels are already neighbours, so no duplicate alternates needed
- R2: ಕ (hint ಖ) alts: ಖ · ಗ (hint ಘ) alts: ಘ · ಚ (hint ಛ) alts: ಛ · ಜ (hint ಝ) alts: ಝ · ಟ (hint ಠ) alts: ಠ · ಡ (hint ಢ) alts: ಢ · ತ (hint ಥ) alts: ಥ ತ್ರ · ದ (hint ಧ) alts: ಧ · ನ (hint ಣ) alts: ಣ ಙ ಞ · ಮ · ಬ (hint ಭ) alts: ಭ
- R3: ಯ · ರ (hint ಱ) alts: ಱ · ಲ (hint ಳ) alts: ೞ · ವ · ಸ (hint ಷ) · ಶ (hint ಶ್ರ) alts: ಶ್ರ · ಷ · ಹ · ಪ (hint ಫ) alts: ಫ · ಳ
- R4: ್ (hint ಕ್ಷ) alts: none (ಕ್ಷ key is adjacent) · ಂ (hint ಃ) alts: ಃ · । alts: ॥ · ಕ್ಷ · ಜ್ಞ

**Shift page `KNDA-MORE` — final:**

```
M1 (10):  ೃ   ೄ   ಋ   ೠ   ಌ   ೡ   ೱ   ೲ   ಽ   ಼
M2 (10):  ಖ   ಘ   ಙ   ಛ   ಝ   ಞ   ಥ   ಧ   ಠ   ಢ
M3 (10):  ೦   ೧   ೨   ೩   ೪   ೫   ೬   ೭   ೮   ೯
M4 (1.5+4+1.5):  ABC   ZWNJ   ZWJ   ॥   ₹   [⌫]
```

(M1 tail: ಽ = avagraha U+0CBD, ಼ = nukta sign U+0CBC; ೱ/ೲ are Jihvamuliya U+0CF1 & Upadhmaniya U+0CF2 — Sanskrit-sign visarga relatives from Unicode 5.0, S16. M2 exists so the aspirate/nasal series is one visible grid even without long-pressing; the long-press alternates on R2/R3 are the fast path for the same characters.)

### 3.3 InScript fidelity note

For typists who already know InScript (a real minority on phones, but policy-relevant), a future variant can implement the verbatim S1/S2 maps above as `DEVA-INSCRIPT` / `KNDA-INSCRIPT` — the Engine only needs the same `KeyRow` machinery plus an `alternates` overlay for the Shift layer, since InScript's Shift page is exactly the independent-vowel/virama layer already described.

---

## Part 4 — Backspace on grapheme clusters, cursor movement, fonts & rendering

### 4.1 What "one delete" should mean

- Unicode defines **extended grapheme clusters** so that `C+matra`, `C+virama+C`, conjuncts like `क्ष` or sequences with marks are **one cluster** each (S12). Users of both scripts think in aksharas — the visible letter — so one backspace tap should delete **one extended grapheme cluster** (e.g. `क्षो` in a single tap; the tap after that removes the previous cluster).
- Why not code points: deleting one *code point* from `क्षो` would leave a naked matra (displays as a dotted circle; to the user the letter "broke"). Code-point delete is exactly what naive `deleteSurroundingText(1, 0)` gives.
- **Android gotcha**: `InputConnection.deleteSurroundingText(n, 0)` deletes **code points/characters, not grapheme clusters** — the documented contract (S13). Wisperlow must compute the cluster boundary itself (ICU4J `RuleBasedBreakIterator`/`BreakIteratorCharacterInstance` implements UAX#29) and call `deleteSurroundingText(clusterCountInCodePoints, 0)`.
- **Gboard's exact backspace granularity is not publicly documented** (say-so; no Google Help page states cluster- vs code-point behaviour). Community reports generally describe "the whole letter goes away", consistent with cluster-wise deletion; implement cluster-wise and match Gboard by observation later if needed.

### 4.2 Cursor movement

- Arrow keys / the editing panel must move by **cluster** (`BreakIterator` again). Visual positions and code point order diverge: ि renders before its consonant (S5); Kannada's two-part matras wrap around (ೆ ೇ ೈ ೊ ೋ), its arkavottu postscript renders after a dead ರ (S6, S7). A cursor placed between code segments of one cluster looks wrong; never park inside a cluster.
- Selection handles follow the same cluster rule so `ा`/`ि` are never selected separately from their consonant.

### 4.3 Fonts and rendering on Android

- **Shaping** is the OS's job: HarfBuzz applies the OpenType `dev2`/`knd2` tables — conjunct ligatures, reph, i-matra reordering, Kannada arkavottu, the ರ+ು special form (S7 §2.5 lists these rule classes explicitly). The keyboard inserts code points in phonetic order and nothing else.
- **Bundle fonts for labels**: system Noto Sans Devanagari / Noto Sans Kannada cover body text, but the keyboard also renders *small* label glyphs (क्ष, ಕ್ಷ, ಂ) and suggestion-strip text twice per word — bundling (subsetting) Noto under its OFL licence keeps look consistent across devices.
- **Counting glyphs**: any feature that counts "characters" (hint truncation, suggestion-strip width, Wisperlow's SmartSpacing) must use clusters (S12) — same helper as backspace.
- **Never normalize**: NFC-composed single code points exist for the whole Devanagari nukta series — क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़ ऩ ऱ ळ ऴ (U+0958–U+095F, U+0929, U+0931, U+0933–U+0934 — S16) — so always insert the precomposed code point chosen in Part 3, and display whatever arrives unchanged — NFC/NFD variance across sources is the sender's problem, not Wisperlow's.
- **Test fixtures** for the engine: क्ष त्र ज्ञ श्र and both र-orderings (क्र = क+्+र vs र्मा = र+्+म, the S1 Shift+3/Shift+4 strings), ि placement (कि renders ि to the left of क), ZWJ/ZWNJ joins; Kannada: ಕ್ಷ, ಜ್ಞ, ತ್ರ, ರ್ನ (arkavottu), ರ+ು → ರು, two-part matras (ಲೆ ಲೇ ಲೈ ಲೊ ಲೋ), and sorting notes from S7 §2.6 if history-search ever sorts Indic text.

### 4.4 Current Wisperlow behaviour (where the fix lands)

As shipped in the Android module this research pass covers:

- A plain backspace tap goes `TypingSession.backspace() → EditorField.backspaceKey() → sendDownUpKeyEvents(KEYCODE_DEL)` (`WisperlowKeyboardService.kt`, `ConnectionField`) — a key event whose delete granularity is decided by the focused app; the `EditorField` contract comment says this is deliberate so selections/emoji deletions stay the app's business.
- Held backspace escalates to whole-word delete (`KeyArea.kt`: repeats ≥ `WORD_DELETE_AFTER` → `typing.deleteWord()`), and dictation undo matches/removes the last insert by code-point length (`undoDictation()` uses `before(last.length)` + `deleteBefore(last.length)` → `ConnectionField.deleteBefore(n)` → `InputConnection.deleteSurroundingText(n, 0)`). Both are code-point paths — with Indic text they would tear a word apart one matra/cluster piece per code point.
- Context reads for spacing (`SmartSpacing.fit`) — and for the §2.8 pending-consonant check, which is yet to be implemented — slice text around the cursor via `getTextBeforeCursor`/`getTextAfterCursor`; those slices must stop at cluster boundaries too.

**So the cluster work belongs in keyboard-core**: a UAX#29 helper next to `TypingSession`/`EditorField` that (a) converts "delete N clusters" into the code-point count passed to `deleteSurroundingText`, (b) backs the §2.8 pending-consonant scan, and (c) re-uses the same boundary function for label/strip width counting. Deliberate `sendDownUpKeyEvents(KEYCODE_DEL)` stays for app-owned selection deletes.

---

## Quick cross-reference: InScript vs Wisperlow recommendation

| Topic | InScript desktop (S1, S2) | Wisperlow mobile recommendation |
|---|---|---|
| Vowel keys | unshifted = matra, Shift = independent vowel | one key, context rule §2.8 (matra after pending C, vowel otherwise) |
| Conjuncts | Shift + number row (5/6/7/8) | dedicated keys क्ष ज्ञ श्र त्र / ಕ್ಷ ಜ್ಞ + universal ्/್ key |
| Nukta | own key (`[` unshifted) / AltGr combos | long-press emits finished ड़/क़-class characters; ़ on 2nd page |
| ZWJ/ZWNJ | Ctrl+Alt+1 / +2 | 2nd-page keys |
| Digits | Shift+Ctrl+Alt (Dev) / Shift on `1–8` (Ka) = native digits | Latin default; native digits long-press + 2nd page |
| Danda | Shift+`.` = ।, Ctrl+Alt = ॥ | dedicated । key (Hindi); Kannada keeps `.` default per S7 |
| Aspirated consonants | same keys, Shift state | long-press alternates + full list on 2nd page |
