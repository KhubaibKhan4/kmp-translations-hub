# Changelog

## Unreleased

- A Translations tab on every `composeResources/values…` XML file: all keys of the module against all locales, with search and filters for missing, problem and outdated translations.
- Edit strings in the table, and plurals (only the forms each language uses) and string-arrays in the side panel. Edits keep your files' formatting and comments; each is one Undo, from the tab too.
- Add, rename and delete keys in every locale at once, and add a locale.
- Checks for missing translations, placeholders that don't match the default text, plural forms a language needs, array sizes and keys left behind, in the tab and as warnings in the XML editor, with quick fixes.
- ⌘/Ctrl+click `Res.string.x` in Kotlin to jump to its XML; a gutter icon on each key finds the code that uses it.
- Pro: Translate Changes sends only missing and outdated strings (tracked in `translations.lock.json`), all locales at once, with placeholders, markup and escapes protected. Apple on-device translation (macOS 26+) and MyMemory need no key; DeepL, Google Cloud, Claude and OpenAI use your own key, kept in the IDE's password safe.
- Pro: the plural forms each language needs, and checks for unknown escapes, untranslated text, translations much longer than the default, and keys no Kotlin code uses.
- Pro: extract hardcoded strings in Compose UI code to `composeResources` with Alt+Enter, or all at once with Inspect Code.
- Pro: import and export CSV, XLIFF 1.2 and Android `strings.xml`.
- Pro: Run App in Selected Locale, on the device selected in KMP Device Hub.
- Placeholders survive MyMemory: it's sent `%1$s` as is (its memory keeps real placeholders but dropped stand-in tokens), and any string a service returns with a broken placeholder is retried once the other way before it's reported.
