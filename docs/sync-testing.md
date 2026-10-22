# Testing Sync by hand

The sandbox IDE opens a sample project with `composeApp/src/commonMain/composeResources`: English defaults (a
placeholder, an apostrophe, a plural, an array, a comment, an untranslatable key) and two German translations.

Start it again any time with:

```bash
./gradlew :idea-plugin:runIde -Ptranslationshub.openProject=/path/to/sync-sample
```

## Without an account (now)

1. Open `composeApp/src/commonMain/composeResources/values/strings.xml` and switch to the **Translations** tab.
2. Click **Sync with Translation Platform…** (the refresh icon). Expect: a message asking you to set up a platform, with **Open Settings**.
3. In **Settings → Tools → KMP Translations Hub → Translation Platform**, pick POEditor, enter project ID `1` and token `wrong`.
4. Sync again, keep all three boxes ticked. Expect: an error notification like "POEditor: Invalid API token", and no changes to the files.
5. Do the same with Crowdin and Lokalise. Expect: an error with each platform's own message (Lokalise checks the
   project ID's format first, so `1` gives "Invalid `project_id`").

## With a test project on each platform

Per platform: create a project with **English** as the source language and **German** and **French** as targets, then
put its project ID and your token in Settings.

| # | Do | Expect |
|---|---|---|
| 1 | Sync, all boxes ticked | Notification: 7 default strings uploaded as `composeApp-commonMain-strings.xml`; `de: 2 uploaded` |
| 2 | Look at the project on the platform | 7 keys, no `app_name`; the comment shows as context; `cart_items` is a plural, `delivery_days` an array; `%1$s` and the apostrophe look right; German has `tab_home` and `greeting` |
| 3 | On the platform: translate `tab_orders` to German, and two strings to French. Sync | `de: 1 downloaded`, `fr: 2 downloaded`; a new **fr** column appears; the German text matches exactly |
| 4 | Sync again, change nothing | "Everything is already in sync." |
| 5 | In the IDE, change German `tab_home`. Sync | `de: 1 uploaded`; the platform shows the new text |
| 6 | Change German `greeting` both in the IDE and on the platform, differently. Sync | Warning, then the conflict dialog listing `greeting`. Try **Keep Mine**, sync again: the platform gets yours |
| 7 | Add a key in the IDE (Add Key). Sync with only "Upload the default strings" | The key appears on the platform |
| 8 | On the platform, translate `cart_items` into German (one and other). Sync | Both plural forms arrive; no plural warnings in the tab |
| 9 | Undo (⌘Z) in the Translations tab after a sync | The last locale's sync is undone in one step |
| 10 | Check `translations.lock.json` next to `composeResources` | Entries have a `"synced"` hash |

Things worth noting as you go: anything that arrives with changed escaping (`\'`, `\n`), placeholders written
differently (`%s` instead of `%1$s`), or plural forms in the wrong place.

POEditor allows one upload every 20 seconds, so step 1 there takes about 20 seconds longer.
