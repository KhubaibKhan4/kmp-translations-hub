# Releasing KMP Translations Hub

## Your steps (accounts and decisions only you can make)

- [ ] **GitHub repo** `KhubaibKhan4/kmp-translations-hub` (the vendor URL in `gradle.properties` points there), then push.
- [ ] **Paid plan**: apply in JetBrains Marketplace → vendor profile → Sales, as Freemium. They assign the product code.
- [ ] **Price and trial length.**
- [ ] **Release date**: `translationshub.releaseDate` is a placeholder (20261201). Set it to the launch date (YYYYMMDD).
- [ ] Secrets for CI with `gh secret set` (it prompts): `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- [ ] To try Apple translation on your Mac: download the languages in System Settings → General → Language & Region →
      Translation Languages.
- [ ] Merge KMP Device Hub's `run-in-locale` branch (the hidden `KmpDeviceHub.RunInLocale` action) and ship it in a
      Device Hub release, so "Run App in Selected Locale" works.

## Build steps

- [ ] Screenshots: the Translations tab on a real project (KhataPay), the side panel with Polish plurals, Translate
      Changes with its progress, the inspection in `strings.xml`, Alt+Enter "Extract to composeResources".
- [ ] A 60–90 s demo video.
- [ ] `./gradlew build :idea-plugin:verifyPlugin -Ptranslationshub.verifyRecommended=true`
- [ ] Free early-access build: `./gradlew :idea-plugin:buildPlugin` (no product code: every Pro feature on).
- [ ] Paid build once the code is assigned: `./gradlew :idea-plugin:buildPlugin -Ptranslationshub.productCode=<CODE>`.
- [ ] Upload `idea-plugin/build/distributions/kmp-translations-hub-<version>.zip`, tag `v<version>`, GitHub release.

## Marketplace listing

**Name:** KMP Translations Hub

**Short description:** Every translation of your Compose Multiplatform app in one table, with machine translation
that only sends what changed.

**Tags:** Kotlin Multiplatform, Compose Multiplatform, Localization, Translation, i18n, Android, iOS

**Category:** Tools Integration

**Description:** generated from `plugin.xml`. Lead with:

> Stop hand-editing a dozen `strings.xml` files. KMP Translations Hub puts every key of your module against every
> locale in one table, checks placeholders and plural forms the way each language needs them, translates only
> what's missing or changed, with Apple on-device translation and no key at all if you like, and runs your app in
> the new language on a device with KMP Device Hub.

**Against the free "Compose Multiplatform Translations" plugin (33682):** it has a table, missing-key and
placeholder checks and navigation; so does our free tier. Ours adds plural forms per language, translation that
only sends what changed, string extraction, import/export and Device Hub.

**Privacy note for the listing:** strings are sent only to the translation service the user picks, with their key
names and XML comments as context; Apple translation stays on the Mac; API keys stay in the IDE's password safe.
