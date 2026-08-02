// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import app.AppState
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
            if (deferredUpdates.deferIfReplacing(transform)) return
            applyImmediateUpdateLocked(transform)
        } ?: return

        persist(pendingSave.nextState, pendingSave.revision)
    }

    suspend fun replaceAllAndAwaitPersistence(nextState: AppState) {
        lateinit var previousState: AppState
        val replacementRevision = synchronized(updateLock) {
            previousState = mutableState.value
            if (nextState == previousState) return
            deferredUpdates.beginReplacement()
            saveRevision.incrementAndGet()
        }
        val completion = CompletableDeferred<Result<Unit>>()
        persist(nextState, replacementRevision, completion)
        var cancellation: CancellationException? = null
        val result = try {
            completion.await()
        } catch (error: CancellationException) {
            cancellation = error
            withContext(NonCancellable) { completion.await() }
        }

        val followUpSave = synchronized(updateLock) {
            val replacementBase = if (result.isSuccess) nextState else previousState
            val finalState = deferredUpdates.finishReplacement(replacementBase) { error ->
                AndroidAppLogger.error(
                    LogTag,
                    "Discarded an invalid app state update deferred during replacement; continuing",
                    error,
                )
            }
            mutableState.value = finalState
            if (result.isFailure || finalState != replacementBase) {
                PendingStateSave(
                    nextState = finalState,
                    revision = saveRevision.incrementAndGet(),
                )
            } else {
                null
            }
        }
        followUpSave?.let { save -> persist(save.nextState, save.revision) }
        cancellation?.let { error -> throw error }
        result.getOrThrow()
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
        val nextState = transform(previousState)
        if (nextState === previousState || nextState.isCheapNoopUpdate(previousState)) return null
        mutableState.value = nextState
        if (
            nextState.languageMode != previousState.languageMode ||
            nextState.colorMode != previousState.colorMode
        ) {
            settingsPreferences.save(nextState)
        }
        return PendingStateSave(
            nextState = nextState,
            revision = saveRevision.incrementAndGet(),
        )
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
                    state = settings,
                    loadedFromDatabase = false,
                )
            }
        }
    }

    private fun persist(
        nextState: AppState,
        revision: Long,
        completion: CompletableDeferred<Result<Unit>>? = null,
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
                    )
                    val firstAttempt = runCatching {
                        settingsPreferences.save(plan.nextState)
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
            .addMigrations(MIGRATION_1_2)
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
        ebpfSharedNetworkInterfaces === previous.ebpfSharedNetworkInterfaces &&
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
