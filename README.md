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
| `idea-plugin` | The **Translations** editor tab, PSI writer (keeps formatting, one Undo per edit), inspections and quick fixes, `Res.string` navigation, string extraction (Kotlin plugin, optional), import/export, translation providers, Pro licensing. |
| `tools/apple-translate` | `translationshub-apple`, the Swift helper for Apple's on-device Translation framework. `./build.sh` rebuilds the universal binary into the plugin's resources (needs Xcode with the macOS 26 SDK). |

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

## Translation services

| Service | Key | Notes |
|---|---|---|
| Apple on-device | None | macOS 26+. Offline; nothing leaves the Mac. Download languages first in System Settings → General → Language & Region → Translation Languages. No Urdu. |
| MyMemory | None | About 5,000 characters a day per IP, ~10× with an optional email. One string per request: fine for a few new strings. |
| DeepL | Yours | Free plan: 500,000 characters a month (`:fx` keys use the free endpoint). |
| Google Cloud Translation | Yours | v2 API with an API key. |
| Claude | Yours | Anthropic's official Java SDK. Model set in Settings (default `claude-opus-5-5`); answers come back as schema-checked JSON, and a declined request goes to Anthropic's recommended fallback model. The SDK makes the plugin about 40 MB. |
| OpenAI | Yours | Model set in Settings (default `gpt-5-mini`). |

Placeholders (`%1$s`, `%d`), inline markup and escapes are swapped for tokens before sending and put back after; a
result whose placeholders don't match the default text is dropped, not saved. Every plural form the target language
needs is sent (from the source's same form, or `other`). Settings → Tools → KMP Translations Hub.

## Phase 0 results (2026-10-20)

- **Real project** (KhataPay: 8 `composeResources` folders, English + Urdu): all 381 keys read, the same counts as
  the files, 0 problems, in 98 ms.
- **2,000 keys × 15 locales** (1,800 strings, 150 plurals, 50 arrays): engine reads it in 30 ms, checks in 89 ms;
  the Translations tab opens in 151 ms, paints in 3 ms a frame, and an edit with re-read takes 279 ms.
- **Write-back**: edits change only the tag they touch (comments, blank lines, odd spacing and attribute layout
  survive); new keys land after the key they follow in the source; missing plural forms go in CLDR order; inline
  markup and Compose escapes pass through. Several edits are one Undo that restores the file byte for byte, and
  Undo works from the Translations tab too.
- **Plugin Verifier**: compatible with Android Studio 2026.2. One warning remains: the verifier wants
  `supportsKotlinPluginMode` in the main `plugin.xml`, but the Kotlin plugin is an optional dependency, so it's in
  `kotlin-features.xml`, which loads in K2 mode (the extraction tests run there).

Found on the way: `XmlTagValue.setEscapedText` drops inline markup, and replacing a tag through the PSI runs the
formatter over it, so text with markup is written into the document directly.

## Status (2026-10-21)

Phases 0–3 are built and tested (78 tests). What's left is in [RELEASE.md](RELEASE.md): accounts, the paid-plan
product code, screenshots and the listing.
