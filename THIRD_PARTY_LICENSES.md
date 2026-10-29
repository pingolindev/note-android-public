# Third-party licences

Pingolin Note for Android is licensed under the GNU General Public License,
version 3 or later (see [LICENSE](LICENSE)). The source code of every release
is at https://github.com/pingolindev/note-android-public. The web editor it
bundles is licensed under the GNU General Public License, version 3 or later,
too; its source is at https://github.com/pingolindev/note-web-public.
It links and redistributes the third-party components listed below, each under
its own licence.

**This file is generated — do not edit it by hand.** Run:

```
./gradlew :app:generateThirdPartyLicenses
```

| Source | Covers | Produced by |
|---|---|---|
| `prodGmsRelease` runtime classpath | 153 JVM/Android dependencies | `com.jaredsburrows.license`, reading each POM's `<licenses>` block |
| `licenses/native-licenses.json` | 284 Rust crates | `licenses/collect-native-licenses.py` |
| `licenses/supplement.json` | corrections, elections, embedded, vendored and proprietary components | maintained by hand |

## Why the native crates are listed here

The prebuilt `.so` files under `library/src/main/jniLibs/` are committed to this
repository and packaged into the APK. Every Rust crate compiled into them is
therefore redistributed by this project, and its notice belongs here rather than
only in the SDK repository the binaries were built from. A licence scan of the
JVM classpath alone would miss all 284 of them.

## Licence elections

Where a dependency offers a choice of licences, this is the one we take.

### `net.java.dev.jna:jna` — Apache-2.0

Offered under LGPL-2.1-or-later or Apache-2.0. **Elected: Apache-2.0.**

JNA has been dual-licensed since version 4.0: a recipient may use it under either the LGPL 2.1 (or later) or the Apache License 2.0. Pingolin Note elects Apache-2.0, so no LGPL obligation attaches to this distribution. JNA is a genuine runtime dependency, not just a test one - the UniFFI-generated Kotlin bindings call the Rust .so through it, and it is declared as `implementation("net.java.dev.jna:jna:5.18.1@aar")` in library/build.gradle.kts.

## Corrections to published metadata

Dependencies whose published POM does not state a usable licence. Each value
below was established from the project's own licence text, not from a
third-party licence database.

### `net.zetetic:sqlcipher-android` — BSD-3-Clause

The published POM carries a <licenses> entry with a URL but an EMPTY <name>, so automated tooling reports no licence for it. SQLCipher Community Edition is BSD-3-Clause, copyright ZETETIC LLC. It embeds SQLite, which its authors released into the public domain.

## Components embedded in other components

Upstream projects statically linked inside a dependency's own native library.
They are not separate artifacts, so no classpath or crate scan reaches them,
but their code ships in the APK.

| Component | Version | Licence | Inside |
|---|---|---|---|
| SQLite | — | blessing | net.zetetic:sqlcipher-android (libsqlcipher.so) |
| OpenSSL | 3.0.16 | Apache-2.0 | net.zetetic:sqlcipher-android (libsqlcipher.so) |

**SQLite.** SQLCipher is a fork of SQLite. Its authors dedicated it to the public domain: 'The author disclaims copyright to this source code.'

**OpenSSL.** SQLCipher links OpenSSL statically for its cryptographic primitives; the exact build is identified by the version banner inside the shipped binary ('OpenSSL 3.0.16 11 Feb 2025'). OpenSSL 3.x is Apache-2.0.

## Components that are not open source

These ship in the APK but carry no open-source grant. They are redistributed
under their own terms, linked below.

| Component | Terms |
|---|---|
| `com.google.android.gms` | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| `com.google.android.play:core-common` | [Play Core Software Development Kit Terms of Service](https://developer.android.com/guide/playcore/license) |
| `com.google.android.play:integrity` | [Play Integrity API Terms of Service](https://developer.android.com/google/play/integrity/overview#tos) |
| `com.google.firebase:firebase-iid-interop` | [Android Software Development Kit License](https://developer.android.com/studio/terms) |
| `com.google.firebase:firebase-measurement-connector` | [Android Software Development Kit License](https://developer.android.com/studio/terms) |

## JVM and Android dependencies (153)

Resolved from the `prodGmsRelease` runtime classpath.

| Component | Version | Licence | Notes |
|---|---|---|---|
| [androidx.activity:activity-ktx](https://developer.android.com/jetpack/androidx/releases/activity#1.11.0) | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.activity:activity](https://developer.android.com/jetpack/androidx/releases/activity#1.11.0) | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.annotation:annotation-experimental](https://developer.android.com/jetpack/androidx/releases/annotation#1.5.0) | 1.5.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.annotation:annotation](https://developer.android.com/jetpack/androidx/releases/annotation#1.10.0) | 1.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.appcompat:appcompat-resources](https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1) | 1.7.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.appcompat:appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1) | 1.7.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.arch.core:core-common](https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0) | 2.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.arch.core:core-runtime](https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0) | 2.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.biometric:biometric](https://developer.android.com/jetpack/androidx/releases/biometric#1.1.0) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.cardview:cardview](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.collection:collection-ktx](https://developer.android.com/jetpack/androidx/releases/collection#1.5.0) | 1.5.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.collection:collection](https://developer.android.com/jetpack/androidx/releases/collection#1.5.0) | 1.5.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.concurrent:concurrent-futures-ktx](https://developer.android.com/topic/libraries/architecture/index.html) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.concurrent:concurrent-futures](https://developer.android.com/topic/libraries/architecture/index.html) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.constraintlayout:constraintlayout-core](https://developer.android.com/jetpack/androidx/releases/constraintlayout#1.1.1) | 1.1.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.constraintlayout:constraintlayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout#2.2.1) | 2.2.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.coordinatorlayout:coordinatorlayout](https://developer.android.com/jetpack/androidx) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx/releases/core#1.17.0) | 1.17.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.core:core-viewtree](https://developer.android.com/jetpack/androidx/releases/core#1.0.0) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.core:core](https://developer.android.com/jetpack/androidx/releases/core#1.17.0) | 1.17.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.cursoradapter:cursoradapter](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.customview:customview-poolingcontainer](https://developer.android.com/jetpack/androidx/releases/customview#1.0.0) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.customview:customview](https://developer.android.com/jetpack/androidx) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| androidx.databinding:viewbinding | 9.3.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore-core-okio](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore-core](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore-preferences-core](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore-preferences-external-protobuf](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) |  |
| [androidx.datastore:datastore-preferences-proto](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore-preferences](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.datastore:datastore](https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7) | 1.1.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.documentfile:documentfile](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.drawerlayout:drawerlayout](https://developer.android.com/jetpack/androidx) | 1.1.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.dynamicanimation:dynamicanimation](https://developer.android.com/jetpack/androidx/releases/dynamicanimation#1.1.0) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.emoji2:emoji2-views-helper](https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0) | 1.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.emoji2:emoji2](https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0) | 1.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.exifinterface:exifinterface](https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2) | 1.4.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.fragment:fragment-ktx](https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9) | 1.8.9 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.fragment:fragment](https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9) | 1.8.9 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.graphics:graphics-shapes](https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1) | 1.0.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.hilt:hilt-common](https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0) | 1.3.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.hilt:hilt-work](https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0) | 1.3.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.interpolator:interpolator](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.legacy:legacy-support-core-utils](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-common](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-livedata-core-ktx](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-livedata-core](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-livedata-ktx](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-livedata](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-process](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-runtime-ktx](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-runtime](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-service](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-viewmodel-ktx](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-viewmodel-savedstate](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.lifecycle:lifecycle-viewmodel](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.10.0) | 2.10.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.loader:loader](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.localbroadcastmanager:localbroadcastmanager](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-common](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-fragment-ktx](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-fragment](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-runtime](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-ui-ktx](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.navigation:navigation-ui](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.7) | 2.9.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.print:print](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.profileinstaller:profileinstaller](https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.1) | 1.4.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.recyclerview:recyclerview](https://developer.android.com/jetpack/androidx/releases/recyclerview#1.4.0) | 1.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.resourceinspection:resourceinspection-annotation](https://developer.android.com/jetpack/androidx/releases/resourceinspection#1.0.1) | 1.0.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.room:room-common](https://developer.android.com/jetpack/androidx/releases/room#2.8.4) | 2.8.4 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.room:room-ktx](https://developer.android.com/jetpack/androidx/releases/room#2.8.4) | 2.8.4 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.room:room-runtime](https://developer.android.com/jetpack/androidx/releases/room#2.8.4) | 2.8.4 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.savedstate:savedstate-ktx](https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0) | 1.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.savedstate:savedstate](https://developer.android.com/jetpack/androidx/releases/savedstate#1.4.0) | 1.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.security:security-crypto](https://developer.android.com/jetpack/androidx/releases/security#1.1.0) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.slidingpanelayout:slidingpanelayout](https://developer.android.com/jetpack/androidx/releases/slidingpanelayout#1.2.0) | 1.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.sqlite:sqlite-framework](https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2) | 2.6.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.sqlite:sqlite-ktx](https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2) | 2.6.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.sqlite:sqlite](https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2) | 2.6.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.startup:startup-runtime](https://developer.android.com/jetpack/androidx/releases/startup#1.1.1) | 1.1.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.startup:startup-runtime](https://developer.android.com/jetpack/androidx/releases/startup#1.2.0) | 1.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.swiperefreshlayout:swiperefreshlayout](https://developer.android.com/jetpack/androidx) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.tracing:tracing-ktx](https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0) | 1.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.tracing:tracing](https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0) | 1.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.transition:transition](https://developer.android.com/jetpack/androidx/releases/transition#1.6.0) | 1.6.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.vectordrawable:vectordrawable-animated](https://developer.android.com/jetpack/androidx) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.vectordrawable:vectordrawable](https://developer.android.com/jetpack/androidx) | 1.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.versionedparcelable:versionedparcelable](http://developer.android.com/tools/extras/support-library.html) | 1.1.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.viewpager2:viewpager2](https://developer.android.com/jetpack/androidx) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.viewpager2:viewpager2](https://developer.android.com/jetpack/androidx/releases/viewpager2#1.1.0-beta02) | 1.1.0-beta02 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.viewpager:viewpager](http://developer.android.com/tools/extras/support-library.html) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.webkit:webkit](https://developer.android.com/jetpack/androidx/releases/webkit#1.12.1) | 1.12.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.window:window-core](https://developer.android.com/jetpack/androidx/releases/window#1.5.0) | 1.5.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.window:window](https://developer.android.com/jetpack/androidx/releases/window#1.5.0) | 1.5.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.work:work-runtime-ktx](https://developer.android.com/jetpack/androidx/releases/work#2.11.1) | 2.11.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [androidx.work:work-runtime](https://developer.android.com/jetpack/androidx/releases/work#2.11.1) | 2.11.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.android.datatransport:transport-api | 3.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.android.datatransport:transport-backend-cct | 3.1.9 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.android.datatransport:transport-runtime | 3.1.9 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.android.gms:play-services-base | 18.9.0 | Android Software Development Kit License | Google Play services client libraries. Proprietary; redistributed under the Android SDK licence terms, not an OSS licence. |
| com.google.android.gms:play-services-basement | 18.9.0 | Android Software Development Kit License | Google Play services client libraries. Proprietary; redistributed under the Android SDK licence terms, not an OSS licence. |
| com.google.android.gms:play-services-cloud-messaging | 17.2.0 | Android Software Development Kit License | Google Play services client libraries. Proprietary; redistributed under the Android SDK licence terms, not an OSS licence. |
| com.google.android.gms:play-services-stats | 17.0.2 | Android Software Development Kit License | Google Play services client libraries. Proprietary; redistributed under the Android SDK licence terms, not an OSS licence. |
| com.google.android.gms:play-services-tasks | 18.4.0 | Android Software Development Kit License | Google Play services client libraries. Proprietary; redistributed under the Android SDK licence terms, not an OSS licence. |
| [com.google.android.material:material](https://github.com/material-components/material-components-android) | 1.13.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.android.play:core-common | 2.0.4 | Play Core Software Development Kit Terms of Service | Proprietary Google component. |
| com.google.android.play:integrity | 1.4.0 | Play Integrity API Terms of Service | Proprietary Google component used for device attestation. |
| [com.google.code.findbugs:jsr305](http://findbugs.sourceforge.net/) | 3.0.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.code.gson:gson | 2.13.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.crypto.tink:apps-webpush](http://github.com/tink-crypto/tink-java-apps) | 1.11.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.crypto.tink:tink-android](http://github.com/tink-crypto/tink-java) | 1.8.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.dagger:dagger-lint-aar](https://github.com/google/dagger) | 2.60.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.dagger:dagger](https://github.com/google/dagger) | 2.60.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.dagger:hilt-android](https://github.com/google/dagger) | 2.60.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.google.dagger:hilt-core](https://github.com/google/dagger) | 2.60.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.errorprone:error_prone_annotations | 2.38.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-annotations | 17.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-bom | 34.14.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-common | 22.0.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-components | 19.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-datatransport | 18.2.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-encoders-json | 18.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-encoders-proto | 16.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-encoders | 17.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-iid-interop | 17.1.0 | Android Software Development Kit License | Proprietary Firebase interop shim. |
| com.google.firebase:firebase-installations-interop | 17.3.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-installations | 19.1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.firebase:firebase-measurement-connector | 19.0.0 | Android Software Development Kit License | Proprietary Firebase interop shim. |
| com.google.firebase:firebase-messaging | 25.0.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| com.google.guava:listenablefuture | 1.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.squareup.okhttp3:logging-interceptor](https://square.github.io/okhttp/) | 5.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.squareup.okhttp3:okhttp](https://square.github.io/okhttp/) | 5.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.squareup.okio:okio](https://github.com/square/okio/) | 3.17.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.squareup.retrofit2:converter-gson](https://github.com/square/retrofit) | 3.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [com.squareup.retrofit2:retrofit](https://github.com/square/retrofit) | 3.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [io.coil-kt.coil3:coil-core](https://github.com/coil-kt/coil) | 3.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [io.coil-kt.coil3:coil](https://github.com/coil-kt/coil) | 3.4.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [jakarta.inject:jakarta.inject-api](https://github.com/eclipse-ee4j/injection-api) | 2.0.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [javax.inject:javax.inject](http://code.google.com/p/atinject/) | 1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [net.java.dev.jna:jna](https://github.com/java-native-access/jna) | 5.18.1 | [Apache-2.0 (elected)](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [net.zetetic:sqlcipher-android](https://www.zetetic.net/sqlcipher) | 4.9.0 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | The published POM carries a <licenses> entry with a URL but an EMPTY <name>, so automated tooling reports no licence for it. SQLCipher Community Edition is BSD-3-Clause, copyright ZETETIC LLC. It embeds SQLite, which its authors released into the public domain. |
| [org.jetbrains.kotlin:kotlin-bom](https://kotlinlang.org/) | 1.8.22 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlin:kotlin-parcelize-runtime](https://kotlinlang.org/) | 2.3.10 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlin:kotlin-stdlib](https://kotlinlang.org/) | 2.3.21 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-coroutines-android](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-coroutines-bom](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-coroutines-core](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-coroutines-play-services](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-serialization-bom](https://github.com/Kotlin/kotlinx.serialization) | 1.7.3 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains.kotlinx:kotlinx-serialization-core](https://github.com/Kotlin/kotlinx.serialization) | 1.7.3 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jetbrains:annotations](https://github.com/JetBrains/java-annotations) | 23.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.jspecify:jspecify](http://jspecify.org/) | 1.0.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |
| [org.mindrot:jbcrypt](https://github.com/djmdjm/jBCrypt) | 0.4 | [ISC](https://spdx.org/licenses/ISC.html) |  |
| [org.unifiedpush.android:connector](https://unifiedpush.org/developers/android/) | 3.0.3 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |  |

## Rust crates in the bundled native libraries (284)

Statically linked into the committed `.so` files, for both `arm64-v8a`
and `armeabi-v7a`.

| Crate | Version | Licence | Shipped in |
|---|---|---|---|
| [adler2](https://github.com/oyvindln/adler2) | 2.0.1 | 0BSD OR MIT OR Apache-2.0 | libpingolin_mobile.so |
| [aead](https://github.com/RustCrypto/traits) | 0.5.2 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [ahash](https://github.com/tkaitchuck/ahash) | 0.8.12 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [aho-corasick](https://github.com/BurntSushi/aho-corasick) | 1.1.4 | Unlicense OR MIT | libpingolin_mobile.so |
| [android_log-sys](https://github.com/nercury/android_log-sys-rs) | 0.2.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [android_log-sys](https://github.com/rust-mobile/android_log-sys-rs) | 0.3.2 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [android_logger](https://github.com/rust-mobile/android_logger-rs) | 0.14.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [android_system_properties](https://github.com/nical/android_system_properties) | 0.1.5 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [anstyle](https://github.com/rust-cli/anstyle.git) | 1.0.14 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [anyhow](https://github.com/dtolnay/anyhow) | 1.0.103 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [anyhow](https://github.com/dtolnay/anyhow) | 1.0.104 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [arc-swap](https://github.com/vorner/arc-swap) | 1.9.2 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [argon2](https://github.com/RustCrypto/password-hashes/tree/master/argon2) | 0.5.3 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [arrayref](https://github.com/droundy/arrayref) | 0.3.9 | [BSD-2-Clause](https://spdx.org/licenses/BSD-2-Clause.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [arrayvec](https://github.com/bluss/arrayvec) | 0.7.7 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [arrayvec](https://github.com/bluss/arrayvec) | 0.7.8 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [askama](https://github.com/askama-rs/askama) | 0.13.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [askama_parser](https://github.com/askama-rs/askama) | 0.13.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [async-compression](https://github.com/Nullus157/async-compression) | 0.4.42 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [async-lock](https://github.com/smol-rs/async-lock) | 3.4.2 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [async-stream](https://github.com/tokio-rs/async-stream) | 0.3.6 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [atomic-waker](https://github.com/smol-rs/atomic-waker) | 1.1.2 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [axum](https://github.com/tokio-rs/axum) | 0.8.9 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [axum-core](https://github.com/tokio-rs/axum) | 0.5.6 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [base16ct](https://github.com/RustCrypto/formats/tree/master/base16ct) | 0.2.0 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [base64](https://github.com/marshallpierce/rust-base64) | 0.22.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [base64ct](https://github.com/RustCrypto/formats) | 1.8.3 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [basic-toml](https://github.com/dtolnay/basic-toml) | 0.1.10 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [bcrypt](https://github.com/Keats/rust-bcrypt) | 0.16.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [bitflags](https://github.com/bitflags/bitflags) | 2.13.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [bitflags](https://github.com/bitflags/bitflags) | 2.13.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [blake2](https://github.com/RustCrypto/hashes) | 0.10.6 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [blake3](https://github.com/BLAKE3-team/BLAKE3) | 1.8.5 | CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception | libnote_collab_sdk.so, libpingolin_mobile.so |
| [block-buffer](https://github.com/RustCrypto/utils) | 0.10.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [blowfish](https://github.com/RustCrypto/block-ciphers) | 0.9.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [byteorder](https://github.com/BurntSushi/byteorder) | 1.5.0 | Unlicense OR MIT | libpingolin_mobile.so |
| [bytes](https://github.com/tokio-rs/bytes) | 1.12.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [bytes](https://github.com/tokio-rs/bytes) | 1.12.1 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [camino](https://github.com/camino-rs/camino) | 1.2.3 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [camino](https://github.com/camino-rs/camino) | 1.2.5 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [cargo-platform](https://github.com/rust-lang/cargo) | 0.1.9 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [cargo_metadata](https://github.com/oli-obk/cargo_metadata) | 0.19.2 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [cfg-if](https://github.com/rust-lang/cfg-if) | 1.0.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [chacha20](https://github.com/RustCrypto/stream-ciphers) | 0.9.1 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [chacha20poly1305](https://github.com/RustCrypto/AEADs/tree/master/chacha20poly1305) | 0.10.1 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [chrono](https://github.com/chronotope/chrono) | 0.4.45 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [cipher](https://github.com/RustCrypto/traits) | 0.4.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [clap](https://github.com/clap-rs/clap) | 4.6.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [clap_builder](https://github.com/clap-rs/clap) | 4.6.2 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [clap_lex](https://github.com/clap-rs/clap) | 1.1.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [compression-codecs](https://github.com/Nullus157/async-compression) | 0.4.38 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [compression-core](https://github.com/Nullus157/async-compression) | 0.4.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [const-oid](https://github.com/RustCrypto/formats/tree/master/const-oid) | 0.9.6 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [constant_time_eq](https://github.com/cesarb/constant_time_eq) | 0.4.2 | CC0-1.0 OR MIT-0 OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [cpufeatures](https://github.com/RustCrypto/utils) | 0.2.17 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [crc32fast](https://github.com/srijs/rust-crc32fast) | 1.5.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [crossbeam-utils](https://github.com/crossbeam-rs/crossbeam) | 0.8.22 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [crypto-bigint](https://github.com/RustCrypto/crypto-bigint) | 0.5.5 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [crypto-common](https://github.com/RustCrypto/traits) | 0.1.7 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [crypto_box](https://github.com/RustCrypto/nacl-compat/tree/master/crypto_box) | 0.9.1 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [crypto_secretbox](https://github.com/RustCrypto/nacl-compat/tree/master/crypto_secretbox) | 0.1.1 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [curve25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek/tree/main/curve25519-dalek) | 4.1.3 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [dashmap](https://github.com/xacrimon/dashmap) | 6.2.1 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [der](https://github.com/RustCrypto/formats/tree/master/der) | 0.7.10 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [digest](https://github.com/RustCrypto/traits) | 0.10.7 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [ed25519](https://github.com/RustCrypto/signatures/tree/master/ed25519) | 2.2.3 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [ed25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek/tree/main/ed25519-dalek) | 2.2.0 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [either](https://github.com/rayon-rs/either) | 1.16.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [elliptic-curve](https://github.com/RustCrypto/traits/tree/master/elliptic-curve) | 0.13.8 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [env_filter](https://github.com/rust-cli/env_logger) | 0.1.4 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [equivalent](https://github.com/indexmap-rs/equivalent) | 1.0.2 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [errno](https://github.com/lambda-fairy/rust-errno) | 0.3.14 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [event-listener](https://github.com/smol-rs/event-listener) | 5.4.2 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [event-listener-strategy](https://github.com/smol-rs/event-listener-strategy) | 0.5.4 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [fallible-iterator](https://github.com/sfackler/rust-fallible-iterator) | 0.3.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [fallible-streaming-iterator](https://github.com/sfackler/fallible-streaming-iterator) | 0.1.9 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [fastrand](https://github.com/smol-rs/fastrand) | 2.4.1 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [fastrand](https://github.com/smol-rs/fastrand) | 2.5.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [ff](https://github.com/zkcrypto/ff) | 0.13.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [flate2](https://github.com/rust-lang/flate2-rs) | 1.1.9 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [fnv](https://github.com/servo/rust-fnv) | 1.0.7 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [form_urlencoded](https://github.com/servo/rust-url) | 1.2.2 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [fs-err](https://github.com/andrewhickman/fs-err) | 2.11.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [futures](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-channel](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-core](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-executor](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-io](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-sink](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-task](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [futures-util](https://github.com/rust-lang/futures-rs) | 0.3.32 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [generic-array](https://github.com/fizyk20/generic-array.git) | 0.14.7 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [getrandom](https://github.com/rust-random/getrandom) | 0.2.17 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [getrandom](https://github.com/rust-random/getrandom) | 0.4.3 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [glob](https://github.com/rust-lang/glob) | 0.3.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [goblin](https://github.com/m4b/goblin) | 0.8.2 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [group](https://github.com/zkcrypto/group) | 0.13.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [h2](https://github.com/hyperium/h2) | 0.4.15 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [hashbrown](https://github.com/rust-lang/hashbrown) | 0.14.5 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [hashbrown](https://github.com/rust-lang/hashbrown) | 0.17.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [hashlink](https://github.com/kyren/hashlink) | 0.9.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [heck](https://github.com/withoutboats/heck) | 0.5.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [hex](https://github.com/KokaKiwi/rust-hex) | 0.4.3 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [http](https://github.com/hyperium/http) | 1.4.2 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [http-body](https://github.com/hyperium/http-body) | 1.0.1 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [http-body-util](https://github.com/hyperium/http-body) | 0.1.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [httparse](https://github.com/seanmonstar/httparse) | 1.10.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [httpdate](https://github.com/pyfisch/httpdate) | 1.0.3 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [hyper](https://github.com/hyperium/hyper) | 1.10.1 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [hyper-rustls](https://github.com/rustls/hyper-rustls) | 0.27.9 | Apache-2.0 OR ISC OR MIT | libpingolin_mobile.so |
| [hyper-timeout](https://github.com/hjr3/hyper-timeout) | 0.5.2 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [hyper-util](https://github.com/hyperium/hyper-util) | 0.1.20 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [iana-time-zone](https://github.com/strawlab/iana-time-zone) | 0.1.65 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [icu_collections](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_locale_core](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_normalizer](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_normalizer_data](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_properties](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_properties_data](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [icu_provider](https://github.com/unicode-org/icu4x) | 2.2.0 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [idna](https://github.com/servo/rust-url/) | 1.1.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [idna_adapter](https://github.com/hsivonen/idna_adapter) | 1.2.2 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [indexmap](https://github.com/indexmap-rs/indexmap) | 2.14.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [inout](https://github.com/RustCrypto/utils) | 0.1.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [ipnet](https://github.com/krisprice/ipnet) | 2.12.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [itertools](https://github.com/rust-itertools/itertools) | 0.14.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [itoa](https://github.com/dtolnay/itoa) | 1.0.18 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [lazy_static](https://github.com/rust-lang-nursery/lazy-static.rs) | 1.5.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [libc](https://github.com/rust-lang/libc) | 0.2.186 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [libc](https://github.com/rust-lang/libc) | 0.2.189 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [libsqlite3-sys](https://github.com/rusqlite/rusqlite) | 0.28.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [linux-raw-sys](https://github.com/sunfishcode/linux-raw-sys) | 0.12.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [litemap](https://github.com/unicode-org/icu4x) | 0.8.2 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [lock_api](https://github.com/Amanieu/parking_lot) | 0.4.14 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [log](https://github.com/rust-lang/log) | 0.4.33 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [matchit](https://github.com/ibraheemdev/matchit) | 0.8.4 | MIT AND BSD-3-Clause | libpingolin_mobile.so |
| [memchr](https://github.com/BurntSushi/memchr) | 2.8.2 | Unlicense OR MIT | libpingolin_mobile.so |
| [memchr](https://github.com/BurntSushi/memchr) | 2.8.3 | Unlicense OR MIT | libnote_collab_sdk.so |
| [mime](https://github.com/hyperium/mime) | 0.3.17 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [minimal-lexical](https://github.com/Alexhuszagh/minimal-lexical) | 0.2.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [miniz_oxide](https://github.com/Frommi/miniz_oxide/tree/master/miniz_oxide) | 0.8.9 | MIT OR Zlib OR Apache-2.0 | libpingolin_mobile.so |
| [mio](https://github.com/tokio-rs/mio) | 1.2.1 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [nom](https://github.com/Geal/nom) | 7.1.3 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [nu-ansi-term](https://github.com/nushell/nu-ansi-term) | 0.50.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [num-bigint](https://github.com/rust-num/num-bigint) | 0.4.6 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [num-integer](https://github.com/rust-num/num-integer) | 0.1.46 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [num-traits](https://github.com/rust-num/num-traits) | 0.2.19 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [num_cpus](https://github.com/seanmonstar/num_cpus) | 1.17.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [once_cell](https://github.com/matklad/once_cell) | 1.21.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [opaque-debug](https://github.com/RustCrypto/utils) | 0.3.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [openssl-probe](https://github.com/rustls/openssl-probe) | 0.2.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [parking](https://github.com/smol-rs/parking) | 2.2.1 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [parking_lot_core](https://github.com/Amanieu/parking_lot) | 0.9.12 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [password-hash](https://github.com/RustCrypto/traits/tree/master/password-hash) | 0.5.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [percent-encoding](https://github.com/servo/rust-url/) | 2.3.2 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [pin-project](https://github.com/taiki-e/pin-project) | 1.1.13 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [pin-project-lite](https://github.com/taiki-e/pin-project-lite) | 0.2.17 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [plain](https://github.com/randomites/plain) | 0.2.3 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [poly1305](https://github.com/RustCrypto/universal-hashes) | 0.8.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [potential_utf](https://github.com/unicode-org/icu4x) | 0.1.5 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [ppv-lite86](https://github.com/cryptocorrosion/cryptocorrosion) | 0.2.21 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [proc-macro2](https://github.com/dtolnay/proc-macro2) | 1.0.106 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [proc-macro2](https://github.com/dtolnay/proc-macro2) | 1.0.107 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [prost](https://github.com/tokio-rs/prost) | 0.14.4 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | libpingolin_mobile.so |
| [quote](https://github.com/dtolnay/quote) | 1.0.46 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [quote](https://github.com/dtolnay/quote) | 1.0.47 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [rand](https://github.com/rust-random/rand) | 0.8.6 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [rand](https://github.com/rust-random/rand) | 0.8.7 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [rand_chacha](https://github.com/rust-random/rand) | 0.3.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [rand_core](https://github.com/rust-random/rand) | 0.6.4 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [regex](https://github.com/rust-lang/regex) | 1.12.4 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [regex-automata](https://github.com/rust-lang/regex) | 0.4.14 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [regex-syntax](https://github.com/rust-lang/regex) | 0.8.11 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [reqwest](https://github.com/seanmonstar/reqwest) | 0.12.28 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [ring](https://github.com/briansmith/ring) | 0.17.14 | Apache-2.0 AND ISC | libpingolin_mobile.so |
| [rusqlite](https://github.com/rusqlite/rusqlite) | 0.31.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [rustc-hash](https://github.com/rust-lang/rustc-hash) | 2.1.3 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [rustix](https://github.com/bytecodealliance/rustix) | 1.1.4 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [rustls](https://github.com/rustls/rustls) | 0.23.41 | Apache-2.0 OR ISC OR MIT | libpingolin_mobile.so |
| [rustls-native-certs](https://github.com/rustls/rustls-native-certs) | 0.8.4 | Apache-2.0 OR ISC OR MIT | libpingolin_mobile.so |
| [rustls-pki-types](https://github.com/rustls/pki-types) | 1.14.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [rustls-webpki](https://github.com/rustls/webpki) | 0.103.13 | [ISC](https://spdx.org/licenses/ISC.html) | libpingolin_mobile.so |
| [ryu](https://github.com/dtolnay/ryu) | 1.0.23 | Apache-2.0 OR BSL-1.0 | libpingolin_mobile.so |
| [salsa20](https://github.com/RustCrypto/stream-ciphers) | 0.10.2 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [scopeguard](https://github.com/bluss/scopeguard) | 1.2.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [scroll](https://github.com/m4b/scroll) | 0.12.0 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [sec1](https://github.com/RustCrypto/formats/tree/master/sec1) | 0.7.3 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [semver](https://github.com/dtolnay/semver) | 1.0.28 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [serde](https://github.com/serde-rs/serde) | 1.0.228 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [serde](https://github.com/serde-rs/serde) | 1.0.229 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [serde_core](https://github.com/serde-rs/serde) | 1.0.228 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [serde_core](https://github.com/serde-rs/serde) | 1.0.229 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [serde_json](https://github.com/serde-rs/json) | 1.0.150 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [serde_json](https://github.com/serde-rs/json) | 1.0.151 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [serde_spanned](https://github.com/toml-rs/toml) | 1.1.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [serde_urlencoded](https://github.com/nox/serde_urlencoded) | 0.7.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [sha2](https://github.com/RustCrypto/hashes) | 0.10.9 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [sharded-slab](https://github.com/hawkw/sharded-slab) | 0.1.7 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [signature](https://github.com/RustCrypto/traits/tree/master/signature) | 2.2.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [simd-adler32](https://github.com/mcountryman/simd-adler32) | 0.3.9 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [siphasher](https://github.com/jedisct1/rust-siphash) | 0.3.11 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [slab](https://github.com/tokio-rs/slab) | 0.4.12 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [smallstr](https://github.com/murarth/smallstr) | 0.3.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [smallvec](https://github.com/servo/rust-smallvec) | 1.15.2 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [smawk](https://github.com/mgeisler/smawk) | 0.3.3 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [socket2](https://github.com/rust-lang/socket2) | 0.6.4 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [srp](https://github.com/RustCrypto/PAKEs) | 0.6.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [stable_deref_trait](https://github.com/storyyeller/stable_deref_trait) | 1.2.1 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [static_assertions](https://github.com/nvzqz/static-assertions-rs) | 1.1.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [strsim](https://github.com/rapidfuzz/strsim-rs) | 0.11.1 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [subtle](https://github.com/dalek-cryptography/subtle) | 2.6.1 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [syn](https://github.com/dtolnay/syn) | 2.0.118 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [syn](https://github.com/dtolnay/syn) | 2.0.119 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [syn](https://github.com/dtolnay/syn) | 3.0.3 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [sync_wrapper](https://github.com/Actyx/sync_wrapper) | 1.0.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | libpingolin_mobile.so |
| [synstructure](https://github.com/mystor/synstructure) | 0.13.2 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tempfile](https://github.com/Stebalien/tempfile) | 3.27.0 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [textwrap](https://github.com/mgeisler/textwrap) | 0.16.2 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [thiserror](https://github.com/dtolnay/thiserror) | 1.0.69 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [thiserror](https://github.com/dtolnay/thiserror) | 2.0.18 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [thiserror](https://github.com/dtolnay/thiserror) | 2.0.19 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [thread_local](https://github.com/Amanieu/thread_local-rs) | 1.1.9 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [tinystr](https://github.com/unicode-org/icu4x) | 0.8.3 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [tokio](https://github.com/tokio-rs/tokio) | 1.52.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tokio-rustls](https://github.com/rustls/tokio-rustls) | 0.26.4 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [tokio-stream](https://github.com/tokio-rs/tokio) | 0.1.18 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tokio-util](https://github.com/tokio-rs/tokio) | 0.7.18 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [toml](https://github.com/toml-rs/toml) | 0.5.11 | MIT OR Apache-2.0 | libnote_collab_sdk.so |
| [toml](https://github.com/toml-rs/toml) | 0.9.12+spec-1.1.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [toml_datetime](https://github.com/toml-rs/toml) | 0.7.5+spec-1.1.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [toml_parser](https://github.com/toml-rs/toml) | 1.1.2+spec-1.1.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [toml_writer](https://github.com/toml-rs/toml) | 1.1.1+spec-1.1.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [tonic](https://github.com/hyperium/tonic) | 0.14.6 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tonic-prost](https://github.com/hyperium/tonic) | 0.14.6 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tower](https://github.com/tower-rs/tower) | 0.5.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tower-http](https://github.com/tower-rs/tower-http) | 0.6.11 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tower-layer](https://github.com/tower-rs/tower) | 0.3.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tower-service](https://github.com/tower-rs/tower) | 0.3.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tracing](https://github.com/tokio-rs/tracing) | 0.1.44 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tracing-android](https://github.com/Actyx/tracing-android) | 0.2.0 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [tracing-core](https://github.com/tokio-rs/tracing) | 0.1.36 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tracing-log](https://github.com/tokio-rs/tracing) | 0.2.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [tracing-subscriber](https://github.com/tokio-rs/tracing) | 0.3.23 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [try-lock](https://github.com/seanmonstar/try-lock) | 0.2.5 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [typenum](https://github.com/paholg/typenum) | 1.20.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [unicode-ident](https://github.com/dtolnay/unicode-ident) | 1.0.24 | (MIT OR Apache-2.0) AND Unicode-3.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [uniffi](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi](https://github.com/mozilla/uniffi-rs) | 0.30.0 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libpingolin_mobile.so |
| [uniffi_bindgen](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi_build](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi_core](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi_core](https://github.com/mozilla/uniffi-rs) | 0.30.0 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libpingolin_mobile.so |
| [uniffi_meta](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi_meta](https://github.com/mozilla/uniffi-rs) | 0.30.0 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libpingolin_mobile.so |
| [uniffi_pipeline](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [uniffi_pipeline](https://github.com/mozilla/uniffi-rs) | 0.30.0 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libpingolin_mobile.so |
| [uniffi_udl](https://github.com/mozilla/uniffi-rs) | 0.29.5 | [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/) | libnote_collab_sdk.so |
| [universal-hash](https://github.com/RustCrypto/traits) | 0.5.1 | MIT OR Apache-2.0 | libnote_collab_sdk.so, libpingolin_mobile.so |
| [untrusted](https://github.com/briansmith/untrusted) | 0.9.0 | [ISC](https://spdx.org/licenses/ISC.html) | libpingolin_mobile.so |
| [url](https://github.com/servo/rust-url) | 2.5.8 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [utf8_iter](https://github.com/hsivonen/utf8_iter) | 1.0.4 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [uuid](https://github.com/uuid-rs/uuid) | 1.23.4 | Apache-2.0 OR MIT | libpingolin_mobile.so |
| [uuid](https://github.com/uuid-rs/uuid) | 1.24.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [voprf](https://github.com/facebook/voprf/) | 0.5.0 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [want](https://github.com/seanmonstar/want) | 0.3.1 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [webpki-roots](https://github.com/rustls/webpki-roots) | 1.0.8 | [CDLA-Permissive-2.0](https://spdx.org/licenses/CDLA-Permissive-2.0.html) | libpingolin_mobile.so |
| [weedle2](https://github.com/mozilla/uniffi-rs) | 5.0.0 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [winnow](https://github.com/winnow-rs/winnow) | 0.7.15 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so, libpingolin_mobile.so |
| [winnow](https://github.com/winnow-rs/winnow) | 1.0.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [writeable](https://github.com/unicode-org/icu4x) | 0.6.3 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [x25519-dalek](https://github.com/dalek-cryptography/curve25519-dalek/tree/main/x25519-dalek) | 2.0.1 | [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html) | libpingolin_mobile.so |
| [yoke](https://github.com/unicode-org/icu4x) | 0.8.3 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [yrs](https://github.com/y-crdt/y-crdt/) | 0.21.3 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [zerocopy](https://github.com/google/zerocopy) | 0.8.52 | BSD-2-Clause OR Apache-2.0 OR MIT | libpingolin_mobile.so |
| [zerocopy](https://github.com/google/zerocopy) | 0.8.55 | BSD-2-Clause OR Apache-2.0 OR MIT | libnote_collab_sdk.so |
| [zerofrom](https://github.com/unicode-org/icu4x) | 0.1.8 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [zeroize](https://github.com/RustCrypto/utils) | 1.9.0 | Apache-2.0 OR MIT | libnote_collab_sdk.so, libpingolin_mobile.so |
| [zerotrie](https://github.com/unicode-org/icu4x) | 0.2.4 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [zerovec](https://github.com/unicode-org/icu4x) | 0.11.6 | [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html) | libpingolin_mobile.so |
| [zmij](https://github.com/dtolnay/zmij) | 1.0.21 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [zmij](https://github.com/dtolnay/zmij) | 1.0.23 | [MIT](https://spdx.org/licenses/MIT.html) | libnote_collab_sdk.so |
| [zstd](https://github.com/gyscos/zstd-rs) | 0.13.3 | [MIT](https://spdx.org/licenses/MIT.html) | libpingolin_mobile.so |
| [zstd-safe](https://github.com/gyscos/zstd-rs) | 7.2.4 | MIT OR Apache-2.0 | libpingolin_mobile.so |
| [zstd-sys](https://github.com/gyscos/zstd-rs) | 2.0.16+zstd.1.5.7 | MIT OR Apache-2.0 | libpingolin_mobile.so |

## Licence summary

| Licence | Components |
|---|---|
| MIT OR Apache-2.0 | 146 |
| Apache-2.0 | 142 |
| MIT | 51 |
| Apache-2.0 OR MIT | 32 |
| Unicode-3.0 | 15 |
| MPL-2.0 | 11 |
| Android Software Development Kit License | 7 |
| BSD-3-Clause | 6 |
| Unlicense OR MIT | 4 |
| Apache-2.0 OR ISC OR MIT | 3 |
| ISC | 3 |
| Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT | 2 |
| BSD-2-Clause OR Apache-2.0 OR MIT | 2 |
| (MIT OR Apache-2.0) AND Unicode-3.0 | 1 |
| 0BSD OR MIT OR Apache-2.0 | 1 |
| Apache-2.0 (elected) | 1 |
| Apache-2.0 AND ISC | 1 |
| Apache-2.0 OR BSL-1.0 | 1 |
| BSD-2-Clause | 1 |
| CC0-1.0 OR Apache-2.0 OR Apache-2.0 WITH LLVM-exception | 1 |
| CC0-1.0 OR MIT-0 OR Apache-2.0 | 1 |
| CDLA-Permissive-2.0 | 1 |
| MIT AND BSD-3-Clause | 1 |
| MIT OR Zlib OR Apache-2.0 | 1 |
| Play Core Software Development Kit Terms of Service | 1 |
| Play Integrity API Terms of Service | 1 |

## Licence texts

Verbatim copies of every licence named above, from `licenses/texts/`. Apache-2.0
section 4(a) and MPL-2.0 section 3.2 both require a copy to travel with the
distribution; the rest are reproduced for the same reason rather than linked.
Each project's own copyright line lives in its repository, linked in the tables
above.

### 0BSD

```
Copyright (C) YEAR by AUTHOR EMAIL

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### Apache-2.0

```
                              Apache License
                        Version 2.0, January 2004
                     http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

   "License" shall mean the terms and conditions for use, reproduction,
   and distribution as defined by Sections 1 through 9 of this document.

   "Licensor" shall mean the copyright owner or entity authorized by
   the copyright owner that is granting the License.

   "Legal Entity" shall mean the union of the acting entity and all
   other entities that control, are controlled by, or are under common
   control with that entity. For the purposes of this definition,
   "control" means (i) the power, direct or indirect, to cause the
   direction or management of such entity, whether by contract or
   otherwise, or (ii) ownership of fifty percent (50%) or more of the
   outstanding shares, or (iii) beneficial ownership of such entity.

   "You" (or "Your") shall mean an individual or Legal Entity
   exercising permissions granted by this License.

   "Source" form shall mean the preferred form for making modifications,
   including but not limited to software source code, documentation
   source, and configuration files.

   "Object" form shall mean any form resulting from mechanical
   transformation or translation of a Source form, including but
   not limited to compiled object code, generated documentation,
   and conversions to other media types.

   "Work" shall mean the work of authorship, whether in Source or
   Object form, made available under the License, as indicated by a
   copyright notice that is included in or attached to the work
   (an example is provided in the Appendix below).

   "Derivative Works" shall mean any work, whether in Source or Object
   form, that is based on (or derived from) the Work and for which the
   editorial revisions, annotations, elaborations, or other modifications
   represent, as a whole, an original work of authorship. For the purposes
   of this License, Derivative Works shall not include works that remain
   separable from, or merely link (or bind by name) to the interfaces of,
   the Work and Derivative Works thereof.

   "Contribution" shall mean any work of authorship, including
   the original version of the Work and any modifications or additions
   to that Work or Derivative Works thereof, that is intentionally
   submitted to Licensor for inclusion in the Work by the copyright owner
   or by an individual or Legal Entity authorized to submit on behalf of
   the copyright owner. For the purposes of this definition, "submitted"
   means any form of electronic, verbal, or written communication sent
   to the Licensor or its representatives, including but not limited to
   communication on electronic mailing lists, source code control systems,
   and issue tracking systems that are managed by, or on behalf of, the
   Licensor for the purpose of discussing and improving the Work, but
   excluding communication that is conspicuously marked or otherwise
   designated in writing by the copyright owner as "Not a Contribution."

   "Contributor" shall mean Licensor and any individual or Legal Entity
   on behalf of whom a Contribution has been received by Licensor and
   subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   copyright license to reproduce, prepare Derivative Works of,
   publicly display, publicly perform, sublicense, and distribute the
   Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   (except as stated in this section) patent license to make, have made,
   use, offer to sell, sell, import, and otherwise transfer the Work,
   where such license applies only to those patent claims licensable
   by such Contributor that are necessarily infringed by their
   Contribution(s) alone or by combination of their Contribution(s)
   with the Work to which such Contribution(s) was submitted. If You
   institute patent litigation against any entity (including a
   cross-claim or counterclaim in a lawsuit) alleging that the Work
   or a Contribution incorporated within the Work constitutes direct
   or contributory patent infringement, then any patent licenses
   granted to You under this License for that Work shall terminate
   as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the
   Work or Derivative Works thereof in any medium, with or without
   modifications, and in Source or Object form, provided that You
   meet the following conditions:

   (a) You must give any other recipients of the Work or
       Derivative Works a copy of this License; and

   (b) You must cause any modified files to carry prominent notices
       stating that You changed the files; and

   (c) You must retain, in the Source form of any Derivative Works
       that You distribute, all copyright, patent, trademark, and
       attribution notices from the Source form of the Work,
       excluding those notices that do not pertain to any part of
       the Derivative Works; and

   (d) If the Work includes a "NOTICE" text file as part of its
       distribution, then any Derivative Works that You distribute must
       include a readable copy of the attribution notices contained
       within such NOTICE file, excluding those notices that do not
       pertain to any part of the Derivative Works, in at least one
       of the following places: within a NOTICE text file distributed
       as part of the Derivative Works; within the Source form or
       documentation, if provided along with the Derivative Works; or,
       within a display generated by the Derivative Works, if and
       wherever such third-party notices normally appear. The contents
       of the NOTICE file are for informational purposes only and
       do not modify the License. You may add Your own attribution
       notices within Derivative Works that You distribute, alongside
       or as an addendum to the NOTICE text from the Work, provided
       that such additional attribution notices cannot be construed
       as modifying the License.

   You may add Your own copyright statement to Your modifications and
   may provide additional or different license terms and conditions
   for use, reproduction, or distribution of Your modifications, or
   for any such Derivative Works as a whole, provided Your use,
   reproduction, and distribution of the Work otherwise complies with
   the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise,
   any Contribution intentionally submitted for inclusion in the Work
   by You to the Licensor shall be under the terms and conditions of
   this License, without any additional terms or conditions.
   Notwithstanding the above, nothing herein shall supersede or modify
   the terms of any separate license agreement you may have executed
   with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade
   names, trademarks, service marks, or product names of the Licensor,
   except as required for reasonable and customary use in describing the
   origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or
   agreed to in writing, Licensor provides the Work (and each
   Contributor provides its Contributions) on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
   implied, including, without limitation, any warranties or conditions
   of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
   PARTICULAR PURPOSE. You are solely responsible for determining the
   appropriateness of using or redistributing the Work and assume any
   risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory,
   whether in tort (including negligence), contract, or otherwise,
   unless required by applicable law (such as deliberate and grossly
   negligent acts) or agreed to in writing, shall any Contributor be
   liable to You for damages, including any direct, indirect, special,
   incidental, or consequential damages of any character arising as a
   result of this License or out of the use or inability to use the
   Work (including but not limited to damages for loss of goodwill,
   work stoppage, computer failure or malfunction, or any and all
   other commercial damages or losses), even if such Contributor
   has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing
   the Work or Derivative Works thereof, You may choose to offer,
   and charge a fee for, acceptance of support, warranty, indemnity,
   or other liability obligations and/or rights consistent with this
   License. However, in accepting such obligations, You may act only
   on Your own behalf and on Your sole responsibility, not on behalf
   of any other Contributor, and only if You agree to indemnify,
   defend, and hold each Contributor harmless for any liability
   incurred by, or claims asserted against, such Contributor by reason
   of your accepting any such warranty or additional liability.

END OF TERMS AND CONDITIONS

APPENDIX: How to apply the Apache License to your work.

   To apply the Apache License to your work, attach the following
   boilerplate notice, with the fields enclosed by brackets "[]"
   replaced with your own identifying information. (Don't include
   the brackets!)  The text should be enclosed in the appropriate
   comment syntax for the file format. We also recommend that a
   file or class name and description of purpose be included on the
   same "printed page" as the copyright notice for easier
   identification within third-party archives.

Copyright [yyyy] [name of copyright owner]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### BSD-2-Clause

```
Copyright (c) <year> <owner> 

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### BSD-3-Clause

```
Copyright (c) <year> <owner>. 

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### BSL-1.0

```
Boost Software License - Version 1.0 - August 17th, 2003

Permission is hereby granted, free of charge, to any person or organization obtaining a copy of the software and accompanying documentation covered by this license (the "Software") to use, reproduce, display, distribute, execute, and transmit the Software, and to prepare derivative works of the Software, and to permit third-parties to whom the Software is furnished to do so, all subject to the following:

The copyright notices in the Software and this entire statement, including the above license grant, this restriction and the following disclaimer, must be included in all copies of the Software, in whole or in part, and all derivative works of the Software, unless such copies or derivative works are solely in the form of machine-executable object code generated by a source language processor.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### CC0-1.0

```
Creative Commons Legal Code

CC0 1.0 Universal

    CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE
    LEGAL SERVICES. DISTRIBUTION OF THIS DOCUMENT DOES NOT CREATE AN
    ATTORNEY-CLIENT RELATIONSHIP. CREATIVE COMMONS PROVIDES THIS
    INFORMATION ON AN "AS-IS" BASIS. CREATIVE COMMONS MAKES NO WARRANTIES
    REGARDING THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS
    PROVIDED HEREUNDER, AND DISCLAIMS LIABILITY FOR DAMAGES RESULTING FROM
    THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED
    HEREUNDER.

Statement of Purpose

The laws of most jurisdictions throughout the world automatically confer
exclusive Copyright and Related Rights (defined below) upon the creator
and subsequent owner(s) (each and all, an "owner") of an original work of
authorship and/or a database (each, a "Work").

Certain owners wish to permanently relinquish those rights to a Work for
the purpose of contributing to a commons of creative, cultural and
scientific works ("Commons") that the public can reliably and without fear
of later claims of infringement build upon, modify, incorporate in other
works, reuse and redistribute as freely as possible in any form whatsoever
and for any purposes, including without limitation commercial purposes.
These owners may contribute to the Commons to promote the ideal of a free
culture and the further production of creative, cultural and scientific
works, or to gain reputation or greater distribution for their Work in
part through the use and efforts of others.

For these and/or other purposes and motivations, and without any
expectation of additional consideration or compensation, the person
associating CC0 with a Work (the "Affirmer"), to the extent that he or she
is an owner of Copyright and Related Rights in the Work, voluntarily
elects to apply CC0 to the Work and publicly distribute the Work under its
terms, with knowledge of his or her Copyright and Related Rights in the
Work and the meaning and intended legal effect of CC0 on those rights.

1. Copyright and Related Rights. A Work made available under CC0 may be
protected by copyright and related or neighboring rights ("Copyright and
Related Rights"). Copyright and Related Rights include, but are not
limited to, the following:

  i. the right to reproduce, adapt, distribute, perform, display,
     communicate, and translate a Work;
 ii. moral rights retained by the original author(s) and/or performer(s);
iii. publicity and privacy rights pertaining to a person's image or
     likeness depicted in a Work;
 iv. rights protecting against unfair competition in regards to a Work,
     subject to the limitations in paragraph 4(a), below;
  v. rights protecting the extraction, dissemination, use and reuse of data
     in a Work;
 vi. database rights (such as those arising under Directive 96/9/EC of the
     European Parliament and of the Council of 11 March 1996 on the legal
     protection of databases, and under any national implementation
     thereof, including any amended or successor version of such
     directive); and
vii. other similar, equivalent or corresponding rights throughout the
     world based on applicable law or treaty, and any national
     implementations thereof.

2. Waiver. To the greatest extent permitted by, but not in contravention
of, applicable law, Affirmer hereby overtly, fully, permanently,
irrevocably and unconditionally waives, abandons, and surrenders all of
Affirmer's Copyright and Related Rights and associated claims and causes
of action, whether now known or unknown (including existing as well as
future claims and causes of action), in the Work (i) in all territories
worldwide, (ii) for the maximum duration provided by applicable law or
treaty (including future time extensions), (iii) in any current or future
medium and for any number of copies, and (iv) for any purpose whatsoever,
including without limitation commercial, advertising or promotional
purposes (the "Waiver"). Affirmer makes the Waiver for the benefit of each
member of the public at large and to the detriment of Affirmer's heirs and
successors, fully intending that such Waiver shall not be subject to
revocation, rescission, cancellation, termination, or any other legal or
equitable action to disrupt the quiet enjoyment of the Work by the public
as contemplated by Affirmer's express Statement of Purpose.

3. Public License Fallback. Should any part of the Waiver for any reason
be judged legally invalid or ineffective under applicable law, then the
Waiver shall be preserved to the maximum extent permitted taking into
account Affirmer's express Statement of Purpose. In addition, to the
extent the Waiver is so judged Affirmer hereby grants to each affected
person a royalty-free, non transferable, non sublicensable, non exclusive,
irrevocable and unconditional license to exercise Affirmer's Copyright and
Related Rights in the Work (i) in all territories worldwide, (ii) for the
maximum duration provided by applicable law or treaty (including future
time extensions), (iii) in any current or future medium and for any number
of copies, and (iv) for any purpose whatsoever, including without
limitation commercial, advertising or promotional purposes (the
"License"). The License shall be deemed effective as of the date CC0 was
applied by Affirmer to the Work. Should any part of the License for any
reason be judged legally invalid or ineffective under applicable law, such
partial invalidity or ineffectiveness shall not invalidate the remainder
of the License, and in such case Affirmer hereby affirms that he or she
will not (i) exercise any of his or her remaining Copyright and Related
Rights in the Work or (ii) assert any associated claims and causes of
action with respect to the Work, in either case contrary to Affirmer's
express Statement of Purpose.

4. Limitations and Disclaimers.

 a. No trademark or patent rights held by Affirmer are waived, abandoned,
    surrendered, licensed or otherwise affected by this document.
 b. Affirmer offers the Work as-is and makes no representations or
    warranties of any kind concerning the Work, express, implied,
    statutory or otherwise, including without limitation warranties of
    title, merchantability, fitness for a particular purpose, non
    infringement, or the absence of latent or other defects, accuracy, or
    the present or absence of errors, whether or not discoverable, all to
    the greatest extent permissible under applicable law.
 c. Affirmer disclaims responsibility for clearing rights of other persons
    that may apply to the Work or any use thereof, including without
    limitation any person's Copyright and Related Rights in the Work.
    Further, Affirmer disclaims responsibility for obtaining any necessary
    consents, permissions or other rights required for any use of the
    Work.
 d. Affirmer understands and acknowledges that Creative Commons is not a
    party to this document and has no duty or obligation with respect to
    this CC0 or use of the Work.
```

### CDLA-Permissive-2.0

```
Community Data License Agreement - Permissive - Version 2.0

This is the Community Data License Agreement - Permissive, Version 2.0 (the "agreement"). Data Provider(s) and Data Recipient(s) agree as follows:

1. Provision of the Data

1.1. A Data Recipient may use, modify, and share the Data made available by Data Provider(s) under this agreement if that Data Recipient follows the terms of this agreement.

1.2. This agreement does not impose any restriction on a Data Recipient's use, modification, or sharing of any portions of the Data that are in the public domain or that may be used, modified, or shared under any other legal exception or limitation.

2. Conditions for Sharing Data

2.1. A Data Recipient may share Data, with or without modifications, so long as the Data Recipient makes available the text of this agreement with the shared Data.

3. No Restrictions on Results

3.1. This agreement does not impose any restriction or obligations with respect to the use, modification, or sharing of Results.

4. No Warranty; Limitation of Liability

4.1. All Data Recipients receive the Data subject to the following terms:

THE DATA IS PROVIDED ON AN "AS IS" BASIS, WITHOUT REPRESENTATIONS, WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.

NO DATA PROVIDER SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE DATA OR RESULTS, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

5. Definitions

5.1. "Data" means the material received by a Data Recipient under this agreement.

5.2. "Data Provider" means any person who is the source of Data provided under this agreement and in reliance on a Data Recipient's agreement to its terms.

5.3. "Data Recipient" means any person who receives Data directly or indirectly from a Data Provider and agrees to the terms of this agreement.

5.4. "Results" means any outcome obtained by computational analysis of Data, including for example machine learning models and models' insights.
```

### ISC

```
ISC License:

Copyright (c) 2004-2010 by Internet Systems Consortium, Inc. ("ISC")
Copyright (c) 1995-2003 by Internet Software Consortium

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND ISC DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL ISC BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### LLVM-exception

```
---- LLVM Exceptions to the Apache 2.0 License ----

   As an exception, if, as a result of your compiling your source code, portions
   of this Software are embedded into an Object form of such source code, you
   may redistribute such embedded portions in such Object form without complying
   with the conditions of Sections 4(a), 4(b) and 4(d) of the License.

   In addition, if you combine or link compiled forms of this Software with
   software that is licensed under the GPLv2 ("Combined Software") and if a
   court of competent jurisdiction determines that the patent provision (Section
   3), the indemnity provision (Section 9) or other Section of the License
   conflicts with the conditions of the GPLv2, you may retroactively and
   prospectively choose to deem waived or otherwise exclude such Section(s) of
   the License, but only in their entirety and only with respect to the Combined
   Software.
```

### MIT-0

```
MIT No Attribution

Copyright <YEAR> <COPYRIGHT HOLDER>

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MIT

```
MIT License

Copyright (c) <year> <copyright holders>

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
USE OR OTHER DEALINGS IN THE SOFTWARE.
```

### MPL-2.0

```
Mozilla Public License Version 2.0
==================================

1. Definitions
--------------

1.1. "Contributor"
    means each individual or legal entity that creates, contributes to
    the creation of, or owns Covered Software.

1.2. "Contributor Version"
    means the combination of the Contributions of others (if any) used
    by a Contributor and that particular Contributor's Contribution.

1.3. "Contribution"
    means Covered Software of a particular Contributor.

1.4. "Covered Software"
    means Source Code Form to which the initial Contributor has attached
    the notice in Exhibit A, the Executable Form of such Source Code
    Form, and Modifications of such Source Code Form, in each case
    including portions thereof.

1.5. "Incompatible With Secondary Licenses"
    means

    (a) that the initial Contributor has attached the notice described
        in Exhibit B to the Covered Software; or

    (b) that the Covered Software was made available under the terms of
        version 1.1 or earlier of the License, but not also under the
        terms of a Secondary License.

1.6. "Executable Form"
    means any form of the work other than Source Code Form.

1.7. "Larger Work"
    means a work that combines Covered Software with other material, in 
    a separate file or files, that is not Covered Software.

1.8. "License"
    means this document.

1.9. "Licensable"
    means having the right to grant, to the maximum extent possible,
    whether at the time of the initial grant or subsequently, any and
    all of the rights conveyed by this License.

1.10. "Modifications"
    means any of the following:

    (a) any file in Source Code Form that results from an addition to,
        deletion from, or modification of the contents of Covered
        Software; or

    (b) any new file in Source Code Form that contains any Covered
        Software.

1.11. "Patent Claims" of a Contributor
    means any patent claim(s), including without limitation, method,
    process, and apparatus claims, in any patent Licensable by such
    Contributor that would be infringed, but for the grant of the
    License, by the making, using, selling, offering for sale, having
    made, import, or transfer of either its Contributions or its
    Contributor Version.

1.12. "Secondary License"
    means either the GNU General Public License, Version 2.0, the GNU
    Lesser General Public License, Version 2.1, the GNU Affero General
    Public License, Version 3.0, or any later versions of those
    licenses.

1.13. "Source Code Form"
    means the form of the work preferred for making modifications.

1.14. "You" (or "Your")
    means an individual or a legal entity exercising rights under this
    License. For legal entities, "You" includes any entity that
    controls, is controlled by, or is under common control with You. For
    purposes of this definition, "control" means (a) the power, direct
    or indirect, to cause the direction or management of such entity,
    whether by contract or otherwise, or (b) ownership of more than
    fifty percent (50%) of the outstanding shares or beneficial
    ownership of such entity.

2. License Grants and Conditions
--------------------------------

2.1. Grants

Each Contributor hereby grants You a world-wide, royalty-free,
non-exclusive license:

(a) under intellectual property rights (other than patent or trademark)
    Licensable by such Contributor to use, reproduce, make available,
    modify, display, perform, distribute, and otherwise exploit its
    Contributions, either on an unmodified basis, with Modifications, or
    as part of a Larger Work; and

(b) under Patent Claims of such Contributor to make, use, sell, offer
    for sale, have made, import, and otherwise transfer either its
    Contributions or its Contributor Version.

2.2. Effective Date

The licenses granted in Section 2.1 with respect to any Contribution
become effective for each Contribution on the date the Contributor first
distributes such Contribution.

2.3. Limitations on Grant Scope

The licenses granted in this Section 2 are the only rights granted under
this License. No additional rights or licenses will be implied from the
distribution or licensing of Covered Software under this License.
Notwithstanding Section 2.1(b) above, no patent license is granted by a
Contributor:

(a) for any code that a Contributor has removed from Covered Software;
    or

(b) for infringements caused by: (i) Your and any other third party's
    modifications of Covered Software, or (ii) the combination of its
    Contributions with other software (except as part of its Contributor
    Version); or

(c) under Patent Claims infringed by Covered Software in the absence of
    its Contributions.

This License does not grant any rights in the trademarks, service marks,
or logos of any Contributor (except as may be necessary to comply with
the notice requirements in Section 3.4).

2.4. Subsequent Licenses

No Contributor makes additional grants as a result of Your choice to
distribute the Covered Software under a subsequent version of this
License (see Section 10.2) or under the terms of a Secondary License (if
permitted under the terms of Section 3.3).

2.5. Representation

Each Contributor represents that the Contributor believes its
Contributions are its original creation(s) or it has sufficient rights
to grant the rights to its Contributions conveyed by this License.

2.6. Fair Use

This License is not intended to limit any rights You have under
applicable copyright doctrines of fair use, fair dealing, or other
equivalents.

2.7. Conditions

Sections 3.1, 3.2, 3.3, and 3.4 are conditions of the licenses granted
in Section 2.1.

3. Responsibilities
-------------------

3.1. Distribution of Source Form

All distribution of Covered Software in Source Code Form, including any
Modifications that You create or to which You contribute, must be under
the terms of this License. You must inform recipients that the Source
Code Form of the Covered Software is governed by the terms of this
License, and how they can obtain a copy of this License. You may not
attempt to alter or restrict the recipients' rights in the Source Code
Form.

3.2. Distribution of Executable Form

If You distribute Covered Software in Executable Form then:

(a) such Covered Software must also be made available in Source Code
    Form, as described in Section 3.1, and You must inform recipients of
    the Executable Form how they can obtain a copy of such Source Code
    Form by reasonable means in a timely manner, at a charge no more
    than the cost of distribution to the recipient; and

(b) You may distribute such Executable Form under the terms of this
    License, or sublicense it under different terms, provided that the
    license for the Executable Form does not attempt to limit or alter
    the recipients' rights in the Source Code Form under this License.

3.3. Distribution of a Larger Work

You may create and distribute a Larger Work under terms of Your choice,
provided that You also comply with the requirements of this License for
the Covered Software. If the Larger Work is a combination of Covered
Software with a work governed by one or more Secondary Licenses, and the
Covered Software is not Incompatible With Secondary Licenses, this
License permits You to additionally distribute such Covered Software
under the terms of such Secondary License(s), so that the recipient of
the Larger Work may, at their option, further distribute the Covered
Software under the terms of either this License or such Secondary
License(s).

3.4. Notices

You may not remove or alter the substance of any license notices
(including copyright notices, patent notices, disclaimers of warranty,
or limitations of liability) contained within the Source Code Form of
the Covered Software, except that You may alter any license notices to
the extent required to remedy known factual inaccuracies.

3.5. Application of Additional Terms

You may choose to offer, and to charge a fee for, warranty, support,
indemnity or liability obligations to one or more recipients of Covered
Software. However, You may do so only on Your own behalf, and not on
behalf of any Contributor. You must make it absolutely clear that any
such warranty, support, indemnity, or liability obligation is offered by
You alone, and You hereby agree to indemnify every Contributor for any
liability incurred by such Contributor as a result of warranty, support,
indemnity or liability terms You offer. You may include additional
disclaimers of warranty and limitations of liability specific to any
jurisdiction.

4. Inability to Comply Due to Statute or Regulation
---------------------------------------------------

If it is impossible for You to comply with any of the terms of this
License with respect to some or all of the Covered Software due to
statute, judicial order, or regulation then You must: (a) comply with
the terms of this License to the maximum extent possible; and (b)
describe the limitations and the code they affect. Such description must
be placed in a text file included with all distributions of the Covered
Software under this License. Except to the extent prohibited by statute
or regulation, such description must be sufficiently detailed for a
recipient of ordinary skill to be able to understand it.

5. Termination
--------------

5.1. The rights granted under this License will terminate automatically
if You fail to comply with any of its terms. However, if You become
compliant, then the rights granted under this License from a particular
Contributor are reinstated (a) provisionally, unless and until such
Contributor explicitly and finally terminates Your grants, and (b) on an
ongoing basis, if such Contributor fails to notify You of the
non-compliance by some reasonable means prior to 60 days after You have
come back into compliance. Moreover, Your grants from a particular
Contributor are reinstated on an ongoing basis if such Contributor
notifies You of the non-compliance by some reasonable means, this is the
first time You have received notice of non-compliance with this License
from such Contributor, and You become compliant prior to 30 days after
Your receipt of the notice.

5.2. If You initiate litigation against any entity by asserting a patent
infringement claim (excluding declaratory judgment actions,
counter-claims, and cross-claims) alleging that a Contributor Version
directly or indirectly infringes any patent, then the rights granted to
You by any and all Contributors for the Covered Software under Section
2.1 of this License shall terminate.

5.3. In the event of termination under Sections 5.1 or 5.2 above, all
end user license agreements (excluding distributors and resellers) which
have been validly granted by You or Your distributors under this License
prior to termination shall survive termination.

************************************************************************
*                                                                      *
*  6. Disclaimer of Warranty                                           *
*  -------------------------                                           *
*                                                                      *
*  Covered Software is provided under this License on an "as is"       *
*  basis, without warranty of any kind, either expressed, implied, or  *
*  statutory, including, without limitation, warranties that the       *
*  Covered Software is free of defects, merchantable, fit for a        *
*  particular purpose or non-infringing. The entire risk as to the     *
*  quality and performance of the Covered Software is with You.        *
*  Should any Covered Software prove defective in any respect, You     *
*  (not any Contributor) assume the cost of any necessary servicing,   *
*  repair, or correction. This disclaimer of warranty constitutes an   *
*  essential part of this License. No use of any Covered Software is   *
*  authorized under this License except under this disclaimer.         *
*                                                                      *
************************************************************************

************************************************************************
*                                                                      *
*  7. Limitation of Liability                                          *
*  --------------------------                                          *
*                                                                      *
*  Under no circumstances and under no legal theory, whether tort      *
*  (including negligence), contract, or otherwise, shall any           *
*  Contributor, or anyone who distributes Covered Software as          *
*  permitted above, be liable to You for any direct, indirect,         *
*  special, incidental, or consequential damages of any character      *
*  including, without limitation, damages for lost profits, loss of    *
*  goodwill, work stoppage, computer failure or malfunction, or any    *
*  and all other commercial damages or losses, even if such party      *
*  shall have been informed of the possibility of such damages. This   *
*  limitation of liability shall not apply to liability for death or   *
*  personal injury resulting from such party's negligence to the       *
*  extent applicable law prohibits such limitation. Some               *
*  jurisdictions do not allow the exclusion or limitation of           *
*  incidental or consequential damages, so this exclusion and          *
*  limitation may not apply to You.                                    *
*                                                                      *
************************************************************************

8. Litigation
-------------

Any litigation relating to this License may be brought only in the
courts of a jurisdiction where the defendant maintains its principal
place of business and such litigation shall be governed by laws of that
jurisdiction, without reference to its conflict-of-law provisions.
Nothing in this Section shall prevent a party's ability to bring
cross-claims or counter-claims.

9. Miscellaneous
----------------

This License represents the complete agreement concerning the subject
matter hereof. If any provision of this License is held to be
unenforceable, such provision shall be reformed only to the extent
necessary to make it enforceable. Any law or regulation which provides
that the language of a contract shall be construed against the drafter
shall not be used to construe this License against a Contributor.

10. Versions of the License
---------------------------

10.1. New Versions

Mozilla Foundation is the license steward. Except as provided in Section
10.3, no one other than the license steward has the right to modify or
publish new versions of this License. Each version will be given a
distinguishing version number.

10.2. Effect of New Versions

You may distribute the Covered Software under the terms of the version
of the License under which You originally received the Covered Software,
or under the terms of any subsequent version published by the license
steward.

10.3. Modified Versions

If you create software not governed by this License, and you want to
create a new license for such software, you may create and use a
modified version of this License if you rename the license and remove
any references to the name of the license steward (except to note that
such modified license differs from this License).

10.4. Distributing Source Code Form that is Incompatible With Secondary
Licenses

If You choose to distribute Source Code Form that is Incompatible With
Secondary Licenses under the terms of this version of the License, the
notice described in Exhibit B of this License must be attached.

Exhibit A - Source Code Form License Notice
-------------------------------------------

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at https://mozilla.org/MPL/2.0/.

If it is not possible or desirable to put the notice in a particular
file, then You may include the notice in a location (such as a LICENSE
file in a relevant directory) where a recipient would be likely to look
for such a notice.

You may add additional accurate notices of copyright ownership.

Exhibit B - "Incompatible With Secondary Licenses" Notice
---------------------------------------------------------

  This Source Code Form is "Incompatible With Secondary Licenses", as
  defined by the Mozilla Public License, v. 2.0.
```

### SQLCipher-Community-Edition

```
Copyright (c) 2008-2026, ZETETIC, LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Unicode-3.0

```
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2023 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```

### Unlicense

```
This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or distribute this software, either in source code form or as a compiled binary, for any purpose, commercial or non-commercial, and by any means.

In jurisdictions that recognize copyright laws, the author or authors of this software dedicate any and all copyright interest in the software to the public domain. We make this dedication for the benefit of the public at large and to the detriment of our heirs and
successors. We intend this dedication to be an overt act of relinquishment in perpetuity of all present and future rights to this software under copyright law.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

For more information, please refer to <http://unlicense.org/>
```

### Zlib

```
zlib License

This software is provided 'as-is', without any express or implied warranty.  In no event will the authors be held liable for any damages arising from the use of this software.

Permission is granted to anyone to use this software for any purpose, including commercial applications, and to alter it and redistribute it freely, subject to the following restrictions:

     1. The origin of this software must not be misrepresented; you must not claim that you wrote the original software. If you use this software in a product, an acknowledgment in the product documentation would be appreciated but is not required.

     2. Altered source versions must be plainly marked as such, and must not be misrepresented as being the original software.

     3. This notice may not be removed or altered from any source distribution.
```

