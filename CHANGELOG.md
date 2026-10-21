# Changelog

## Unreleased

- A Translations tab on every `composeResources/values…` XML file: all keys of the module against all locales, with search and filters for missing, problem and outdated translations.
- Edit strings in the table, and plurals (only the forms each language uses) and string-arrays in the side panel. Edits keep your files' formatting and comments; each is one Undo, from the tab too.
- Add, rename and delete keys in every locale at once, and add a locale.
- Checks for missing translations, placeholders that don't match the default text, plural forms a language needs, array sizes and keys left behind, in the tab and as warnings in the XML editor, with quick fixes.
- ⌘/Ctrl+click `Res.string.x` in Kotlin to jump to its XML; a gutter icon on each key finds the code that uses it.
