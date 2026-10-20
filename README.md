# KMP Translations Hub

**Every translation of your Compose Multiplatform app in one table.** A JetBrains plugin (Android Studio and
IntelliJ IDEA 2025.1+) for `composeResources` strings: plurals and string-arrays, checks that follow each language's
rules, AI and on-device translation that only translates what changed, and "Run in this locale" through
[KMP Device Hub](https://plugins.jetbrains.com/plugin/35272-kmp-device-hub).

Freemium: the table, missing-key and placeholder checks and navigation are free.

## Modules

| Module | What it is |
|---|---|
| `core` | Dependency-free engine: `values-…` qualifiers, the `strings.xml` model and parser, plural rules per language, checks, and `translations.lock.json`. No IDE APIs, so a CLI or CI check can reuse it. |
| `idea-plugin` | The **Translations** editor tab, PSI writer (keeps formatting, one Undo per edit), and later inspections, quick fixes and translation providers. |

## Build and run

```bash
./gradlew test                       # core + IDE tests
./gradlew :idea-plugin:runIde -Ptranslationshub.openProject=/path/to/cmp/project
./gradlew :idea-plugin:verifyPlugin  # against the IDE in local.properties
```

Set the IDE to build against in the git-ignored `local.properties`:
`translationshub.ideaLocalPath=/Applications/Android Studio.app`.

`./gradlew :core:test -Ptranslationshub.realProject=/path/to/cmp/project` also reads a real project and prints what
it found per `composeResources` folder.

## Translating only what changed

`translations.lock.json` sits next to each `composeResources` folder and is committed with it. For each translation
it stores a hash of the source text it was made from, a hash of its own text, and who wrote it (a provider such as
`deepl`, `apple`, `mymemory`, or `human`):

| State | What "Translate changes" does |
|---|---|
| Missing | Translates it |
| Source changed, machine translation | Translates it again |
| Source changed, written by a person | Flags it for review, never overwrites it |
| Unchanged | Skips it: no API call |

A machine translation someone edited by hand counts as `human` from then on. Translations that existed before
the plugin are adopted as `human` and current, so the first run doesn't re-translate the app.

## Phase 0 results (2026-10-20)

- **Real project** (KhataPay: 8 `composeResources` folders, English + Urdu): all 381 keys read, the same counts as
  the files, 0 problems, in 98 ms.
- **2,000 keys × 15 locales** (1,800 strings, 150 plurals, 50 arrays): engine reads it in 30 ms, checks in 89 ms;
  the Translations tab opens in 151 ms, paints in 3 ms a frame, and an edit with re-read takes 279 ms.
- **Write-back**: edits change only the tag they touch (comments, blank lines, odd spacing and attribute layout
  survive); new keys land after the key they follow in the source; missing plural forms go in CLDR order; inline
  markup and Compose escapes pass through. Several edits are one Undo that restores the file byte for byte, and
  Undo works from the Translations tab too.
- **Plugin Verifier**: compatible with Android Studio 2026.2, no deprecated or experimental API use.

Found on the way: `XmlTagValue.setEscapedText` drops inline markup, and replacing a tag through the PSI runs the
formatter over it, so text with markup is written into the document directly.

## Roadmap

1. **Free MVP**: add/rename/delete keys across locales, add a locale, plural and array editing panel, search and
   "missing only" filter, `Res.string.x` ↔ XML navigation, inspections.
2. **Paid core**: per-language plural forms editor, extract hardcoded strings
   (`Text("Hello")` → `stringResource(Res.string.hello)`), escaping/length/unused/untranslated checks,
   XLIFF/CSV/Android import and export.
3. **Translation**: parallel, batched translation of only missing and stale strings, placeholders protected.
   No key needed: Apple on-device Translation (macOS 26.4+) and MyMemory. Bring your own key: DeepL, Google Cloud,
   Claude/OpenAI. Device Hub "Run in this locale".
4. **Launch**: Marketplace listing, screenshots, Freemium pricing.
