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
        keys = SigningKeys { kid -> if (kid == "test") pair.public else null },
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
    fun heartbeatStoresTheBalanceShownOnThePhone() {
        val balances = mutableListOf<Int>()
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
            override fun setWallet(rider: Rider, credits: Int) {
                balances += credits
            }
        }
        withApi(sync = sync) {
            client.post("/v1/session") { bearer("good") }
            val beat = client.post("/v1/heartbeat") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"credits":430}""")
            }
            assertEquals(HttpStatusCode.NoContent, beat.status)
        }
        assertEquals(listOf(430), balances)
    }

    @Test
    fun welcomeCreditsComeFromThePortal() {
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
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

            client.post("/v1/session") {
                bearer("good")
                header("X-Airvox-Platform", "iOS")
            }
            val beat = client.post("/v1/heartbeat") {
                bearer("good")
                header("X-Airvox-Platform", "android")
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
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
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
    fun recordsCreditUseAndPurchases() {
        val rows = mutableListOf<Triple<String, String, Int>>()
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
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
            assertEquals(HttpStatusCode.NoContent, used.status)
            val bought = client.post("/v1/credits") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"purchase","credits":50}""")
            }
            assertEquals(HttpStatusCode.NoContent, bought.status)
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
                Triple("uid-1", "consume", 1),
                Triple("uid-1", "purchase", 50),
            ),
            rows,
        )
    }

    @Test
    fun signOutTellsThePortal() {
        val signedOut = mutableListOf<String>()
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
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
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
            override fun removeRider(userId: String): RemovedRider? {
                if (userId != "11111111-1111-1111-1111-111111111111") return null
                return RemovedRider(userId, "uid-1")
            }
        }
        withApi(sync = sync, staff = StaffGate { it == "staff" }, accounts = AccountDeleter { uid ->
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
        val sync = object : RiderSync {
            override fun riderSeen(rider: Rider, platform: String?) = Unit
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

            client.post("/v1/session") { bearer("good") }
            val joined = client.post("/v1/channels") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"room":"Ride 1","mode":"bluetooth","action":"join","at":"2026-11-04T08:00:00Z"}""")
            }
            assertEquals(HttpStatusCode.NoContent, joined.status)
            val here = client.post("/v1/heartbeat") {
                bearer("good")
                contentType(ContentType.Application.Json)
                setBody("""{"credits":3,"room":"ride1","mode":"Hotspot"}""")
            }
            assertEquals(HttpStatusCode.NoContent, here.status)
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
        sync: RiderSync = NoRiderSync,
        staff: StaffGate = StaffGate { false },
        accounts: AccountDeleter = AccountDeleter { false },
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
                application { airvoxApi(verifier, store, sync, staff, accounts) }
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
