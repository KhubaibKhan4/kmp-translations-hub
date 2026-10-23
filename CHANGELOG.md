# Changelog

## 0.2.0

- Pro: Sync with Crowdin, Lokalise and POEditor, from the Translations tab. It uploads the default strings, downloads
  your translators' work into `composeResources`, and uploads translations made in the IDE that the platform doesn't
  have yet. Plurals, arrays, placeholders and escapes go both ways as Compose expects them.
- Nothing is overwritten silently: `translations.lock.json` remembers each translation at the last sync. A change on
  one side wins; a change on both sides is kept as it is in the IDE until you pick the platform's or your own.
- Set it up in Settings → Tools → KMP Translations Hub → Translation Platform, with a project ID and token for each
  platform. Tokens are kept in the IDE's password safe.
- Imported `strings.xml` files written in Android's quoted style (`"It's here"`) are read the way Compose shows them.
- New translations, from Translate Changes, Import or Sync, go next to the keys they follow in the default file,
  instead of at the end.
- After the third Translate Changes that translates something, a one-time notification asks for a rating on JetBrains
  Marketplace. It never shows again.

## 0.1.3

- No deprecated API in IntelliJ IDEA 2026.3: "Open in the Translations tab" opens the file the way every supported version supports.

## 0.1.2

- Compatible with every supported IDE, 2025.1 to 2026.3, with no compatibility warnings: the plugin now declares Kotlin K2 mode support where the Plugin Verifier expects it, and depends on the bundled Kotlin plugin.

## 0.1.1

- Works in IntelliJ IDEA and Android Studio 2025.1: "Start Free Trial" and "Run App in Selected Locale" no longer fail there with a missing-method error.
- Translate Changes and Add Locales no longer use APIs that 2025.1 marks experimental or newer IDEs deprecate.
- Settings say next to MyMemory that it keeps the text it's sent, so you can pick Apple or your own key for confidential strings.

## 0.1.0

- A Translations tab on every `composeResources/values…` XML file: all keys of the module against all locales, with search and filters for missing, problem and outdated translations.
- Edit strings in the table, and plurals (only the forms each language uses) and string-arrays in the side panel. Edits keep your files' formatting and comments; each is one Undo, from the tab too.
- Add, rename and delete keys in every locale at once. Add Locales takes any number at once: tick them in a searchable list (or "Select the top 10"), or type tags such as `sr-Latn, es-MX`; one Undo removes them all.
- Checks for missing translations, placeholders that don't match the default text, plural forms a language needs, array sizes and keys left behind, in the tab and as warnings in the XML editor, with quick fixes.
- ⌘/Ctrl+click `Res.string.x` in Kotlin to jump to its XML; a gutter icon on each key finds the code that uses it.
- Pro: Translate Changes sends only missing and outdated strings (tracked in `translations.lock.json`), all locales at once, with placeholders, markup and escapes protected. Apple on-device translation (macOS 26+) and MyMemory need no key; DeepL, Google Cloud, Claude and OpenAI use your own key, kept in the IDE's password safe.
- Pro: the plural forms each language needs, and checks for unknown escapes, untranslated text, translations much longer than the default, and keys no Kotlin code uses.
- Pro: extract hardcoded strings in Compose UI code to `composeResources` with Alt+Enter, or all at once with Inspect Code.
- Pro: import and export CSV, XLIFF 1.2 and Android `strings.xml`.
- Pro: Run App in Selected Locale, on the device selected in KMP Device Hub.
- Placeholders survive MyMemory: it's sent `%1$s` as is (its memory keeps real placeholders but dropped stand-in tokens), and any string a service returns with a broken placeholder is retried once the other way before it's reported.
