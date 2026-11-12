/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Note.
 *
 * Pingolin Note is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Note is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Note. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")
    id("kotlin-parcelize")
    // google-services is declared here but APPLIED CONDITIONALLY at the bottom
    // of this file. It aborts any build it runs in when app/google-services.json
    // is absent, and the `libre` distribution flavour deliberately ships no
    // Firebase at all — so applying it unconditionally would make a Google-free
    // build impossible. See "APPLYING google-services" at the end of this file.
    id("com.google.gms.google-services") apply false
    id("com.jaredsburrows.license")
    // Consumes :baselineprofile's output. Generation is explicit
    // (`:app:generateDevGmsReleaseBaselineProfile`, needs a signed-in device
    // running API 33+ or rooted); ordinary builds just bake in the committed
    // src/main/generated/baselineProfiles/baseline-prof.txt.
    id("androidx.baselineprofile")
}

import java.util.Properties
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Release signing material comes from the environment; there is deliberately no
// default path here. Official release builds never hand Gradle the keystore at
// all: PINGOLIN_KEYSTORE_* is removed from the environment before ./gradlew runs
// and the release artifacts are signed afterwards with apksigner/jarsigner — so
// "unset" is the normal case rather than an error, and the release build type
// below then attaches no signingConfig.
val releaseKeystore = System.getenv("PINGOLIN_KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }
val releaseKeystorePassword = System.getenv("PINGOLIN_KEYSTORE_PASSWORD")
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
    ?: ""
val releaseSigningAvailable = releaseKeystore != null && releaseKeystorePassword.isNotBlank()

if (releaseKeystore == null && releaseKeystorePassword.isNotBlank()) {
    logger.warn(
        "Release signing disabled: a keystore password is set but PINGOLIN_KEYSTORE_PATH " +
            "is unset or points at a missing file. Export it to sign from Gradle."
    )
}

android {
    lint {
        // What the baseline holds is understood and inert: the theme's
        // `android:windowLightNavigationBar` needs API 27 against minSdk 26,
        // and an unknown theme attribute is simply dropped by the resource
        // system on older releases. Fixing it "properly" means splitting the
        // theme into a Base + a values-v27 override, which is more risk than
        // the attribute is worth.
        //
        // The baseline exists so lint FAILS ON NEW ERRORS. A NewApi call
        // that slips through crashes older Android releases, and a
        // `catch (Exception)` cannot catch the NoSuchMethodError it throws.
        // Regenerate deliberately with `./gradlew updateLintBaseline` — and
        // read the diff, because anything added here is a check being
        // switched off.
        baseline = file("lint-baseline.xml")
    }
    namespace = "com.pingolin.note"
    compileSdk = 36
    // Without an explicit ndkVersion AGP cannot resolve llvm-strip, and
    // stripDevGmsReleaseDebugSymbols silently degrades to a copy — the build
    // log says so only in an ordinary line ("Unable to strip the following
    // libraries, packaging them as they are: ... libnote_collab_sdk.so,
    // libpingolin_mobile.so ..."). The two Rust libraries then ship their
    // full symbol tables: 2,834,432 bytes of APK, since native libs are Stored
    // (uncompressed), byte for byte. r27c is what built the committed binaries.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pingolindev.note"
        minSdk = 26
        targetSdk = 36

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        versionCode = (versionProps["VERSION_CODE"] as? String)?.toIntOrNull() ?: 1
        versionName = (versionProps["VERSION_NAME"] as? String) ?: "1.0.0"

        testInstrumentationRunner = "com.pingolin.note.HiltTestRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("PINGOLIN_KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("PINGOLIN_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Removes resources R8 proved unreachable — measured
            // at −840,894 bytes (145 res entries + the arsc
            // rewrite). A resource looked up only by name is
            // invisible to it and needs a tools:keep rule in
            // res/raw/keep.xml; none is looked up that way now.
            isShrinkResources = true
            // SYMBOL_TABLE puts the stripped symbols into the AAB's
            // BUNDLE-METADATA for Play native crash symbolication;
            // without it Play receives none at all. This is also
            // why the Rust side must NOT set strip = "symbols" in
            // its profiles: a pre-stripped .so would leave AGP
            // nothing to hand over, for identical APK bytes.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig is attached when the keystore is missing, so a
            // fresh clone can still assemble an (unsigned) release build.
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Dimension ORDER sets the variant name: environment first, then
    // distribution, so variants read `devGmsDebug` / `prodLibreRelease`.
    flavorDimensions += listOf("environment", "distribution")
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "AUTH_BASE_URL", "\"https://api-auth.pingolindev.com/\"")
            // Host for the https verify-email autoVerify App Link. MUST match
            // AUTH_BASE_URL's host per flavor — the shared manifest references
            // it via ${authDeepLinkHost} so each flavor verifies links on its
            // own auth host instead of a single hardcoded (dev) one.
            manifestPlaceholders["authDeepLinkHost"] = "api-auth.pingolindev.com"
            buildConfigField("String", "NOTE_API_BASE_URL", "\"https://note.pingolindev.com/\"")
            buildConfigField("String", "ACCOUNT_BASE_URL", "\"https://account.pingolindev.com/\"")
            // Track web: the account deletion's erasure chain goes through it (ShellNavigation).
            buildConfigField("String", "TRACK_WEB_BASE_URL", "\"https://track.pingolindev.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"DEV\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "AUTH_BASE_URL", "\"https://api-auth.pingolin.com/\"")
            // Host for the https verify-email autoVerify App Link — see the dev
            // flavor. Must match AUTH_BASE_URL's prod host, or prod verify-email
            // deep links never fire.
            manifestPlaceholders["authDeepLinkHost"] = "api-auth.pingolin.com"
            buildConfigField("String", "NOTE_API_BASE_URL", "\"https://note.pingolin.com/\"")
            buildConfigField("String", "ACCOUNT_BASE_URL", "\"https://account.pingolin.com/\"")
            // Track web: the account deletion's erasure chain goes through it (ShellNavigation).
            buildConfigField("String", "TRACK_WEB_BASE_URL", "\"https://track.pingolin.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"PROD\"")
            // There is no certificate-pinning flag: see the note in
            // di/AppModule.kt (Cloudflare Universal SSL rotates the issuing CA).
        }

        // DISTRIBUTION — how much Google ships inside the APK.
        //
        // `gms` is the default build: Firebase Cloud Messaging for push and the
        // Play Integrity API for registration attestation.
        //
        // `libre` resolves NONE of those artifacts (see `gmsImplementation`
        // below) so the APK qualifies as FLOSS. The runtime needs no special
        // handling for it — the app already degrades on a de-Googled device:
        // `AttestationManager.detectPlatform()` falls through Play Integrity ->
        // hardware attestation -> Turnstile, and `PushNotificationManager`
        // already prefers UnifiedPush whenever a distributor is installed. What
        // the flavour changes is which of the two `PlayIntegrityManager` /
        // `FcmTokenProvider` implementations (src/gms vs src/libre) is compiled
        // in, and the libre pair simply reports "unavailable".
        create("gms") {
            dimension = "distribution"
            isDefault = true
            // No applicationIdSuffix / versionNameSuffix, ON PURPOSE: the
            // application IDs must stay exactly `com.pingolindev.note.dev` /
            // `com.pingolindev.note` — they are what the Play listing, the
            // Firebase project and the App Links assetlinks.json are all
            // keyed on.
        }
        create("libre") {
            dimension = "distribution"
            // Suffixed so both APKs can sit on one device. The
            // manifest never hardcodes the id (both providers use
            // `${applicationId}.…`).
            // NOTE: a suffixed id is NOT in the assetlinks.json
            // served by the auth host, so the https verify-email App
            // Link does not autoVerify on a libre build.
            applicationIdSuffix = ".libre"
            versionNameSuffix = "-libre"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // gradle.properties sizes the Gradle DAEMON; it does not reach a
                // forked test worker, which otherwise gets Gradle's 512m default.
                // Robolectric sandboxes plus MockK/kotlin-reflect metadata
                // accumulate across a run and exhaust that, and the worker dying
                // surfaces as a bare `java.io.EOFException` on the task with NO
                // test XML written at all — so the suite looks broken rather than
                // out of memory, and a single-class run still passes. Fork a fresh
                // JVM per class so state cannot carry between them either.
                it.maxHeapSize = "3g"
                it.setForkEvery(1)
                // Each isolated worker is capped at 3 GiB,
                // so four classes running at once need up
                // to 12 GiB, without weakening the
                // per-class isolation.
                it.maxParallelForks = 4
            }
        }
    }

    packaging {
        resources {
            // fragment-testing + mockito-android + espresso-contrib all pull
            // junit-jupiter / junit-platform transitively, and each ships its
            // own META-INF/LICENSE.md (+ NOTICE.md, etc). Merge-java-res gets
            // confused by the duplicates — exclude them on the androidTest
            // classpath. Main app classpath is unaffected.
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                // 108 nested androidx entries, of which eight are byte-identical
                // 10KB copies of the same Apache-2.0 text. Real attribution
                // ships via generateThirdPartyLicenses + the in-app licences
                // screen; together they add up to 43KB of duplicate boilerplate.
                "META-INF/androidx/**",
                // Only kotlin-reflect reads these, and it is not on the
                // runtime classpath.
                "**/*.kotlin_builtins",
            )
        }
    }
}

// THIRD-PARTY ATTRIBUTION.
//
// Most of what this app links against is Apache-2.0, whose §4(d) obliges us to
// hand recipients the attribution notices along with the binary. The
// `com.jaredsburrows.license` plugin resolves a variant's runtime classpath and
// reads each dependency's POM `<licenses>` block; `generateThirdPartyLicenses`
// below turns that machine-readable report into the two things we actually
// ship: THIRD_PARTY_LICENSES.md at the repo root, and the offline HTML asset
// the in-app "Open source licenses" screen renders.
licenseReport {
    generateJsonReport = true
    generateHtmlReport = false
    generateCsvReport = false
    generateTextReport = false
    // The asset is written by `generateThirdPartyLicenses`, not by the plugin —
    // we need the manual additions and the JNA licence election merged in, and
    // the plugin's own template carries neither.
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
    showVersions = true
}

// WHAT A CLASSPATH SCAN CANNOT SEE.
//
// Three categories of shipped code carry no POM, so a report built from the
// licence plugin alone would understate what this APK actually redistributes:
//
//  1. The Rust crates compiled into library/src/main/jniLibs/**/*.so. Those
//     binaries are COMMITTED to this repository and packaged into the APK, so
//     the attribution obligation for every crate inside them lands here — it
//     cannot be left to the SDK repository they were built from. They are
//     inventoried in licenses/native-licenses.json by
//     licenses/collect-native-licenses.py.
//  2. Projects vendored inside a dependency's own native library — SQLite and
//     OpenSSL are both statically linked into SQLCipher's libsqlcipher.so and
//     are not separate artifacts anywhere.
//  3. Facts a POM cannot express: a dependency whose published <licenses> block
//     is empty (SQLCipher ships exactly that), and which licence we elect from
//     a dual-licensed offer (JNA).
//
// (2) and (3) are recorded by hand in licenses/supplement.json. This task
// merges all three sources into the two artifacts we ship: THIRD_PARTY_LICENSES.md
// at the repo root, and the offline HTML asset the in-app "Open source licenses"
// screen renders.
tasks.register("generateThirdPartyLicenses") {
    group = "reporting"
    description = "Merge the POM licence report, the native-crate inventory and the " +
        "hand-maintained supplement into THIRD_PARTY_LICENSES.md and the in-app asset."
    // prodGmsRelease, not prodLibreRelease: gms is the SUPERSET of the two
    // distribution flavours, so a report built from it covers everything either
    // APK can contain. (It is also the only one that works — the `proprietary`
    // section of licenses/supplement.json is keyed on com.google.android.gms,
    // which is absent from the libre classpath and would trip the stale-entry
    // check below.) The libre APK therefore ships an attribution list that names
    // a few components it does not actually contain, which over-discloses rather
    // than under-discloses.
    dependsOn("licenseProdGmsReleaseReport")

    val pomReport = layout.buildDirectory.file("reports/licenses/licenseProdGmsReleaseReport.json")
    val nativeJson = rootProject.file("licenses/native-licenses.json")
    val supplementJson = rootProject.file("licenses/supplement.json")
    val textsDir = rootProject.file("licenses/texts")
    val markdownOut = rootProject.file("THIRD_PARTY_LICENSES.md")
    val assetOut = file("src/main/assets/open_source_licenses.html")

    // pomReport too, or the task is UP-TO-DATE when a dependency changes but
    // the three hand-maintained files do not — shipping a stale attribution
    // list, which for this project is a correctness problem, not cosmetics.
    inputs.file(pomReport)
    inputs.file(nativeJson)
    inputs.file(supplementJson)
    inputs.dir(textsDir)
    outputs.files(markdownOut, assetOut)

    doLast {
        val slurper = groovy.json.JsonSlurper()

        @Suppress("UNCHECKED_CAST")
        val pomEntries = slurper.parse(pomReport.get().asFile) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val crates = (slurper.parse(nativeJson) as Map<String, Any?>)["crates"]
            as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val supplement = slurper.parse(supplementJson) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun section(name: String) =
            (supplement[name] as? Map<String, Map<String, Any?>>) ?: emptyMap()
        val overrides = section("overrides")
        val elections = section("elections")
        val proprietary = section("proprietary")
        val embedded = section("embedded")
        // Upstream projects whose SOURCE is copied into this repository. Like
        // `embedded` these carry no POM on the classpath, but the reason
        // differs and so does the obligation: we redistribute their source, not
        // just their compiled form, and we have modified it. They get their own
        // section rather than being filed under "embedded in other components",
        // which would misdescribe them. Exempt from the stale-coordinate check
        // below for the same reason `embedded` is — there is no coordinate.
        val vendored = section("vendored")
        @Suppress("UNCHECKED_CAST")
        val aliases = (supplement["spdx_aliases"] as? Map<String, String>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val licenceUrls = (supplement["license_urls"] as? Map<String, String>) ?: emptyMap()

        // "group:artifact:version" -> "group:artifact"
        fun coordOf(dependency: String) = dependency.substringBeforeLast(':')

        // A supplement entry keyed on a coordinate that is not on the
        // classpath is worse than useless: it reads as a live statement about
        // something the app ships. Fail rather than drop it silently.
        // Proprietary keys may be a bare group (com.google.android.gms), so
        // match on prefix.
        val coords = pomEntries.map { coordOf(it["dependency"] as String) }.toSet()
        val stale = (overrides.keys + elections.keys).filterNot { it in coords } +
            proprietary.keys.filterNot { key -> coords.any { it == key || it.startsWith("$key:") } }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "licenses/supplement.json refers to dependencies that are no longer on the " +
                    "prodGmsRelease runtime classpath: $stale. Remove the entries or fix the coordinates."
            )
        }

        // POM <name> is free text, so the same licence arrives under many
        // spellings. Fold them onto SPDX so the summary counts each licence once.
        fun spdx(name: String) = aliases[name.trim()] ?: name.trim()

        fun licenceOf(entry: Map<String, Any?>): String {
            val coord = coordOf(entry["dependency"] as String)
            overrides[coord]?.get("license")?.let { return it as String }
            elections[coord]?.get("elected")?.let { return "${it as String} (elected)" }
            @Suppress("UNCHECKED_CAST")
            val declared = (entry["licenses"] as? List<Map<String, Any?>>).orEmpty()
                .mapNotNull { (it["license"] as? String)?.trim()?.ifEmpty { null } }
                .map { spdx(it) }
            return if (declared.isEmpty()) "UNDECLARED" else declared.distinct().joinToString(" OR ")
        }

        fun urlFor(licence: String) = licenceUrls[licence.removeSuffix(" (elected)")]

        // A dependency whose POM states no usable licence must not be allowed to
        // ship as the word "UNDECLARED" in a file nobody re-reads. SQLCipher is
        // the proof this is not hypothetical: its POM carries a <license> with a
        // <url> and no <name>, which reads as a licence named "" rather than as a
        // missing one, so a check for an absent block would wave it through.
        // Attribution is a licence obligation, so an unattributable dependency
        // fails the build and names itself here instead.
        val undeclared = pomEntries
            .filter { licenceOf(it) == "UNDECLARED" }
            .map { it["dependency"] as String }
            .sorted()
        if (undeclared.isNotEmpty()) {
            throw GradleException(
                "No usable licence found for: $undeclared.\n" +
                    "Establish each one from the project's own licence text, then add it to the " +
                    "`overrides` section of licenses/supplement.json (keyed on group:artifact, " +
                    "without the version)."
            )
        }

        // ---- normalised rows, rendered twice (markdown + HTML) ----------------
        // Keys: name, version, licence, licenceUrl, url, note
        fun row(
            name: String, version: String, licence: String,
            url: String?, note: String?,
        ) = mapOf(
            "name" to name, "version" to version, "licence" to licence,
            "licenceUrl" to (urlFor(licence) ?: ""), "url" to (url ?: ""),
            "note" to (note ?: ""),
        )

        val jvmRows = pomEntries
            .sortedBy { (it["dependency"] as String).lowercase() }
            .map {
                val dependency = it["dependency"] as String
                row(
                    name = coordOf(dependency),
                    version = dependency.substringAfterLast(':'),
                    licence = licenceOf(it),
                    url = it["url"] as? String,
                    note = (overrides[coordOf(dependency)]?.get("note")
                        ?: proprietary.entries.firstOrNull { (k, _) ->
                            coordOf(dependency) == k || coordOf(dependency).startsWith("$k:")
                        }?.value?.get("note")) as? String,
                )
            }

        val crateRows = crates.map {
            @Suppress("UNCHECKED_CAST")
            val shippedIn = (it["shipped_in"] as? List<String>).orEmpty()
            row(
                name = it["name"] as String,
                version = it["version"] as String,
                licence = spdx(it["license"] as String),
                url = it["url"] as? String,
                note = shippedIn.joinToString(", "),
            )
        }

        val licenceTexts = (textsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".txt") }
            .sortedBy { it.name.lowercase() }

        val histogram = (jvmRows + crateRows)
            .map { it["licence"] as String }
            .groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        // ---- markdown --------------------------------------------------------
        val md = StringBuilder()
        fun line(text: String = "") { md.append(text).append('\n') }

        line("# Third-party licences")
        line()
        line("Pingolin Note for Android is licensed under the GNU General Public License,")
        line("version 3 or later (see [LICENSE](LICENSE)). The source code of every release")
        line("is at https://github.com/pingolindev/note-android-public. The web editor it")
        line("bundles is licensed under the GNU General Public License, version 3 or later,")
        line("too; its source is at https://github.com/pingolindev/note-web-public.")
        line("It links and redistributes the third-party components listed below, each under")
        line("its own licence.")
        line()
        line("**This file is generated — do not edit it by hand.** Run:")
        line()
        line("```")
        line("./gradlew :app:generateThirdPartyLicenses")
        line("```")
        line()
        line("| Source | Covers | Produced by |")
        line("|---|---|---|")
        line("| `prodGmsRelease` runtime classpath | ${jvmRows.size} JVM/Android dependencies " +
            "| `com.jaredsburrows.license`, reading each POM's `<licenses>` block |")
        line("| `licenses/native-licenses.json` | ${crateRows.size} Rust crates " +
            "| `licenses/collect-native-licenses.py` |")
        line("| `licenses/supplement.json` | corrections, elections, embedded, vendored and " +
            "proprietary components | maintained by hand |")
        line()
        line("## Why the native crates are listed here")
        line()
        line("The prebuilt `.so` files under `library/src/main/jniLibs/` are committed to this")
        line("repository and packaged into the APK. Every Rust crate compiled into them is")
        line("therefore redistributed by this project, and its notice belongs here rather than")
        line("only in the SDK repository the binaries were built from. A licence scan of the")
        line("JVM classpath alone would miss all ${crateRows.size} of them.")
        line()

        if (elections.isNotEmpty()) {
            line("## Licence elections")
            line()
            line("Where a dependency offers a choice of licences, this is the one we take.")
            line()
            for ((coord, entry) in elections) {
                @Suppress("UNCHECKED_CAST")
                val offered = (entry["offered"] as? List<String>).orEmpty()
                line("### `$coord` — ${entry["elected"]}")
                line()
                line("Offered under ${offered.joinToString(" or ")}. " +
                    "**Elected: ${entry["elected"]}.**")
                line()
                (entry["note"] as? String)?.let { line(it); line() }
            }
        }

        if (overrides.isNotEmpty()) {
            line("## Corrections to published metadata")
            line()
            line("Dependencies whose published POM does not state a usable licence. Each value")
            line("below was established from the project's own licence text, not from a")
            line("third-party licence database.")
            line()
            for ((coord, entry) in overrides) {
                line("### `$coord` — ${entry["license"]}")
                line()
                (entry["note"] as? String)?.let { line(it); line() }
            }
        }

        if (embedded.isNotEmpty()) {
            line("## Components embedded in other components")
            line()
            line("Upstream projects statically linked inside a dependency's own native library.")
            line("They are not separate artifacts, so no classpath or crate scan reaches them,")
            line("but their code ships in the APK.")
            line()
            line("| Component | Version | Licence | Inside |")
            line("|---|---|---|---|")
            for ((name, entry) in embedded) {
                line("| $name | ${entry["version"] ?: "—"} | ${entry["license"]} " +
                    "| ${entry["within"]} |")
            }
            line()
            for ((name, entry) in embedded) {
                (entry["note"] as? String)?.let { line("**$name.** $it"); line() }
            }
        }

        if (vendored.isNotEmpty()) {
            line("## Components vendored into this repository")
            line()
            line("Upstream source copied into this repository and built from source here, rather")
            line("than consumed as a published artifact. No classpath scan reaches them — there")
            line("is no coordinate to scan — but their code ships in the APK and this project")
            line("redistributes their source. Where the copy has been modified, that is stated;")
            line("the licence obligations of the upstream project continue to apply to it.")
            line()
            line("| Component | Version | Licence | Path in this repository | Modified |")
            line("|---|---|---|---|---|")
            for ((name, entry) in vendored) {
                line("| $name | ${entry["version"] ?: "—"} | ${entry["license"]} " +
                    "| `${entry["path"]}` | ${if (entry["modified"] == true) "yes" else "no"} |")
            }
            line()
            for ((name, entry) in vendored) {
                (entry["note"] as? String)?.let { line("**$name.** $it"); line() }
            }
        }

        if (proprietary.isNotEmpty()) {
            line("## Components that are not open source")
            line()
            line("These ship in the APK but carry no open-source grant. They are redistributed")
            line("under their own terms, linked below.")
            line()
            line("| Component | Terms |")
            line("|---|---|")
            for ((coord, entry) in proprietary) {
                line("| `$coord` | [${entry["license"]}](${entry["license_url"]}) |")
            }
            line()
        }

        fun table(title: String, intro: List<String>, rows: List<Map<String, String>>, header: String) {
            line("## $title")
            line()
            intro.forEach { line(it) }
            if (intro.isNotEmpty()) line()
            line(header)
            line(header.replace(Regex("[^|]+"), "---"))
            for (r in rows) {
                val name = if (r["url"].isNullOrEmpty()) r["name"] else "[${r["name"]}](${r["url"]})"
                val licence = if (r["licenceUrl"].isNullOrEmpty()) r["licence"]
                    else "[${r["licence"]}](${r["licenceUrl"]})"
                line("| $name | ${r["version"]} | $licence | ${r["note"]} |")
            }
            line()
        }

        table(
            "JVM and Android dependencies (${jvmRows.size})",
            listOf("Resolved from the `prodGmsRelease` runtime classpath."),
            jvmRows,
            "| Component | Version | Licence | Notes |",
        )
        table(
            "Rust crates in the bundled native libraries (${crateRows.size})",
            listOf("Statically linked into the committed `.so` files, for both `arm64-v8a`",
                "and `armeabi-v7a`."),
            crateRows,
            "| Crate | Version | Licence | Shipped in |",
        )

        line("## Licence summary")
        line()
        line("| Licence | Components |")
        line("|---|---|")
        histogram.forEach { (licence, n) -> line("| $licence | $n |") }
        line()

        line("## Licence texts")
        line()
        line("Verbatim copies of every licence named above, from `licenses/texts/`. Apache-2.0")
        line("section 4(a) and MPL-2.0 section 3.2 both require a copy to travel with the")
        line("distribution; the rest are reproduced for the same reason rather than linked.")
        line("Each project's own copyright line lives in its repository, linked in the tables")
        line("above.")
        line()
        for (file in licenceTexts) {
            line("### ${file.nameWithoutExtension}")
            line()
            line("```")
            file.readLines().forEach { line(it) }
            line("```")
            line()
        }

        markdownOut.writeText(md.toString())

        // ---- offline asset for the in-app screen ------------------------------
        // Real markup rather than a <pre> dump of the markdown: this is what the
        // licences screen shows, and it has to be readable on a phone. It must
        // also open in a WebView with no network and no third-party renderer, so
        // everything is inline and there is not a single external reference.
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun link(text: String, href: String) =
            if (href.isEmpty()) esc(text) else "<a href=\"${esc(href)}\">${esc(text)}</a>"

        val html = StringBuilder()
        fun h(text: String) { html.append(text).append('\n') }

        h("<!doctype html>")
        h("<html lang=\"en\"><head><meta charset=\"utf-8\">")
        h("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        h("<title>Open source licenses</title>")
        h("<style>")
        h(":root{color-scheme:light dark;--fg:#1b1b1b;--muted:#5f5f5f;--rule:#d7d7d7;--bg:#fff}")
        h("@media(prefers-color-scheme:dark){:root{--fg:#e6e6e6;--muted:#a0a0a0;--rule:#3a3a3a;--bg:#121212}}")
        h("body{margin:0;padding:16px;background:var(--bg);color:var(--fg);")
        h("font:15px/1.55 -apple-system,Roboto,sans-serif;overflow-wrap:break-word}")
        h("h1{font-size:1.4rem;margin:0 0 .4em}h2{font-size:1.1rem;margin:1.6em 0 .4em;")
        h("padding-top:.6em;border-top:1px solid var(--rule)}h3{font-size:1rem;margin:1.2em 0 .3em}")
        h("a{color:inherit}p{margin:.5em 0}")
        h(".c{border-bottom:1px solid var(--rule);padding:.55em 0}")
        h(".m{color:var(--muted);font-size:.85rem}")
        h("pre{white-space:pre-wrap;font-size:.78rem;color:var(--muted);")
        h("border:1px solid var(--rule);border-radius:6px;padding:10px;overflow-x:auto}")
        h("</style></head><body>")

        h("<h1>Open source licenses</h1>")
        h("<p>Pingolin Note for Android is free software, licensed under the GNU General " +
            "Public License, version 3 or later. The source code of every release is at " +
            "https://github.com/pingolindev/note-android-public. The web editor it bundles " +
            "is licensed under the GNU General Public License, version 3 or later, too; its source " +
            "is at https://github.com/pingolindev/note-web-public. It includes the " +
            "third-party components below, each under its own licence.</p>")

        if (elections.isNotEmpty() || overrides.isNotEmpty()) {
            h("<h2>Licence notes</h2>")
            for ((coord, entry) in elections) {
                @Suppress("UNCHECKED_CAST")
                val offered = (entry["offered"] as? List<String>).orEmpty()
                val elected = entry["elected"] as String
                // Naming only the elected licence is half a disclosure: a
                // reader cannot tell an election from a plain single-licence
                // dependency, so spell out what the offer was.
                h("<div class=\"c\"><b>${esc(coord)}</b> — offered under " +
                    offered.joinToString(" or ") { link(it, urlFor(it) ?: "") } +
                    "; elected ${link(elected, urlFor(elected) ?: "")}" +
                    "<div class=\"m\">${esc(entry["note"] as? String ?: "")}</div></div>")
            }
            for ((coord, entry) in overrides) {
                h("<div class=\"c\"><b>${esc(coord)}</b> — ${esc(entry["license"] as String)}" +
                    "<div class=\"m\">${esc(entry["note"] as? String ?: "")}</div></div>")
            }
        }

        if (embedded.isNotEmpty()) {
            h("<h2>Embedded components</h2>")
            for ((name, entry) in embedded) {
                h("<div class=\"c\"><b>${esc(name)}</b> ${esc(entry["version"] as? String ?: "")} — " +
                    link(entry["license"] as String, entry["license_url"] as? String ?: "") +
                    "<div class=\"m\">inside ${esc(entry["within"] as String)}</div></div>")
            }
        }

        if (vendored.isNotEmpty()) {
            h("<h2>Vendored into this repository</h2>")
            for ((name, entry) in vendored) {
                val modified = if (entry["modified"] == true) ", modified" else ""
                h("<div class=\"c\"><b>${esc(name)}</b> ${esc(entry["version"] as? String ?: "")} — " +
                    link(entry["license"] as String, entry["license_url"] as? String ?: "") +
                    "<div class=\"m\">built from source at ${esc(entry["path"] as String)}$modified</div></div>")
            }
        }

        if (proprietary.isNotEmpty()) {
            h("<h2>Not open source</h2>")
            for ((coord, entry) in proprietary) {
                h("<div class=\"c\"><b>${esc(coord)}</b> — " +
                    link(entry["license"] as String, entry["license_url"] as? String ?: "") +
                    "<div class=\"m\">${esc(entry["note"] as? String ?: "")}</div></div>")
            }
        }

        fun htmlRows(title: String, rows: List<Map<String, String>>) {
            h("<h2>$title</h2>")
            for (r in rows) {
                h("<div class=\"c\">${link(r["name"]!!, r["url"]!!)} ${esc(r["version"]!!)}" +
                    "<div class=\"m\">${link(r["licence"]!!, r["licenceUrl"]!!)}" +
                    (if (r["note"].isNullOrEmpty()) "" else " · ${esc(r["note"]!!)}") +
                    "</div></div>")
            }
        }
        htmlRows("JVM and Android dependencies (${jvmRows.size})", jvmRows)
        htmlRows("Rust crates in the bundled native libraries (${crateRows.size})", crateRows)

        h("<h2>Licence texts</h2>")
        for (file in licenceTexts) {
            h("<h3>${esc(file.nameWithoutExtension)}</h3>")
            h("<pre>${esc(file.readText())}</pre>")
        }

        h("</body></html>")

        assetOut.parentFile.mkdirs()
        assetOut.writeText(html.toString())

        logger.lifecycle(
            "THIRD_PARTY_LICENSES.md: ${jvmRows.size} JVM deps + ${crateRows.size} native crates " +
                "(${overrides.size} corrected, ${elections.size} elected, " +
                "${embedded.size} embedded, ${vendored.size} vendored, " +
                "${proprietary.size} proprietary), " +
                "${licenceTexts.size} licence texts"
        )
    }
}

// ROBOLECTRIC NEEDS JNA'S NATIVE LIBRARY, AND AGP HIDES IT.
//
// Robolectric's sandbox loads `libjnidispatch.so` out of the JNA jar's own
// resources before any test body runs. On the unit-test classpath that jar has
// been through AGP's artifact transform, which STRIPS native libraries — so the
// run dies with `UnsatisfiedLinkError: com/sun/jna/linux-x86-64/libjnidispatch.so
// not found in resource path`, however JNA is declared as a test dependency.
//
// `jnaNative` is a plain (non-Android) configuration, so it resolves the
// ORIGINAL artifact with its natives intact. The task unpacks them into the
// build directory and every Test task points JNA at that directory — no system
// package, no path outside the project.
val jnaNative: Configuration by configurations.creating

/// JNA ships one native per platform in its own folder, so take only the host's
/// — flattening all of them collides on the shared file name.
val jnaPlatformDir: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    when {
        os.contains("mac") || os.contains("darwin") -> "darwin-$arch"
        os.contains("windows") -> "win32-$arch"
        else -> "linux-$arch"
    }
}

val extractJnaNatives = tasks.register<Sync>("extractJnaNatives") {
    from(jnaNative.map { zipTree(it) }) {
        include("com/sun/jna/$jnaPlatformDir/*")
        eachFile { path = name }        // flatten: JNA wants a directory OF libraries
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("jna-natives"))
}

tasks.withType<Test>().configureEach {
    dependsOn(extractJnaNatives)
    systemProperty(
        "jna.boot.library.path",
        layout.buildDirectory.dir("jna-natives").get().asFile.absolutePath,
    )
}

// CONSUMER side of the baseline profile. The recording itself is configured in
// :baselineprofile; this is what happens to the result.
baselineProfile {
    // One profile for every variant. A profile is a list of code paths, not a
    // build-specific artifact, so the dev/gms recording is correct for prod and
    // for libre too.
    mergeIntoMain = true
    // Written to src/main/generated/baselineProfiles/baseline-prof.txt and
    // COMMITTED, so an ordinary build needs no device and produces the same APK
    // every time.
    saveInSrc = true
    // Never during an ordinary build: generation needs a running, signed-in
    // device and takes minutes. Run it deliberately with
    // `:app:generateDevGmsReleaseBaselineProfile`.
    automaticGenerationDuringBuild = false
}

dependencies {
    // Baseline profile: the generator module, and the runtime that installs the
    // shipped profile on devices where Play did not do it for us.
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // UniFFI Rust crypto SDK, shared with the other Pingolin mobile clients
    implementation(project(":library"))

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // WebViewCompat.addDocumentStartJavaScript — the ONLY injection point that
    // is guaranteed to run before the page's own scripts. onPageStarted races
    // them, and losing that race means the web app boots keyless and bounces
    // the user to the account portal.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Held at the versions the app has shipped with. Compose's ui pulled these
    // above what the remaining libraries ask for (appcompat: emoji2 1.3.0;
    // material: transition 1.5.0; slidingpanelayout: window 1.0.0), so taking
    // Compose out would have quietly moved the released app onto older ones.
    // Constraints, not dependencies: they choose the version of what is
    // already on the classpath and add nothing to it.
    constraints {
        implementation("androidx.emoji2:emoji2:1.4.0")
        implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
        implementation("androidx.transition:transition:1.6.0")
        implementation("androidx.window:window:1.5.0")
    }

    // Chip input for labels comes from the material dependency declared
    // earlier in this block, so it has no entry of its own.

    // Image loading (Coil 3, group io.coil-kt.coil3). No network loader, on
    // purpose: coil-network-okhttp builds its own OkHttpClient on plain system
    // trust, past util/CompromisedKeyTrust.kt, and no image here comes from the
    // network. One that must would come through the app's own client.
    implementation("io.coil-kt.coil3:coil:3.4.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Room + SQLCipher
    // Using sqlcipher-android 4.9.0 for 16KB page size support (Android 15+; landed in 4.6.1)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.9.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.6.2")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.11.1")

    // Firebase Cloud Messaging (tickle-based push — no content in push payload)
    // BOM 34.x has no KTX modules; the KTX APIs live in the main
    // firebase-messaging artifact.
    //
    // `gmsImplementation` (not `implementation`) — this and the Play artifacts
    // below are the entire Google surface of the APK, and scoping them to the
    // `gms` flavour is what makes the `libre` variant Google-free. Everything
    // that touches them lives in src/gms; src/main must stay clean or the libre
    // variant will not compile. Verify with:
    //   ./gradlew :app:dependencies --configuration prodLibreReleaseRuntimeClasspath
    //
    // QUOTED on purpose: `gmsImplementation(...)` unquoted is a type-safe
    // accessor, and the Kotlin DSL generates those from an earlier build's
    // model — so a flavour declared in this same file has no accessor on a
    // fresh clone or after `clean`, and the script fails to compile with
    // "Unresolved reference 'gmsImplementation'". The string form always
    // resolves. Do not "tidy" the quotes away.
    "gmsImplementation"(platform("com.google.firebase:firebase-bom:34.14.1"))
    "gmsImplementation"("com.google.firebase:firebase-messaging")

    // UnifiedPush — privacy-focused alternative to FCM. Auto-selected
    // when a distributor app (NextPush, ntfy, etc.) is installed and
    // FCM is unavailable / not preferred. https://unifiedpush.org
    implementation("org.unifiedpush.android:connector:3.0.3") {
        // The connector pulls Tink (JVM); androidx.security:security-crypto
        // already brings tink-android. Excluding the JVM variant avoids
        // a duplicate-class build failure.
        exclude(group = "com.google.crypto.tink", module = "tink")
    }

    // Play Integrity API (device trust attestation). gms-only — see the note on
    // firebase-messaging above. On libre, AttestationManager falls through to
    // Android Keystore hardware attestation, then Turnstile.
    "gmsImplementation"("com.google.android.play:integrity:1.4.0")
    "gmsImplementation"("com.google.android.gms:play-services-base:18.5.0")
    // `kotlinx.coroutines.tasks.await` — the Task<T> -> suspend bridge that both
    // src/gms classes use. Without this line it would arrive only transitively,
    // as `firebase-messaging -> firebase-common -> kotlinx-coroutines-play-
    // services:1.9.0` (visible in `:app:dependencies --configuration
    // prodGmsReleaseRuntimeClasspath`). Declaring it explicitly keeps a
    // compile-time import from depending on a transitive of a dependency that
    // can drop it.
    "gmsImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Biometric auth for note vault
    implementation("androidx.biometric:biometric:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // jBCrypt for SRP password preprocessing
    implementation("org.mindrot:jbcrypt:0.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("io.mockk:mockk:1.13.17")
    // OkHttp 5 moved MockWebServer to com.squareup.okhttp3:mockwebserver3
    // (package mockwebserver3, immutable MockResponse.Builder). This deliberately uses
    // the LEGACY artifact (still published in 5.x) so tests that use the
    // okhttp3.mockwebserver package + setResponseCode()/setBody() setters work
    // without a code rewrite.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // JNA declared EXPLICITLY so it resolves with its natives intact — see the
    // `jnaNative` configuration above for why the transitive artifact won't do.
    jnaNative("net.java.dev.jna:jna:5.18.1")
    testImplementation("androidx.test:core:1.6.1")
    // Real org.json for unit tests — the mockable android.jar stubs it,
    // which silently breaks every JSONObject-based content parse.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.60.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.17")
    androidTestImplementation("org.mockito:mockito-android:5.15.2")
    androidTestImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.work:work-testing:2.11.1")
    androidTestImplementation("androidx.fragment:fragment-testing:1.8.9")
    debugImplementation("androidx.fragment:fragment-testing-manifest:1.8.9")
}

// APPLYING google-services WITHOUT MAKING A GOOGLE-FREE BUILD IMPOSSIBLE.
//
// The plugin registers a `process<Variant>GoogleServices` task for EVERY variant
// and fails that task when app/google-services.json is absent. Applied
// unconditionally, that makes `:app:assembleProdLibreRelease` impossible without
// a Firebase file the libre APK never uses. (See README, "google-services.json —
// needed for gms builds only".)
//
// So apply it UNLESS the requested tasks name a libre variant and no gms one.
// The default when nothing names a variant (`./gradlew build`, `assemble`, an
// IDE sync, a plain test run) is to APPLY, deliberately: the failure mode of the
// opposite default is SILENT. A gms APK assembled with the plugin skipped still
// builds and installs — it just has no google_app_id resource, so FCM never
// registers and push is dead with nothing in the log to say why.
val requestedVariantTasks = gradle.startParameter.taskNames.filter {
    it.contains("gms", ignoreCase = true) || it.contains("libre", ignoreCase = true)
}
val libreOnlyInvocation = requestedVariantTasks.isNotEmpty() &&
    requestedVariantTasks.none { it.contains("gms", ignoreCase = true) }

if (!libreOnlyInvocation) {
    apply(plugin = "com.google.gms.google-services")

    // A mixed invocation (`./gradlew build`, or a gms and a libre task together)
    // has the plugin applied, so switch its task off for the libre variants as
    // well: a libre APK must carry no google_app_id / gcm_defaultSenderId string
    // resource whether or not a gms task happens to share the build.
    tasks.matching {
        it.name.startsWith("process") &&
            it.name.endsWith("GoogleServices") &&
            it.name.contains("Libre")
    }.configureEach { enabled = false }
}
