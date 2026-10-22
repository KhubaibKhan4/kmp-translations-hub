<div align="center">

<img src="docs/banner.png" alt="KMP Translations Hub: every locale of your Compose Multiplatform app in one table" width="900">

# KMP Translations Hub

**Every locale of your Compose Multiplatform app in one table, and only what changed gets translated.**

For Kotlin Multiplatform and Compose Multiplatform developers who keep their strings in `composeResources`.

![Android Studio & IntelliJ IDEA 2025.1+](https://img.shields.io/badge/Android%20Studio%20%26%20IntelliJ-2025.1%2B-7F52FF)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-composeResources-1E88E5)
![Freemium](https://img.shields.io/badge/plan-Freemium-3DDC84)

</div>

## Install

Coming to JetBrains Marketplace. Until then, build the plugin and install it from disk:

```bash
./gradlew :idea-plugin:buildPlugin
```

Then in Android Studio or IntelliJ IDEA (2025.1 or newer): **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick
`idea-plugin/build/distributions/kmp-translations-hub-<version>.zip`, and restart. Open any
`composeResources/values…/strings.xml` and switch to the **Translations** tab at the bottom of the editor.

## Features

### Every key, every locale, one table

The **Translations** tab shows every key of the module against every locale: strings, plurals and string-arrays.
Missing translations and problems are marked in place; search, or filter to missing, problem or outdated keys. Edit a
cell and only that tag changes in the XML: comments, order and formatting stay as they were, and each edit is one Undo,
from the tab or the file. Add, rename and delete keys in every locale at once, or add a locale.

<img src="docs/screenshots/01-translations-table.png" alt="The Translations tab: keys against locales, missing cells, a placeholder problem in the side panel" width="900">

### The plural forms each language needs

The side panel edits plurals and arrays. Each locale gets a field for every plural form its language uses: Polish one,
few, many and other; Arabic six; Japanese just other. Forms a language needs but doesn't have are flagged.

<img src="docs/screenshots/02-plurals.png" alt="Polish plurals in the side panel: one, few, many and other" width="900">

### Translate only what changed

**Translate Changes** sends only what's missing or outdated, every locale at once, and skips everything already
translated and unchanged. Placeholders (`%1$s`, `%d`), inline markup and escapes are protected; a result that loses a
placeholder is dropped instead of saved. Translations a person wrote are never overwritten: when their default text
changes, they're flagged for review. See [how it works](#how-translating-only-what-changed-works).

<img src="docs/screenshots/03-translate-changes.png" alt="Missing translations filter next to the Translate Changes dialog" width="900">

### Problems where you write them

The same checks run in the XML editor: placeholders that don't match the default text, plural forms, arrays with the
wrong number of items, keys left behind in a locale, text identical to the default, unknown escapes, translations far
longer than the default, and keys no Kotlin code uses. Quick fixes add the missing plural forms or delete the leftover
key. ⌘/Ctrl+click `Res.string.hello` in Kotlin to jump to its XML; a gutter icon on each key finds its uses.

<img src="docs/screenshots/04-checks.png" alt="Warnings in values-de/strings.xml: a placeholder mismatch and an untranslated string" width="900">

### Extract hardcoded strings

Alt+Enter on text in Compose UI code moves it to the module's `strings.xml` and replaces it with
`stringResource(Res.string.…)`, with string templates turned into placeholders and the imports added: one Undo
reverts both files. **Code → Inspect Code** finds every hardcoded string in the project to extract them all.

<img src="docs/screenshots/05-extract-strings.png" alt="Before and after extracting two strings from a composable" width="900">

### Bring your own key, or none

| Service | Key | Notes |
|---|---|---|
| Apple on-device | None | macOS 26+. Offline; nothing leaves the Mac. Download the languages first in System Settings → General → Language & Region → Translation Languages. No Urdu. |
| MyMemory | None | About 5,000 characters a day per IP, ~10× with an optional email. One string per request: right for a few new strings. |
| DeepL | Yours | The free plan includes 500,000 characters a month. |
| Google Cloud Translation | Yours | v2 API with an API key. |
| Claude | Yours | Anthropic's official SDK; model in Settings (default `claude-opus-5-5`). Answers are schema-checked JSON. |
| OpenAI | Yours | Model in Settings (default `gpt-5-mini`). |

Keys are kept in the IDE's password safe. **Settings → Tools → KMP Translations Hub.**

<img src="docs/screenshots/06-services.png" alt="Settings: translation service, default language, API keys" width="900">

### Import, export and Run in this locale

Export CSV (all locales), XLIFF 1.2 or Android `strings.xml` for translators, and import them back: plural forms and
array items are their own entries (`cart_items#few`, `days[2]`), so any tool can handle them. With
[KMP Device Hub](https://plugins.jetbrains.com/plugin/35272-kmp-device-hub) installed, **Run App in Selected Locale**
relaunches your app in that column's language on an Android device or iOS simulator.

## Free and Pro

| Free | Pro (free trial through JetBrains Marketplace) |
|---|---|
| The Translations tab: strings, plurals, arrays, every locale | Translate Changes with Apple, MyMemory, DeepL, Google Cloud, Claude or OpenAI |
| Add, rename and delete keys; add locales | Plural forms per language, in the side panel and the checks |
| Missing translations, placeholders, array sizes, keys left behind | Unknown escapes, untranslated text, length, unused keys |
| `Res.string` navigation and usages | Extract hardcoded strings |
| | Import and export: CSV, XLIFF, `strings.xml` |
| | Run in this locale, with KMP Device Hub |

## How translating only what changed works

`translations.lock.json` sits next to each `composeResources` folder (outside what the resource compiler reads) and
is committed with it. For each translation it stores a hash of the default text it was made from, a hash of its own
text, and who wrote it (`deepl`, `apple`, `mymemory`, `claude`… or `human`):

| State | What Translate Changes does |
|---|---|
| Missing | Translates it |
| Default text changed, machine translation | Translates it again |
| Default text changed, written by a person | Flags it for review; never overwrites it |
| Unchanged | Skips it: no request |

A machine translation someone edited by hand counts as `human` from then on. The file is created the first time
something is machine translated, and the translations already there are recorded as `human` and current, so the
first run doesn't re-translate the app. Projects that never machine-translate get no extra file.

## Privacy

Strings go only to the translation service you pick, with their key names and XML comments as context. Apple
translation stays on the Mac. API keys stay in the IDE's password safe. The plugin sends nothing anywhere else.

## Development

| Module | What it is |
|---|---|
| `core` | Dependency-free engine: `values-…` qualifiers, the `strings.xml` model and parser, plural rules per language, checks, `translations.lock.json`, import/export formats and translation plans. No IDE APIs, so a CLI or CI check can reuse it. |
| `idea-plugin` | The Translations tab, PSI writer (keeps formatting, one Undo per edit), inspections and quick fixes, `Res.string` navigation, string extraction (Kotlin plugin, optional), translation services, Pro licensing. |
| `tools/apple-translate` | `translationshub-apple`, the Swift helper for Apple's Translation framework. `./build.sh` rebuilds the universal binary into the plugin's resources (needs Xcode with the macOS 26 SDK). |
| `tools/listing` | The README and Marketplace images. See below. |

```bash
./gradlew test                       # core + IDE tests
./gradlew :idea-plugin:runIde -Ptranslationshub.openProject=/path/to/cmp/project
./gradlew :idea-plugin:verifyPlugin  # against the IDE in local.properties
```

Set the IDE to build against in the git-ignored `local.properties`:
`translationshub.ideaLocalPath=/Applications/Android Studio.app`.
`./gradlew :core:test -Ptranslationshub.realProject=/path/to/cmp/project` also reads a real project and prints what it
found in each `composeResources` folder.

**Images.** The screenshots and banner are made from the plugin's real panels, rendered by a test with a fictional
shop app:

```bash
./gradlew :idea-plugin:test --tests '*ListingRender*'
python3 tools/listing/make_listing.py idea-plugin/build/listing docs
```

**Performance** (2,000 keys × 15 locales): the engine reads them in 30 ms and checks in 89 ms; the Translations tab
opens in 151 ms, and an edit with re-read takes 279 ms.

Release steps are in [RELEASE.md](RELEASE.md).
