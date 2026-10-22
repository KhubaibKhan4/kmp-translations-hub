# Privacy Policy: KMP Translations Hub

_Effective October 22, 2026._

This policy covers the **KMP Translations Hub** plugin for JetBrains IDEs, published by Khubaib Khan.

## Summary

KMP Translations Hub does **not** collect, sell or share any personal data, and has no analytics, telemetry, crash
reporting or advertising. The plugin has no servers of its own. It connects to the internet in one case only: when
you run **Translate Changes** with an online translation service, it sends the strings being translated to **the
service you chose**, and nowhere else.

## What the plugin does on your computer

- **Your project's strings.** The plugin reads and edits the `composeResources/values…` XML files of the project you
  open, and reads its Kotlin files to find `Res.string…` uses (for navigation and the unused-key check). Nothing is
  uploaded, except as described under [Translation services](#translation-services).
- **`translations.lock.json`.** When strings are first machine translated, the plugin writes this file next to the
  `composeResources` folder. It holds short hashes of texts (not the texts themselves), and which service or "human"
  wrote each translation. It stays in your project; you decide whether to commit it.
- **Settings.** Your chosen service, the language of the default values folder, the Claude and OpenAI model names and
  the optional MyMemory email are stored in the IDE's settings on your computer (`kmp-translations-hub.xml`).
- **API keys.** DeepL, Google Cloud, Anthropic and OpenAI keys are stored in the IDE's password safe (on macOS the
  Keychain by default, or KeePass, as set in the IDE), never in a settings or project file.
- **Apple translation helper.** On macOS the plugin copies its small `translationshub-apple` program into the IDE's
  system folder and runs it to use Apple's on-device Translation framework.
- **Files you create.** Imports read, and exports write, only the files and folders you pick.

## Translation services

Strings are sent only when you start **Translate Changes**, only for the locales and strings it lists (missing or
outdated ones), and only to the one service selected in the dialog. Before sending, placeholders such as `%1$s`,
inline markup and escapes may be replaced by neutral tokens; they're restored when the translation comes back.

| Service | What is sent | Where | Its terms |
|---|---|---|---|
| Apple on-device (macOS) | Nothing leaves your Mac: Apple's Translation framework translates with languages already downloaded. | Your Mac | [Apple Privacy Policy](https://www.apple.com/legal/privacy/) |
| MyMemory (no key) | The text of each string, the language pair, and your email if you entered one in Settings. These are sent in the request address. | `api.mymemory.translated.net` | [MyMemory terms](https://mymemory.translated.net/terms-and-conditions) |
| DeepL (your key) | The strings' text, the language pair, and the strings' XML comments as context. | `api-free.deepl.com` or `api.deepl.com` | [DeepL Privacy Policy](https://www.deepl.com/privacy) |
| Google Cloud Translation (your key) | The strings' text and the language pair. | `translation.googleapis.com` | [Google Cloud Privacy Notice](https://cloud.google.com/terms/cloud-privacy-notice) |
| Claude (your key) | The strings' text, their key names and XML comments as context, and the language pair. | `api.anthropic.com` | [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy) |
| OpenAI (your key) | The strings' text, their key names and XML comments as context, and the language pair. | `api.openai.com` | [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/) |

Each service handles what it receives under its own terms, and with your own key, under your agreement with it. In
particular, **MyMemory's terms say it stores submitted text long term and uses it to improve its services**, so don't
send strings you consider confidential (unreleased product names, for example) through MyMemory; Apple on-device
translation keeps them on your Mac. The plugin never sends your API keys anywhere except to the service they belong
to.

## KMP Device Hub

"Run App in Selected Locale" passes the locale's language tag (for example `de`) to the KMP Device Hub plugin, if you
have it installed, on your computer. KMP Device Hub's own policy covers what it does with your devices.

## Licensing and purchases

If you start a trial or buy a Pro license, JetBrains handles it through JetBrains Marketplace and your JetBrains
Account, under [JetBrains' privacy policy](https://www.jetbrains.com/legal/docs/privacy/privacy/). The plugin only
asks the IDE whether a license is present and checks JetBrains' signature on it locally. It never sees your payment or
account details.

## Third parties

Apart from the translation service you choose, the plugin sends nothing to third parties. "Start Free Trial" and "Get
KMP Device Hub" open JetBrains' website in your browser.

## Children

The plugin is a developer tool and isn't directed at children.

## Changes

If this policy changes, the new version will be published with the plugin and on its JetBrains Marketplace page, with
a new effective date.

## Contact

Questions about this policy: contact the vendor (Khubaib Khan) through the plugin's JetBrains Marketplace page.
