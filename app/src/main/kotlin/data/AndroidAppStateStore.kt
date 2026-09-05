// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import app.AppState
import app.requiresManagedTagCanonicalization
import app.withCanonicalManagedTagReferences
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidAppStateStore private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var database = buildDatabase()
    private var dao = database.appStateDao()
    private val settingsPreferences = AppSettingsPreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateLock = Any()
    private val deferredUpdates = DeferredStateUpdates<AppState>()
    private val saveMutex = Mutex()
    private val saveRevision = AtomicLong(0)
    private val hasPersistedState = AtomicBoolean(false)
    private val loadedState = loadInitialState()
    private val persistenceTracker = AppStatePersistenceTracker(loadedState.state)
    private val mutableState = MutableStateFlow(loadedState.state)

    init {
        hasPersistedState.set(loadedState.loadedFromDatabase)
    }

    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun update(transform: (AppState) -> AppState) {
        val pendingSave = synchronized(updateLock) {
            if (deferredUpdates.deferIfReplacing(canonicalAppStateUpdate(transform))) return
            applyImmediateUpdateLocked(transform)
        } ?: return

        persist(pendingSave.nextState, pendingSave.revision)
    }

    suspend fun replaceAllAndAwaitPersistence(nextState: AppState) {
        val canonicalNextState = nextState.withCanonicalManagedTagReferences()
        lateinit var previousState: AppState
        val replacementRevision = synchronized(updateLock) {
            previousState = mutableState.value
            if (canonicalNextState == previousState) return
            deferredUpdates.beginReplacement()
            saveRevision.incrementAndGet()
        }
        persistReplacementAndAwait(
            previousState = previousState,
            persistedState = canonicalNextState,
            revision = replacementRevision,
            forceReplaceAll = true,
            canonicalizeFinalState = true,
            persistBaseAfterFailure = true,
            deferredUpdateFailureMessage =
                "Discarded an invalid app state update deferred during replacement; continuing",
        )
            .getOrThrow()
    }

    internal suspend fun commitPreparedAndAwaitPersistence(
        expected: AppState,
        updated: AppState,
    ): Result<Boolean> {
        val revision = synchronized(updateLock) {
            if (deferredUpdates.mustRejectSynchronousMutation()) return Result.success(false)
            if (mutableState.value !== expected) return Result.success(false)
            if (updated == expected) return Result.success(true)
            deferredUpdates.beginReplacement()
            saveRevision.incrementAndGet()
        }
        return persistReplacementAndAwait(
            previousState = expected,
            persistedState = updated,
            revision = revision,
            forceReplaceAll = false,
            canonicalizeFinalState = false,
            persistBaseAfterFailure = false,
            deferredUpdateFailureMessage = "Discarded an invalid deferred update",
        ).map { true }
    }

    fun compareAndSet(expected: AppState, updated: AppState): Boolean {
        val pendingSave = synchronized(updateLock) {
            if (deferredUpdates.mustRejectSynchronousMutation()) return false
            if (mutableState.value !== expected) return false
            applyImmediateUpdateLocked { updated }
        }
        pendingSave?.let { save -> persist(save.nextState, save.revision) }
        return true
    }

    private fun applyImmediateUpdateLocked(
        transform: (AppState) -> AppState,
    ): PendingStateSave? {
        val previousState = mutableState.value
        val nextState = canonicalAppStateUpdate(transform)(previousState)
        if (nextState === previousState || nextState.isCheapNoopUpdate(previousState)) return null
        mutableState.value = nextState
        if (
            nextState.languageMode != previousState.languageMode ||
            nextState.colorMode != previousState.colorMode
        ) {
            settingsPreferences.saveChanged(previousState, nextState)
        }
        return pendingSaveFor(nextState)
    }

    private fun pendingSaveFor(nextState: AppState): PendingStateSave = PendingStateSave(
        nextState = nextState,
        revision = saveRevision.incrementAndGet(),
    )

    private suspend fun persistReplacementAndAwait(
        previousState: AppState,
        persistedState: AppState,
        revision: Long,
        forceReplaceAll: Boolean,
        canonicalizeFinalState: Boolean,
        persistBaseAfterFailure: Boolean,
        deferredUpdateFailureMessage: String,
    ): Result<Unit> {
        val completion = CompletableDeferred<Result<Unit>>()
        persist(
            nextState = persistedState,
            revision = revision,
            completion = completion,
            forceReplaceAll = forceReplaceAll,
        )
        val awaited = awaitCompletionPreservingCancellation(completion)
        val persistenceResult = awaited.result

        val followUpSave = synchronized(updateLock) {
            val base = if (persistenceResult.isSuccess) persistedState else previousState
            val deferredState = deferredUpdates.finishReplacement(base) { error ->
                AndroidAppLogger.error(LogTag, deferredUpdateFailureMessage, error)
            }
            val finalState = if (canonicalizeFinalState) {
                deferredState.withCanonicalManagedTagReferences()
            } else {
                deferredState
            }
            mutableState.value = finalState
            if (
                finalState != base ||
                (persistBaseAfterFailure && persistenceResult.isFailure)
            ) {
                pendingSaveFor(finalState)
            } else {
                null
            }
        }
        followUpSave?.let { save -> persist(save.nextState, save.revision) }
        awaited.cancellation?.let { error -> throw error }
        return persistenceResult
    }

    private suspend fun awaitCompletionPreservingCancellation(
        completion: CompletableDeferred<Result<Unit>>,
    ): AwaitedPersistence {
        var cancellation: CancellationException? = null
        val result = try {
            completion.await()
        } catch (error: CancellationException) {
            cancellation = error
            withContext(NonCancellable) { completion.await() }
        }
        return AwaitedPersistence(result, cancellation)
    }

    private fun loadInitialState(): LoadedAppState {
        return runBlocking(Dispatchers.IO) {
            val persistedState = runCatching {
                dao.loadState()
            }.onFailure { error ->
                AndroidAppLogger.error(LogTag, "Failed to load app state", error)
                resetDatabase()
            }.getOrNull()
            val settings = settingsPreferences.load()
            if (persistedState?.hasRoomContent() == true) {
                val state = persistedState.toAppState(settings)
                LoadedAppState(
                    state = state,
                    loadedFromDatabase = true,
                )
            } else {
                LoadedAppState(
                    state = settings.withCanonicalManagedTagReferences(),
                    loadedFromDatabase = false,
                )
            }
        }
    }

    private fun persist(
        nextState: AppState,
        revision: Long,
        completion: CompletableDeferred<Result<Unit>>? = null,
        forceReplaceAll: Boolean = false,
    ) {
        scope.launch {
            val result = try {
                saveMutex.withLock {
                    if (revision != saveRevision.get()) {
                        return@withLock Result.failure(StatePersistenceSupersededException())
                    }
                    val plan = persistenceTracker.plan(
                        nextState = nextState,
                        hasPersistedRoomState = hasPersistedState.get(),
                        forceReplaceAll = forceReplaceAll,
                    )
                    val firstAttempt = runCatching {
                        if (plan.replaceAll) {
                            settingsPreferences.save(plan.nextState)
                        } else {
                            settingsPreferences.saveChanged(plan.previousState, plan.nextState)
                        }
                        dao.saveState(
                            previousState = plan.previousState,
                            nextState = plan.nextState,
                            replaceAll = plan.replaceAll,
                        )
                        hasPersistedState.set(true)
                        persistenceTracker.markPersisted(plan.nextState)
                    }
                    if (firstAttempt.isSuccess) return@withLock firstAttempt

                    AndroidAppLogger.error(
                        LogTag,
                        "Failed to persist app state",
                        firstAttempt.exceptionOrNull(),
                    )
                    resetDatabase()
                    runCatching {
                        settingsPreferences.save(nextState)
                        dao.saveState(
                            previousState = AppState(),
                            nextState = nextState,
                            replaceAll = true,
                        )
                        hasPersistedState.set(true)
                        persistenceTracker.markPersisted(nextState)
                    }.onFailure { retryError ->
                        AndroidAppLogger.error(
                            LogTag,
                            "Failed to persist app state after database reset",
                            retryError,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AndroidAppLogger.error(LogTag, "Failed to prepare app state persistence", error)
                Result.failure(error)
            }
            completion?.complete(result)
        }
    }

    private fun buildDatabase(): AsteriskAppDatabase {
        return Room.databaseBuilder(
            appContext,
            AsteriskAppDatabase::class.java,
            AsteriskDatabaseName,
        )
            // Keep committed state in the main DB file for file-based backup tools.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    private fun resetDatabase() {
        runCatching { database.close() }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to close app state database before reset", error) }
        runCatching { appContext.deleteDatabase(AsteriskDatabaseName) }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to delete app state database during reset", error) }
        database = buildDatabase()
        dao = database.appStateDao()
        hasPersistedState.set(false)
    }

    companion object {
        private const val LogTag = "AndroidAppStateStore"

        @Volatile
        private var instance: AndroidAppStateStore? = null

        fun get(context: Context): AndroidAppStateStore {
            return instance ?: synchronized(this) {
                instance ?: AndroidAppStateStore(context).also { store ->
                    instance = store
                }
            }
        }
    }
}

internal fun canonicalAppStateUpdate(
    transform: (AppState) -> AppState,
): (AppState) -> AppState = { state ->
    val nextState = transform(state)
    if (requiresManagedTagCanonicalization(state, nextState)) {
        nextState.withCanonicalManagedTagReferences()
    } else {
        nextState
    }
}

private class StatePersistenceSupersededException : IllegalStateException(
    "App state persistence was superseded by a newer update",
)

private fun AppState.isCheapNoopUpdate(previous: AppState): Boolean {
    return outboundGroups === previous.outboundGroups &&
        outbounds === previous.outbounds &&
        routeRules === previous.routeRules &&
        customResourceFiles === previous.customResourceFiles &&
        dnsServers === previous.dnsServers &&
        dnsRules === previous.dnsRules &&
        externalInterfaces === previous.externalInterfaces &&
        tunSharedNetworkInterfaces === previous.tunSharedNetworkInterfaces &&
        ignoredInterfaces === previous.ignoredInterfaces &&
        privateAddressCidrs === previous.privateAddressCidrs &&
        proxyAppListSelectedApps === previous.proxyAppListSelectedApps &&
        this == previous
}

private data class PendingStateSave(
    val nextState: AppState,
    val revision: Long,
)

private data class LoadedAppState(
    val state: AppState,
    val loadedFromDatabase: Boolean,
)

private data class AwaitedPersistence(
    val result: Result<Unit>,
    val cancellation: CancellationException?,
)
