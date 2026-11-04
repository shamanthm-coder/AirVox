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

    init {
        if (Files.exists(file)) {
            val loaded = json.decodeFromString(RiderFile.serializer(), Files.readString(file))
            loaded.riders.forEach { riders[it.id] = it }
        }
    }

    fun upsert(identity: FirebaseIdentity): UpsertedRider = synchronized(lock) {
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

    private fun persist() {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(RiderFile.serializer(), RiderFile(riders.values.toList())))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
