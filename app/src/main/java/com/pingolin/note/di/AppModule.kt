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

package com.pingolin.note.di

import android.content.Context
import com.pingolin.note.BuildConfig
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.util.CircuitBreakerInterceptor
import com.pingolin.note.util.DatabasePassphraseManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.pingolin.note.util.DatabaseRawKey
import com.pingolin.note.util.KeystoreEncryption
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.RateLimitInterceptor
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import com.pingolin.note.util.TokenAuthenticator
import com.pingolin.note.util.refusingCompromisedKeys
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the set of API hosts allowed to receive the session bearer
 * token. See [AppModule.provideApiHosts] for what is in that set, and what
 * is deliberately kept out of it.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiHosts

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("note_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager {
        return SessionManager(context)
    }

    @Provides
    @Singleton
    fun provideSenderKeyPinStore(
        @ApplicationContext context: Context,
        sessionManager: SessionManager,
    ): SenderKeyPinStore {
        return SenderKeyPinStore(context, sessionManager)
    }

    @Provides
    @Singleton
    fun provideNoteGenFloorStore(
        @ApplicationContext context: Context,
        sessionManager: SessionManager,
    ): NoteGenFloorStore {
        return NoteGenFloorStore(context, sessionManager)
    }

    /** The web page's kept trust: its pins, floors and owners, outside the WebView. */
    @Provides
    @Singleton
    fun provideKeptWebTrustStore(
        @ApplicationContext context: Context,
    ): com.pingolin.note.util.KeptWebTrustStore {
        return com.pingolin.note.util.KeptWebTrustStore(context)
    }

    @Provides
    @Singleton
    fun provideRetainedContentKeyStore(
        @ApplicationContext context: Context,
        sessionManager: SessionManager,
    ): com.pingolin.note.util.RetainedContentKeyStore {
        return com.pingolin.note.util.RetainedContentKeyStore(context, sessionManager)
    }

    /** Each account's typed sign-in e-mail. */
    @Provides
    @Singleton
    fun provideAccountEmailStore(@ApplicationContext context: Context): com.pingolin.note.util.AccountEmailStore {
        return com.pingolin.note.util.AccountEmailStore(context)
    }

    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return CryptoManager()
    }

    @Provides
    @Singleton
    fun provideNoteCrypto(): com.pingolin.note.collab.NoteCrypto {
        return com.pingolin.note.collab.DefaultNoteCrypto
    }

    @Provides
    @Singleton
    fun provideKeystoreEncryption(): KeystoreEncryption {
        return KeystoreEncryption()
    }

    @Provides
    @Singleton
    fun provideCircuitBreakerInterceptor(): CircuitBreakerInterceptor {
        return CircuitBreakerInterceptor()
    }

    @Provides
    @Singleton
    fun provideRateLimitInterceptor(): RateLimitInterceptor {
        return RateLimitInterceptor()
    }

    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool {
        return ConnectionPool(10, 5, TimeUnit.MINUTES)
    }

    /**
     * Hosts that legitimately receive the session bearer token: the app's own
     * API backends (auth + note), parsed from the configured base URLs so the
     * set tracks dev/prod automatically. Consumed by BOTH the token-attach
     * interceptor below and [TokenAuthenticator] (one source of truth). The
     * presigned R2 attachment upload (AttachmentUploader) reuses this shared
     * client but targets a raw Cloudflare storage host — self-authorizing via
     * the signed URL — so it is deliberately NOT in
     * this set: the JWT must never leak into R2-side request logs, neither on
     * the first request nor on a 401 retry.
     */
    @Provides
    @Singleton
    @ApiHosts
    fun provideApiHosts(): Set<String> = listOfNotNull(
        BuildConfig.AUTH_BASE_URL.toHttpUrlOrNull()?.host,
        BuildConfig.NOTE_API_BASE_URL.toHttpUrlOrNull()?.host,
    ).toSet()

    /** System trust, refusing every path through a [com.pingolin.note.util.CompromisedKeys] key: on the client below and AuthMePrefetch. */
    @Provides
    @Singleton
    fun provideCompromisedKeyTrust(@ApplicationContext context: Context): com.pingolin.note.util.CompromisedKeyTrustManager =
        com.pingolin.note.util.CompromisedKeyTrustManager.platform(context.noBackupFilesDir, debug = BuildConfig.DEBUG)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        sessionManager: SessionManager,
        tokenAuthenticator: TokenAuthenticator,
        circuitBreakerInterceptor: CircuitBreakerInterceptor,
        rateLimitInterceptor: RateLimitInterceptor,
        connectionPool: ConnectionPool,
        @ApiHosts apiHosts: Set<String>,
        trust: com.pingolin.note.util.CompromisedKeyTrustManager,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .refusingCompromisedKeys(trust)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            // Token injection + identifiable User-Agent. The auth backend
            // labels sessions (Devices list) from the UA, so the device
            // model has to be in it — without it every session shows up as
            // the generic "on Android". Format:
            // "Note/<ver> (<manufacturer> <model>; Android <release>) okhttp",
            // with the model sanitized to a safe charset and capped so it
            // can't break the header or the server's parser.
            .addInterceptor { chain ->
                val original = chain.request()
                val deviceModel =
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                        .replace(Regex("[^A-Za-z0-9 ._-]"), "")
                        .trim()
                        .take(60)
                val request = original.newBuilder()
                    .header(
                        "User-Agent",
                        "Note/${BuildConfig.VERSION_NAME} ($deviceModel; Android ${android.os.Build.VERSION.RELEASE}) okhttp",
                    )
                // Session-less anonymous requests (e.g. the E2EE crash reporter,
                // which POSTs to an AUTH host — in @ApiHosts — but is contractually
                // NOT linked to the user account) opt OUT of auth via this sentinel
                // header. Strip it so it never hits the wire, send NO Authorization
                // even though the host is ours, and drop the device-identifying UA
                // so the report can't be fingerprinted back to this device/OS build.
                // Pairs with the matching bail in TokenAuthenticator so a 401 can't
                // refresh-and-re-attach a bearer.
                val isAnon = original.header("X-Pingolin-Anon") != null
                if (isAnon) {
                    request.removeHeader("X-Pingolin-Anon")
                    request.header("User-Agent", "Note/${BuildConfig.VERSION_NAME} okhttp")
                } else if (original.url.host in apiHosts && original.header("Authorization") == null) {
                    // Only attach the session bearer to our
                    // own API hosts — see [provideApiHosts]
                    // for what is deliberately NOT in that
                    // set. Never OVERRIDE an
                    // explicitly-supplied Authorization
                    // header: the worker-run push-unregister
                    // replay (PushUnregisterWorker) carries
                    // the DEPARTED account's own bearer, which
                    // must not be clobbered/duplicated with
                    // the current session's token.
                    sessionManager.getAccessToken()?.let { token ->
                        request.addHeader("Authorization", "Bearer $token")
                    }
                }
                chain.proceed(request.build())
            }
            .addInterceptor(circuitBreakerInterceptor)
            // Rate limit handling (retry on 429)
            .addInterceptor(rateLimitInterceptor)
            // Automatic token refresh on 401
            .authenticator(tokenAuthenticator)

        // No certificate pinning, on purpose. Every host sits behind
        // Cloudflare Universal SSL, which picks the issuing CA and
        // rotates it without notice, so a pin here would hard-fail every
        // request the day Cloudflare swaps CA. Pinning would need a fixed
        // CA (Advanced Certificate Manager), root pins with a backup, and
        // a remote kill switch. The app uses system trust, less any path
        // through a key known to be compromised (CompromisedKeys).

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                    redactHeader("X-Api-Key")
                    redactHeader("X-Internal-Api-Key")
                }
            )
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(okHttpClient: OkHttpClient): AuthApi {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.AUTH_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNoteApi(okHttpClient: OkHttpClient): NoteApi {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.NOTE_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NoteApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNoteDatabase(@ApplicationContext context: Context): NoteDatabase {
        val passphrase = DatabasePassphraseManager.getOrCreatePassphrase(context)
        val dbFile = context.getDatabasePath(NoteDatabase.DB_NAME)

        // Open with the RAW key when we have a verified one, which makes
        // SQLCipher use it directly instead of stretching it through 256,000
        // rounds of PBKDF2 — ~509ms of CPU per connection on an A50, over a
        // passphrase that is already full-entropy. Falls back to the passphrase
        // on its own whenever anything is off; see DatabaseRawKey.
        val key = DatabaseRawKey.keyFor(context, dbFile, passphrase)
        val db = NoteDatabase.create(context, key)

        // Derive-and-verify for NEXT time, never on the way in: the one
        // derivation is itself a full 256k-round PBKDF2. This provider already
        // runs off the main thread (the launch sync is what first asks for the
        // database), but the work is still pushed onto IO so it cannot delay
        // the query that triggered it — nothing waits on the result, and until
        // it lands the app simply keeps using the passphrase.
        if (key === passphrase) {
            CoroutineScope(Dispatchers.IO).launch {
                DatabaseRawKey.ensureCached(context, dbFile, passphrase)
            }
        }
        return db
    }

    @Provides
    @Singleton
    fun provideItemDao(database: NoteDatabase): ItemDao {
        return database.itemDao()
    }

    @Provides
    @Singleton
    fun provideReminderDao(database: NoteDatabase): com.pingolin.note.data.local.dao.ReminderDao {
        return database.reminderDao()
    }
}
