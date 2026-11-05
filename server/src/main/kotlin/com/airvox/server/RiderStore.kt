package com.airvox.server

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.json.Json

class RiderStore(
    private val file: Path,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val riders = LinkedHashMap<String, StoredRider>()
    private val removed = LinkedHashSet<String>()

    init {
        if (Files.exists(file)) {
            val loaded = json.decodeFromString(RiderFile.serializer(), Files.readString(file))
            loaded.riders.forEach { riders[it.id] = it }
            loaded.removed.forEach { removed.add(it) }
        }
    }

    fun upsert(identity: FirebaseIdentity): UpsertedRider = synchronized(lock) {
        if (identity.uid in removed) throw RiderRemoved()
        val now = clock().toString()
        val existing = riders[identity.uid]
        val created = existing == null
        val name = when {
            existing?.displayNameCustom == true -> existing.displayName
            !identity.name.isNullOrBlank() -> identity.name
            else -> existing?.displayName
        }
        val stored = StoredRider(
            id = identity.uid,
            phoneNumber = identity.phoneNumber ?: existing?.phoneNumber,
            email = identity.email ?: existing?.email,
            displayName = name,
            displayNameCustom = existing?.displayNameCustom == true,
            profileComplete = existing?.profileComplete == true,
            welcomeGranted = existing?.welcomeGranted == true,
            balanceOffset = existing?.balanceOffset ?: 0,
            signInProvider = identity.signInProvider,
            providers = identity.providers.ifEmpty { listOf(identity.signInProvider) },
            createdAt = existing?.createdAt ?: now,
            lastSeenAt = now,
        )
        riders[identity.uid] = stored
        persist()
        UpsertedRider(stored.toPublic(), created)
    }

    fun rename(uid: String, displayName: String): Rider? = synchronized(lock) {
        val existing = riders[uid] ?: return null
        val stored = existing.copy(
            displayName = displayName,
            displayNameCustom = true,
            lastSeenAt = clock().toString(),
        )
        riders[uid] = stored
        persist()
        stored.toPublic()
    }

    fun completeProfile(uid: String, displayName: String, phoneNumber: String): Rider? = synchronized(lock) {
        val existing = riders[uid] ?: return null
        val stored = existing.copy(
            displayName = displayName,
            displayNameCustom = true,
            phoneNumber = phoneNumber,
            profileComplete = true,
            lastSeenAt = clock().toString(),
        )
        riders[uid] = stored
        persist()
        stored.toPublic()
    }

    fun find(uid: String): Rider? = synchronized(lock) {
        riders[uid]?.toPublic()
    }

    /** An account that already exists does not have to enter a name and mobile again. */
    fun acceptReturningRider(uid: String): Rider? = synchronized(lock) {
        val existing = riders[uid] ?: return null
        if (existing.profileComplete) return existing.toPublic()
        val stored = existing.copy(profileComplete = true, lastSeenAt = clock().toString())
        riders[uid] = stored
        persist()
        stored.toPublic()
    }

    fun welcomeGranted(uid: String): Boolean = synchronized(lock) {
        riders[uid]?.welcomeGranted == true
    }

    fun markWelcomeGranted(uid: String) {
        synchronized(lock) {
            val existing = riders[uid] ?: return
            if (existing.welcomeGranted) return
            riders[uid] = existing.copy(welcomeGranted = true)
            persist()
        }
    }

    fun balanceOffset(uid: String): Int = synchronized(lock) {
        (riders[uid]?.balanceOffset ?: 0).coerceAtLeast(0)
    }

    /** Balance to store. [clearOffset] means the phone already removed the correction itself. */
    fun walletToStore(uid: String, reported: Int, clearOffset: Boolean): Int = synchronized(lock) {
        val amount = reported.coerceIn(0, 1_000_000)
        val existing = riders[uid] ?: return amount
        if (clearOffset) {
            if (existing.balanceOffset != 0) {
                riders[uid] = existing.copy(balanceOffset = 0)
                persist()
            }
            return amount
        }
        return (amount - existing.balanceOffset.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /** Drops the rider and remembers the id, so a later sign-in cannot write them back. */
    fun remove(uid: String) = synchronized(lock) {
        riders.remove(uid)
        removed.add(uid)
        persist()
    }

    fun isRemoved(uid: String): Boolean = synchronized(lock) { uid in removed }

    private fun persist() {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(RiderFile.serializer(), RiderFile(riders.values.toList(), removed.toList())))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
