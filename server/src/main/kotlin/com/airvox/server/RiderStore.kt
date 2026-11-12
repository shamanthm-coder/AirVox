package com.airvox.server

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
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

    /**
     * [newSession] is true for a sign-in, which signs the previous phone out.
     * Saving a name or mobile number keeps the session the phone already holds.
     */
    fun upsert(identity: FirebaseIdentity, newSession: Boolean = true): UpsertedRider = synchronized(lock) {
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
            welcomeOwed = existing?.welcomeOwed == true,
            spentSeen = existing?.spentSeen ?: emptyMap(),
            signInProvider = identity.signInProvider,
            providers = identity.providers.ifEmpty { listOf(identity.signInProvider) },
            sessionId = if (newSession) UUID.randomUUID().toString() else existing?.sessionId.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            lastSeenAt = now,
        )
        riders[identity.uid] = stored
        persist()
        UpsertedRider(stored.toPublic(), created, stored.sessionId)
    }

    /** True when this phone is the one that last signed in. A blank stored id is a rider from before sessions existed. */
    fun sessionMatches(uid: String, presented: String?): Boolean = synchronized(lock) {
        val current = riders[uid]?.sessionId.orEmpty()
        current.isEmpty() || presented == current
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

    /**
     * An account that already gave a mobile number does not have to enter it again.
     * [portalPhone] is the number saved in the portal, for when this server's file lost it.
     */
    fun acceptReturningRider(uid: String, portalPhone: String?): Rider? = synchronized(lock) {
        val existing = riders[uid] ?: return null
        val phone = existing.phoneNumber?.takeIf { it.isNotBlank() } ?: portalPhone?.takeIf { it.isNotBlank() }
        if (phone == null || (existing.profileComplete && existing.phoneNumber == phone)) return existing.toPublic()
        val stored = existing.copy(phoneNumber = phone, profileComplete = true, lastSeenAt = clock().toString())
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
            if (existing.welcomeGranted && !existing.welcomeOwed) return
            riders[uid] = existing.copy(welcomeGranted = true, welcomeOwed = false)
            persist()
        }
    }

    fun welcomeOwed(uid: String): Boolean = synchronized(lock) {
        riders[uid]?.welcomeOwed == true
    }

    fun markWelcomeOwed(uid: String) {
        synchronized(lock) {
            val existing = riders[uid] ?: return
            if (existing.welcomeOwed) return
            riders[uid] = existing.copy(welcomeOwed = true)
            persist()
        }
    }

    /**
     * Credits this install used since the server last counted it.
     * 0 for an install the server has not seen, so its old total is not charged a second time.
     */
    fun spendSince(uid: String, install: String, spent: Int): Int = synchronized(lock) {
        val seen = riders[uid]?.spentSeen?.get(install) ?: return 0
        (spent - seen).coerceIn(0, 10_000)
    }

    fun countSpent(uid: String, install: String, spent: Int) {
        synchronized(lock) {
            val existing = riders[uid] ?: return
            if (existing.spentSeen[install] == spent) return
            // Newest last. A rider rarely has more than one or two phones.
            val seen = (existing.spentSeen - install + (install to spent)).entries.toList().takeLast(8)
            riders[uid] = existing.copy(spentSeen = seen.associate { it.key to it.value })
            persist()
        }
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
