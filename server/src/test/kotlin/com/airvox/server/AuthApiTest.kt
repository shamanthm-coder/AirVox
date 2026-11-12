package com.airvox.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FirebaseIdTokenVerifierTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val clock = Instant.parse("2026-11-02T12:00:00Z")
    private val verifier = FirebaseIdTokenVerifier(
        projectId = "airvox-demo",
        keys = { kid -> if (kid == "test") pair.public else null },
        clock = { clock },
    )

    @Test
    fun acceptsPhoneGoogleAndEmail() {
        listOf("phone", "google.com", "password").forEach { provider ->
            val identity = verifier.verify(token(provider = provider))
            assertEquals(provider, identity.signInProvider)
            assertEquals("uid-1", identity.uid)
        }
    }

    @Test
    fun readsThePhoneNumber() {
        val identity = verifier.verify(token(provider = "phone"))
        assertEquals("+919800000000", identity.phoneNumber)
        assertEquals(listOf("phone"), identity.providers)
    }

    @Test
    fun rejectsExpiredWrongProjectAndAnonymous() {
        assertFailsWith<TokenRejected> {
            verifier.verify(token(exp = clock.epochSecond - 120))
        }
        assertFailsWith<TokenRejected> {
            verifier.verify(token(audience = "other-app"))
        }
        assertFailsWith<TokenRejected> {
            verifier.verify(token(provider = "anonymous"))
        }
    }

    @Test
    fun rejectsABadSignatureAndUnknownKey() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFailsWith<TokenRejected> {
            verifier.verify(token(privateKey = other.private))
        }
        assertFailsWith<TokenRejected> {
            verifier.verify(token(kid = "missing"))
        }
        assertFailsWith<TokenRejected> {
            verifier.verify("not-a-token")
        }
    }

    private fun token(
        provider: String = "phone",
        exp: Long = clock.epochSecond + 600,
        audience: String = "airvox-demo",
        kid: String = "test",
        privateKey: java.security.PrivateKey = pair.private,
    ): String {
        val header = base64("""{"alg":"RS256","kid":"$kid","typ":"JWT"}""")
        val claim = if (provider == "phone") {
            """"phone_number":"+919800000000""""
        } else {
            """"email":"rider@example.com""""
        }
        val body = base64(
            """
            {
              "iss": "https://securetoken.google.com/$audience",
              "aud": "$audience",
              "sub": "uid-1",
              "iat": ${clock.epochSecond},
              "exp": $exp,
              "auth_time": ${clock.epochSecond},
              $claim,
              "firebase": {
                "sign_in_provider": "$provider",
                "identities": { "$provider": ["rider"] }
              }
            }
            """.trimIndent(),
        )
        val input = "$header.$body"
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(input.toByteArray(Charsets.UTF_8))
        val signed = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
        return "$input.$signed"
    }
}

class AuthApiTest {
    private val identity = FirebaseIdentity(
        uid = "uid-1",
        phoneNumber = "+919800000000",
        email = null,
        name = "From Google",
        signInProvider = "phone",
        providers = listOf("phone"),
    )

    @Test
    fun sessionCreatesRiderAndKeepsACustomName() {
        withApi { advance ->
            val created = client.post("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals("uid-1", riderId(created.bodyAsText()))
            assertEquals("+919800000000", riderField(created.bodyAsText(), "phoneNumber"))
            assertEquals("false", riderField(created.bodyAsText(), "profileComplete"))
            assertEquals("20", rootField(created.bodyAsText(), "welcomeCredits"))

            val missing = client.get("/v1/me")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertTrue(missing.bodyAsText().contains("invalid_token"))

            advance()
            val renamed = client.patch("/v1/me") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Asha"}""")
            }
            assertEquals(HttpStatusCode.OK, renamed.status)
            assertEquals("Asha", riderField(renamed.bodyAsText(), "displayName"))

            val again = client.post("/v1/session") { bearer("good") }
            assertEquals("Asha", riderField(again.bodyAsText(), "displayName"))
            assertTrue(riderField(again.bodyAsText(), "lastSeenAt") != riderField(created.bodyAsText(), "lastSeenAt"))
            assertEquals("false", riderField(again.bodyAsText(), "profileComplete"))
            assertEquals("0", rootField(again.bodyAsText(), "welcomeCredits"))

            val profile = client.patch("/v1/me") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Asha","phoneNumber":"+919812345678","completeProfile":true}""")
            }
            assertEquals(HttpStatusCode.OK, profile.status)
            assertEquals("true", riderField(profile.bodyAsText(), "profileComplete"))
            assertEquals("+919812345678", riderField(profile.bodyAsText(), "phoneNumber"))

            val kept = client.post("/v1/session") { bearer("good") }
            assertEquals("true", riderField(kept.bodyAsText(), "profileComplete"))
            assertEquals("Asha", riderField(kept.bodyAsText(), "displayName"))

            val blank = client.patch("/v1/me") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":" "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, blank.status)
            assertTrue(blank.bodyAsText().contains("invalid_name"))

            val signedOut = client.delete("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.NoContent, signedOut.status)
        }
    }

    @Test
    fun heartbeatTakesUsedCreditsOffTheWallet() {
        withApi {
            val created = client.post("/v1/session") { bearer("good") }
            val session = rootField(created.bodyAsText(), "sessionId")
            suspend fun beat(body: String): String {
                val reply = client.post("/v1/heartbeat") {
                    bearer("good")
                    header("X-Airvox-Session", session)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.OK, reply.status)
                return reply.bodyAsText()
            }
            // The first check-in from an install only sets where counting starts.
            val first = beat("""{"install":"phone-aaaa","spent":5}""")
            assertEquals("20", rootField(first, "walletBalance"))
            assertEquals("5", rootField(first, "spentCounted"))
            assertEquals("17", rootField(beat("""{"install":"phone-aaaa","spent":8}"""), "walletBalance"))
            // The same total sent twice is charged once.
            assertEquals("17", rootField(beat("""{"install":"phone-aaaa","spent":8}"""), "walletBalance"))
            // A reinstalled or second phone starts from the saved wallet, not from its own empty one.
            assertEquals("17", rootField(beat("""{"install":"phone-bbbb","spent":0}"""), "walletBalance"))
            // An older app sends only its own balance. That never changes the wallet.
            val old = beat("""{"credits":0}""")
            assertEquals("17", rootField(old, "walletBalance"))
            assertEquals("-1", rootField(old, "spentCounted"))
        }
    }

    @Test
    fun theWalletNeverGoesBelowZero() {
        withApi {
            val created = client.post("/v1/session") { bearer("good") }
            val session = rootField(created.bodyAsText(), "sessionId")
            listOf(0 to "20", 50 to "0").forEach { (spent, left) ->
                val reply = client.post("/v1/heartbeat") {
                    bearer("good")
                    header("X-Airvox-Session", session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"install":"phone-aaaa","spent":$spent}""")
                }
                assertEquals(left, rootField(reply.bodyAsText(), "walletBalance"))
            }
        }
    }

    @Test
    fun welcomeCreditsWaitWhenThePortalCannotBeReached() {
        var reachable = false
        val sync = object : LocalRiderSync() {
            override fun existingAccount(firebaseUid: String): Boolean? = if (reachable) true else null
        }
        withApi(sync = sync) {
            val created = client.post("/v1/session") { bearer("good") }
            assertEquals("0", rootField(created.bodyAsText(), "welcomeCredits"))
            // By now the sign-in was copied to the portal, so the account looks old.
            reachable = true
            val again = client.post("/v1/session") { bearer("good") }
            assertEquals("20", rootField(again.bodyAsText(), "welcomeCredits"))
            val third = client.post("/v1/session") { bearer("good") }
            assertEquals("0", rootField(third.bodyAsText(), "welcomeCredits"))
        }
        assertEquals(20, sync.walletBalance("uid-1"))
    }

    @Test
    fun anAccountWithoutAMobileNumberIsAskedForIt() {
        val email = identity.copy(phoneNumber = null, email = "rider@example.com", signInProvider = "password", providers = listOf("password"))
        var portalPhone: String? = null
        val sync = object : LocalRiderSync() {
            override fun existingAccount(firebaseUid: String) = true
            override fun portalPhone(firebaseUid: String) = portalPhone
        }
        withApi(sync = sync, identity = email) {
            val asked = client.post("/v1/session") { bearer("good") }
            assertEquals("false", riderField(asked.bodyAsText(), "profileComplete"))
            // The number saved in the portal counts, so the rider is not asked twice.
            portalPhone = "+919812345678"
            val known = client.post("/v1/session") { bearer("good") }
            assertEquals("true", riderField(known.bodyAsText(), "profileComplete"))
            assertEquals("+919812345678", riderField(known.bodyAsText(), "phoneNumber"))
        }
    }

    @Test
    fun savingTheProfileKeepsThisPhoneSignedIn() {
        withApi {
            val created = client.post("/v1/session") { bearer("good") }
            val session = rootField(created.bodyAsText(), "sessionId")
            client.patch("/v1/me") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Asha","phoneNumber":"+919812345678","completeProfile":true}""")
            }
            val beat = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Session", session)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, beat.status)
        }
    }

    @Test
    fun aNewSignInReplacesThePreviousPhone() {
        withApi {
            val first = client.post("/v1/session") { bearer("good") }
            val firstId = rootField(first.bodyAsText(), "sessionId")
            val second = client.post("/v1/session") { bearer("good") }
            val secondId = rootField(second.bodyAsText(), "sessionId")
            assertTrue(firstId.isNotBlank())
            assertTrue(firstId != secondId)
            val oldPhone = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Session", firstId)
                contentType(ContentType.Application.Json)
                setBody("""{"credits":1}""")
            }
            assertEquals(HttpStatusCode.Conflict, oldPhone.status)
            assertTrue(oldPhone.bodyAsText().contains("signed_in_elsewhere"))
            val newPhone = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Session", secondId)
                contentType(ContentType.Application.Json)
                setBody("""{"credits":1}""")
            }
            assertEquals(HttpStatusCode.OK, newPhone.status)
        }
    }

    @Test
    fun anExistingAccountDoesNotGetWelcomeCreditsAgain() {
        var grants = 0
        val sync = object : LocalRiderSync() {
            override fun existingAccount(firebaseUid: String) = true
            override fun recordCredits(rider: Rider, kind: String, credits: Int) {
                grants += 1
            }
        }
        withApi(sync = sync) {
            val created = client.post("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals("0", rootField(created.bodyAsText(), "welcomeCredits"))
            assertEquals("true", riderField(created.bodyAsText(), "profileComplete"))
            val again = client.post("/v1/session") { bearer("good") }
            assertEquals("0", rootField(again.bodyAsText(), "welcomeCredits"))
            assertEquals("true", riderField(again.bodyAsText(), "profileComplete"))
        }
        assertEquals(0, grants)
    }

    @Test
    fun welcomeCreditsComeFromThePortal() {
        val sync = object : LocalRiderSync() {
            override fun welcomeCredits(): Int = 40
        }
        withApi(sync = sync) {
            val created = client.post("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.OK, created.status)
            assertEquals("40", rootField(created.bodyAsText(), "welcomeCredits"))
            val again = client.post("/v1/session") { bearer("good") }
            assertEquals("0", rootField(again.bodyAsText(), "welcomeCredits"))
        }
    }

    @Test
    fun unknownRiderAndUnavailableAuth() {
        withApi {
            val me = client.get("/v1/me") { bearer("good") }
            assertEquals(HttpStatusCode.NotFound, me.status)
            assertTrue(me.bodyAsText().contains("unknown_rider"))

            val down = client.post("/v1/session") { bearer("down") }
            assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
            assertTrue(down.bodyAsText().contains("auth_unavailable"))

            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)
            assertTrue(health.bodyAsText().contains("ok"))
        }
    }

    @Test
    fun copiesRidersForThePortal() {
        val seen = mutableListOf<Pair<String, String?>>()
        withApi(sync = { rider, platform -> seen += rider.id to platform }) {
            val early = client.post("/v1/heartbeat") { bearer("good") }
            assertEquals(HttpStatusCode.NotFound, early.status)

            val created = client.post("/v1/session") {
                bearer("good")
                header("X-Airvox-Platform", "iOS")
            }
            val beat = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Platform", "android")
                header("X-Airvox-Session", rootField(created.bodyAsText(), "sessionId"))
            }
            assertEquals(HttpStatusCode.NoContent, beat.status)
            client.patch("/v1/me") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Asha"}""")
            }
            val refused = client.post("/v1/heartbeat") { bearer("bad") }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
        }
        assertEquals(listOf("uid-1" to "iOS", "uid-1" to "Android", "uid-1" to null), seen)
    }

    @Test
    fun sendsPortalPlansToThePhone() {
        val sync = object : LocalRiderSync() {
            override fun activePlans() = listOf(PlanOffer(1, "Weekend", 80, 80))
        }
        withApi(sync = sync) {
            client.post("/v1/session") { bearer("good") }
            val listed = client.get("/v1/plans") { bearer("good") }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(listed.bodyAsText().contains("Weekend"))
            assertTrue(listed.bodyAsText().contains("80"))
            val refused = client.get("/v1/plans") { bearer("bad") }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
        }
    }

    @Test
    fun aPurchaseAddsToTheWallet() {
        val rows = mutableListOf<Triple<String, String, Int>>()
        val sync = object : LocalRiderSync() {
            override fun recordCredits(rider: Rider, kind: String, credits: Int) {
                rows += Triple(rider.id, kind, credits)
            }
        }
        withApi(sync = sync) {
            val early = client.post("/v1/credits") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"consume","credits":1}""")
            }
            assertEquals(HttpStatusCode.NotFound, early.status)

            client.post("/v1/session") { bearer("good") }
            val used = client.post("/v1/credits") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"consume","credits":1}""")
            }
            assertEquals(HttpStatusCode.BadRequest, used.status)
            val bought = client.post("/v1/credits") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"purchase","credits":50}""")
            }
            assertEquals(HttpStatusCode.OK, bought.status)
            assertEquals("70", rootField(bought.bodyAsText(), "walletBalance"))
            val bad = client.post("/v1/credits") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"refund","credits":1}""")
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        }
        assertEquals(
            listOf(
                Triple("uid-1", "grant", 20),
                Triple("uid-1", "purchase", 50),
            ),
            rows,
        )
    }

    @Test
    fun signOutTellsThePortal() {
        val signedOut = mutableListOf<String>()
        val sync = object : LocalRiderSync() {
            override fun riderSignedOut(firebaseUid: String) {
                signedOut += firebaseUid
            }
        }
        withApi(sync = sync) {
            client.post("/v1/session") { bearer("good") }
            val signed = client.delete("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.NoContent, signed.status)
            val refused = client.delete("/v1/session") { bearer("bad") }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
        }
        assertEquals(listOf("uid-1"), signedOut)
    }

    @Test
    fun staffCanDeleteARiderAndFreeTheEmail() {
        val deleted = mutableListOf<String>()
        val sync = object : LocalRiderSync() {
            override fun removeRider(userId: String): RemovedRider? {
                if (userId != "11111111-1111-1111-1111-111111111111") return null
                return RemovedRider(userId, "uid-1")
            }
        }
        withApi(sync = sync, staff = { it == "staff" }, accounts = { uid ->
            deleted += uid
            true
        }) {
            client.post("/v1/session") { bearer("good") }
            val denied = client.delete("/v1/riders/11111111-1111-1111-1111-111111111111") { bearer("good") }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            val bad = client.delete("/v1/riders/not-an-id") { bearer("staff") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val missing = client.delete("/v1/riders/22222222-2222-2222-2222-222222222222") { bearer("staff") }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            val gone = client.delete("/v1/riders/11111111-1111-1111-1111-111111111111") { bearer("staff") }
            assertEquals(HttpStatusCode.OK, gone.status)
            assertTrue(gone.bodyAsText().contains("true"))
            val me = client.get("/v1/me") { bearer("good") }
            assertEquals(HttpStatusCode.Gone, me.status)
            val again = client.post("/v1/session") { bearer("good") }
            assertEquals(HttpStatusCode.Gone, again.status)
            assertTrue(again.bodyAsText().contains("account_removed"))
        }
        assertEquals(listOf("uid-1"), deleted)
    }

    @Test
    fun aRiderChannelIsSavedForThePortal() {
        val events = mutableListOf<String>()
        val sync = object : LocalRiderSync() {
            override fun reportChannel(rider: Rider, room: String, mode: String, action: String, at: String?) {
                events += "$action $room $mode"
            }
        }
        withApi(sync = sync) {
            val early = client.post("/v1/channels") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"room":"ride1","mode":"Bluetooth","action":"join"}""")
            }
            assertEquals(HttpStatusCode.NotFound, early.status)

            val created = client.post("/v1/session") { bearer("good") }
            val session = rootField(created.bodyAsText(), "sessionId")
            val joined = client.post("/v1/channels") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"room":"Ride 1","mode":"bluetooth","action":"join","at":"2026-11-04T08:00:00Z"}""")
            }
            assertEquals(HttpStatusCode.NoContent, joined.status)
            val here = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Session", session)
                contentType(ContentType.Application.Json)
                setBody("""{"credits":3,"room":"ride1","mode":"Hotspot"}""")
            }
            assertEquals(HttpStatusCode.OK, here.status)
            val bad = client.post("/v1/channels") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"room":"ab","mode":"Bluetooth","action":"join"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        }
        assertEquals(listOf("join ride1 Bluetooth", "here ride1 Hotspot"), events)
    }

    @Test
    fun staffCanStopALiveChannel() {
        val stopped = mutableListOf<String>()
        val sync = object : LocalRiderSync() {
            override fun stopChannel(channelId: String): Boolean {
                if (channelId != "33333333-3333-3333-3333-333333333333") return false
                stopped += channelId
                return true
            }
        }
        withApi(sync = sync, staff = { it == "staff" }) {
            val denied = client.post("/v1/channels/33333333-3333-3333-3333-333333333333/stop") { bearer("good") }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            val bad = client.post("/v1/channels/not-an-id/stop") { bearer("staff") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val missing = client.post("/v1/channels/22222222-2222-2222-2222-222222222222/stop") { bearer("staff") }
            assertEquals(HttpStatusCode.Conflict, missing.status)
            val gone = client.post("/v1/channels/33333333-3333-3333-3333-333333333333/stop") { bearer("staff") }
            assertEquals(HttpStatusCode.NoContent, gone.status)
        }
        assertEquals(listOf("33333333-3333-3333-3333-333333333333"), stopped)
    }

    @Test
    fun deletedRiderStaysOffTheServer() {
        val dir = createTempDirectory("airvox-riders")
        val file = dir.resolve("riders.json")
        try {
            val clock = Instant.parse("2026-11-02T12:00:00Z")
            RiderStore(file) { clock }.apply {
                upsert(identity)
                remove("uid-1")
            }
            val reloaded = RiderStore(file) { clock.plusSeconds(5) }
            assertEquals(null, reloaded.find("uid-1"))
            assertTrue(reloaded.isRemoved("uid-1"))
            assertFailsWith<RiderRemoved> { reloaded.upsert(identity) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun staffCanAddCreditsAndPortalAccounts() {
        val added = mutableListOf<Pair<String, Int>>()
        val sync = object : LocalRiderSync() {
            override fun addCredits(userId: String, credits: Int): Int? {
                if (userId != "33333333-3333-3333-3333-333333333333") return null
                added += userId to credits
                return 62 + credits
            }
        }
        val made = mutableListOf<String>()
        val staffAccounts = object : StaffAccounts {
            override fun list() = made.map { StaffAccount(it, "admin") }
            override fun create(email: String, password: String): StaffAccount {
                if (email in made) throw StaffAccountRefused("An account with this email already exists.")
                made += email
                return StaffAccount(email, "admin")
            }
        }
        withApi(sync = sync, staff = { it == "staff" }, staffAccounts = staffAccounts) {
            suspend fun add(token: String, id: String, credits: Int) = client.post("/v1/riders/$id/credits") {
                bearer(token)
                contentType(ContentType.Application.Json)
                setBody("""{"credits":$credits}""")
            }
            assertEquals(HttpStatusCode.Forbidden, add("good", "33333333-3333-3333-3333-333333333333", 50).status)
            assertEquals(HttpStatusCode.BadRequest, add("staff", "33333333-3333-3333-3333-333333333333", 0).status)
            assertEquals(HttpStatusCode.NotFound, add("staff", "22222222-2222-2222-2222-222222222222", 50).status)
            val done = add("staff", "33333333-3333-3333-3333-333333333333", 50)
            assertEquals(HttpStatusCode.OK, done.status)
            assertEquals("112", rootField(done.bodyAsText(), "walletBalance"))

            suspend fun create(token: String, body: String) = client.post("/v1/staff") {
                bearer(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Forbidden, create("good", """{"email":"new@airvox.in","password":"secret1"}""").status)
            assertEquals(HttpStatusCode.BadRequest, create("staff", """{"email":"new@airvox.in","password":"123"}""").status)
            assertEquals(HttpStatusCode.BadRequest, create("staff", """{"email":"not-an-email","password":"secret1"}""").status)
            assertEquals(HttpStatusCode.Created, create("staff", """{"email":" New@Airvox.in ","password":"secret1"}""").status)
            assertEquals(HttpStatusCode.Conflict, create("staff", """{"email":"new@airvox.in","password":"secret1"}""").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/staff") { bearer("good") }.status)
            val listed = client.get("/v1/staff") { bearer("staff") }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertTrue(listed.bodyAsText().contains("new@airvox.in"))
        }
        assertEquals(listOf("33333333-3333-3333-3333-333333333333" to 50), added)
    }

    @Test
    fun anOlderRiderFileStillLoads() {
        val dir = createTempDirectory("airvox-riders")
        val file = dir.resolve("riders.json")
        try {
            file.writeText(
                """
                {"riders":[{"id":"uid-1","phoneNumber":null,"email":"ben1@yopmail.com","displayName":null,"displayNameCustom":false,"profileComplete":true,"welcomeGranted":true,"balanceOffset":20,"signInProvider":"password","providers":["password"],"createdAt":"2026-11-04T09:12:57Z","lastSeenAt":"2026-11-05T04:41:02Z"}],"removed":[]}
                """.trimIndent(),
            )
            val store = RiderStore(file)
            // Marked complete by an older server, but there is no mobile number.
            assertEquals(false, store.find("uid-1")?.profileComplete)
            assertEquals(0, store.spendSince("uid-1", "phone-aaaa", 9))
            store.countSpent("uid-1", "phone-aaaa", 9)
            assertEquals(3, RiderStore(file).spendSince("uid-1", "phone-aaaa", 12))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun profileSurvivesANewStore() {
        val dir = createTempDirectory("airvox-riders")
        val file = dir.resolve("riders.json")
        try {
            val clock = Instant.parse("2026-11-02T12:00:00Z")
            RiderStore(file) { clock }.upsert(identity)
            val loaded = RiderStore(file) { clock.plusSeconds(5) }.find("uid-1")
            assertEquals("+919800000000", loaded?.phoneNumber)
            assertEquals("From Google", loaded?.displayName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun withApi(
        sync: RiderSync = LocalRiderSync(),
        staffAccounts: StaffAccounts = NoStaffAccounts,
        staff: StaffGate = StaffGate { false },
        accounts: AccountDeleter = AccountDeleter { false },
        identity: FirebaseIdentity = this.identity,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(advance: () -> Unit) -> Unit,
    ) {
        val dir = createTempDirectory("airvox-api")
        var now = Instant.parse("2026-11-02T12:00:00Z")
        val store = RiderStore(dir.resolve("riders.json")) { now }
        val verifier = object : IdTokenVerifier {
            override fun verify(token: String): FirebaseIdentity = when (token) {
                "good" -> identity
                "down" -> throw TokenCheckUnavailable("Could not reach Google to check the sign-in.")
                else -> throw TokenRejected("Firebase ID token is not valid.")
            }
        }
        try {
            testApplication {
                application { airvoxApi(verifier, store, sync, staff, accounts, staffAccounts) }
                block { now = now.plusSeconds(30) }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private fun riderId(body: String): String = riderField(body, "id")

private fun riderField(body: String, name: String): String {
    return Json.parseToJsonElement(body).jsonObject.getValue("rider").jsonObject.getValue(name).jsonPrimitive.content
}

private fun rootField(body: String, name: String): String {
    return Json.parseToJsonElement(body).jsonObject.getValue(name).jsonPrimitive.content
}

private fun base64(value: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
