# Pingolin Note for Android

The Android client for [Pingolin Note](https://pingolin.com), end-to-end
encrypted notes with markdown, attachments, sharing and real-time collaborative
editing. Note content is encrypted on the device with a per-note content key;
the backend stores ciphertext and holds no key that could open it. That extends
to live collaboration — the relay forwards CRDT frames it cannot parse.

The cryptography and the CRDT are not implemented here. Both live in a shared
Rust SDK that the iOS client uses too, and reach this app as prebuilt native
libraries.

## Repository layout

| Path | What it is |
| --- | --- |
| `app/` | The application: UI, storage, networking, background sync |
| `library/` | **Generated.** UniFFI Kotlin bindings and the two prebuilt `.so` files — see [The Rust SDK](#the-rust-sdk-in-library) |
| `licenses/` | Inputs to the licence inventory — see [Third-party licences](#third-party-licences) |
| `THIRD_PARTY_LICENSES.md` | **Generated.** Attribution for every bundled component |

The UI is Fragments and Views with view binding, not Compose. Dependency
injection is Hilt, and persistence is Room over SQLCipher.

## Prerequisites

- **JDK 17 or newer.** `app/build.gradle.kts` compiles to JVM 17
  (`jvmTarget.set(JvmTarget.JVM_17)`); this repo is built with JDK 21.
- **Android SDK** with the API 36 platform. `compileSdk = 36`, `targetSdk = 36`,
  `minSdk = 26` (Android 8.0).
- **Gradle** — do not install it. Use the wrapper (`./gradlew`), which pins
  9.6.1.
- **No NDK.** Neither module runs `externalNativeBuild`; the `.so` files in
  `library/` are prebuilt and committed. You only need an NDK if you rebuild the
  Rust SDK itself, which happens in a different repository.

Point Gradle at your SDK with a `local.properties` in the repo root:

```properties
sdk.dir=/path/to/android-sdk
```

`local.properties` is gitignored, as is the whole `build/` tree.

### `google-services.json` — needed for `gms` builds only

`com.google.gms.google-services` aborts the build with `File google-services.json
is missing.` when the file is absent, and the file carries project-specific
Firebase API keys, so it is gitignored (`.gitignore` lines 15-16) and **is not in
this repository**.

A fresh clone still builds: the
plugin is applied conditionally (bottom of `app/build.gradle.kts`), so a `libre`
build needs no Firebase file whatsoever —

```bash
./gradlew :app:assembleProdLibreRelease   # no google-services.json required
```

— and that is the build to reach for if you just want to compile the app.

For a `gms` build you still need one. Get it from a Firebase project of your own
(Firebase console → Project settings → Your apps → Android) and register the
application IDs you intend to build: `com.pingolindev.note.dev` and
`com.pingolindev.note`.

The plugin is applied unless every Gradle task you named is a `libre` one, which
is deliberately the safe default: a `gms` APK assembled with the plugin skipped
still builds and installs, it simply has no `google_app_id` resource and never
receives a push. So `./gradlew build`, `assemble` and an IDE sync all still want
the file. When a `gms` and a `libre` task share one invocation, the plugin is
applied and its per-variant task is switched off for the libre variants, so no
Firebase identifiers reach the libre APK either way.

## Build

```bash
./gradlew :app:assembleDevGmsDebug      # what you want for local work
```

There are **two flavour dimensions**, `environment` and `distribution`, crossed
with two build types — so eight variants, named environment-first:
`devGmsDebug`, `prodLibreRelease`, and so on. **There is no staging flavour.**

`environment` picks the backend:

| Flavour | Application ID | Backend |
| --- | --- | --- |
| `dev` | `com.pingolindev.note.dev` | `*.pingolindev.com` |
| `prod` | `com.pingolindev.note` | `*.pingolin.com` |

Neither flavour pins TLS certificates. The hosts sit behind Cloudflare
Universal SSL, which chooses and rotates the issuing CA, so a pin would break
the app on the next rotation; the app relies on system trust instead (see
`di/AppModule.kt`).

`distribution` picks how much Google ships inside the APK:

| Flavour | Application ID suffix | Push | Registration attestation |
| --- | --- | --- | --- |
| `gms` (default) | none | FCM, or UnifiedPush when a distributor is installed | Play Integrity, else hardware attestation, else Turnstile |
| `libre` | `.libre` | UnifiedPush or the Direct Connection socket only | hardware attestation, else Turnstile |

A `libre` build resolves none of `com.google.firebase`,
`com.google.android.gms` or `com.google.android.play` — check for yourself with

```bash
./gradlew :app:dependencies --configuration prodLibreReleaseRuntimeClasspath
```

Nothing about the runtime was invented for it: `AttestationManager` already fell
through to hardware attestation and Turnstile on a de-Googled device, and
`PushNotificationManager` already preferred UnifiedPush whenever a distributor
was installed. The flavour decides which `PlayIntegrityManager` (`src/gms` or
`src/libre`) and which `FcmTokenProvider` implementation is compiled in; the
libre pair reports the capability as unavailable. `MainSourceSetIsGoogleFreeTest`
fails the build if a Google reference reappears in `src/main`.

The `.libre` application-id suffix lets both APKs sit on one device. It also
means a libre build's application ID is not in the `assetlinks.json` served by
the auth host, so the https verify-email App Link will not autoVerify there
until it is added.

`release` is minified with R8. A release build without signing material
configured produces an unsigned APK rather than failing — see the comment above
`signingConfigs` in `app/build.gradle.kts`.

Every variant is **`arm64-v8a` only** (`abiFilters += listOf("arm64-v8a")` in
`defaultConfig`), so the app does not run natively on an x86_64 emulator. Use an
arm64 AVD or a physical device.

`version.properties` in the repo root is written by the release build. When it is absent the
build falls back to version `1.0.0` / code `1`, so a bare clone still assembles.

If you are building on a small machine, note that `gradle.properties` sizes the
heap for a large build host (`-Xmx4096m` plus a 3 GB Kotlin daemon).

## Test

```bash
./gradlew :app:testDevGmsDebugUnitTest
./gradlew :app:testDevLibreDebugUnitTest
```

Those are the full unit suites. It
is JVM-only: JUnit 4 with MockK, Mockito and Robolectric. Robolectric tests pin
`@Config(sdk = [35], ...)` because Robolectric 4.14.1 ships no SDK 36 image.

Two things worth knowing before you spend time on tests:

- `app/src/androidTest/` (Espresso, UI Automator, Hilt instrumentation) is **not
  part of those suites** and needs a device or emulator. Put new coverage in `app/src/test/`
  with Robolectric, where it will actually run.
- Gradle reporting `UP-TO-DATE` is not a test result. Pass `--rerun` when you
  need proof a suite executed, and read the JUnit XML under
  `app/build/test-results/`.

## The Rust SDK in `library/`

Everything under `library/src/main/java/uniffi/` and
`library/src/main/jniLibs/<abi>/` is **generated output, committed as a binary
mirror**. Do not hand-edit it: the next regeneration overwrites it, and the
Kotlin bindings must stay in lockstep with the `.so` files they describe.

This app needs **both** native libraries. They are separate crates with separate
binding namespaces, and they are loaded by different routes:
`NoteApplication.onCreate()` calls `System.loadLibrary("pingolin_mobile")`
directly, so a missing `libpingolin_mobile.so` kills the app at launch, whereas
`libnote_collab_sdk.so` is loaded lazily by JNA the first time the collaboration
bindings are touched.

| Library | Crate | Kotlin namespace | Provides |
| --- | --- | --- | --- |
| `libpingolin_mobile.so` | `mobile-sdk` | `uniffi.pingolin_mobile` | E2EE primitives, shared with Pingolin Track |
| `libnote_collab_sdk.so` | `note-collab-sdk` | `uniffi.collab` | The collaborative-editing CRDT and its key wrapping |

The source of both is the [`mobile-sdk`](https://github.com/pingolindev/mobile-sdk-public)
repository, whose `build-android.sh` cross-compiles the crates for each Android
ABI and runs `uniffi-bindgen`. That script expects a sibling checkout layout,
because the crates depend on each other by relative path:

```
<parent>/
  mobile-sdk/          # the SDK source and build-android.sh
  pingolin-crypto/     # depended on by relative path from mobile-sdk
  note-android-public/  # this repository
```

Clone the two siblings under exactly those names; the relative paths depend on them:

```sh
git clone https://github.com/pingolindev/mobile-sdk-public.git mobile-sdk
git clone https://github.com/pingolindev/pingolin-crypto-public.git pingolin-crypto
```

To refresh the bindings, run `./build-android.sh` in `mobile-sdk` (it needs Rust
with the Android targets, an NDK r26+, and `protoc`; the script's `--help` lists
the options and it checks each prerequisite before starting), then copy its
output over the committed copies:

```
build/android/<abi>/*.so       -> note-android/library/src/main/jniLibs/<abi>/
build/android/kotlin/*/uniffi/ -> note-android/library/src/main/java/uniffi/
```

Diffing a fresh build against the committed files is how you verify the shipped
binaries match the SDK source. Rust and the NDK do not promise bit-identical
output across toolchain versions, so a mismatch means "built with a different
toolchain", not necessarily "built from different source".

## Third-party licences

`THIRD_PARTY_LICENSES.md` lists every bundled component with its licence, and
reproduces the licence texts in full. The same inventory ships inside the app,
reachable from **Settings → About → Open source licenses**, rendered offline from
`app/src/main/assets/open_source_licenses.html`.

That in-app screen is a licence obligation rather than a nicety. SQLCipher's
Community Edition licence requires the licence grants to be reproduced in the
application interface or materials, in a user-accessible location — see the
class documentation on `LicensesFragment` — and the BSD terms in
`licenses/texts/SQLCipher-Community-Edition.txt` require its copyright notice
to travel with any binary redistribution. A link to a web page satisfies
neither, so the notices are packaged as an offline asset and rendered in a
WebView with JavaScript disabled.

Both files are generated and committed. Regenerate them after **any** dependency
change and commit the result:

```bash
./gradlew :app:generateThirdPartyLicenses
```

That merges three sources, because no single one covers what the APK ships:

| Source | Covers | Produced by |
| --- | --- | --- |
| The resolved `prodGmsRelease` runtime classpath | JVM and Android dependencies | `com.jaredsburrows.license`, reading each POM's `<licenses>` block |
| `licenses/native-licenses.json` | Rust crates inside the committed `.so` files | `licenses/collect-native-licenses.py` |
| `licenses/supplement.json` | Corrections, licence elections, embedded and proprietary components | Maintained by hand |

The native inventory is the part a classpath scan cannot reach: those crates have
no POM and are on no classpath, but they are compiled into binaries this
repository commits and packages. Regenerating it needs a `mobile-sdk` checkout
and `cargo`, which is why it is a committed artifact rather than a build step:

```bash
./licenses/collect-native-licenses.py > licenses/native-licenses.json
# or, if mobile-sdk is not a sibling:
./licenses/collect-native-licenses.py --mobile-sdk /path/to/mobile-sdk > licenses/native-licenses.json
```

`licenses/supplement.json` records what published metadata cannot express — a
dependency whose POM declares a licence URL but no name, the licence Pingolin
elects from a dual-licensed offer, and projects statically linked inside another
dependency's own native library. The Gradle task **fails** if a supplement entry
no longer matches anything on the classpath, so a stale correction surfaces
instead of quietly misdescribing what we ship. It also fails if any dependency's
POM states no usable licence, naming the offenders and telling you to add them to
`licenses/supplement.json` — so a new dependency cannot be published
unattributed. SQLCipher is why that check exists rather than being theoretical:
its POM carries a licence element with a URL and no name, which reads as a
licence named `""` rather than as a missing one.

Nothing in a normal build regenerates these files, so a stale inventory would
otherwise be invisible. `OpenSourceLicensesAssetTest` in the unit test suite
guards against the asset going missing or losing its key entries.

## Contributing and security

This repository is published as one snapshot per release: each commit is a
shipped version, tagged with it, and the tree at a tag is the source that
version was built from. Development happens elsewhere, so this repository
takes no pull requests or issues.

Report security problems by email to **security@mail.pingolin.com**, not in
public.

## Licence

Pingolin Note is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

The Pingolin name and logos are trademarks of Pingolin LLC and are not covered
by the licence. A modified version you distribute must not use them.

The licences of the third-party components this app bundles are separate; they
are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
