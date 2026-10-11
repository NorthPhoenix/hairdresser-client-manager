package com.northphoenix.hairdresserclientmanager

import androidx.compose.runtime.saveable.SaverScope
import com.northphoenix.hairdresserclientmanager.auth.ACCOUNT_NAME_NOT_SAVED
import com.northphoenix.hairdresserclientmanager.auth.AccountName
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.HcmApi
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import com.northphoenix.hairdresserclientmanager.data.TrpcClient
import com.northphoenix.hairdresserclientmanager.domain.PixelSize
import com.northphoenix.hairdresserclientmanager.domain.ShareImageSize
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.state.ActionRunner
import com.northphoenix.hairdresserclientmanager.state.profileShareKey
import com.northphoenix.hairdresserclientmanager.state.withProfileShare
import com.northphoenix.hairdresserclientmanager.ui.screens.ShareChoicesSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Profile Share card: the Stylist's three choices, the Share Image as a file, and the Stylist's name. */
class ProfileShareTest {
    private val server = MockWebServer()
    private lateinit var api: HcmApi

    @Before
    fun setUp() {
        server.start()
        api = HcmApi(TrpcClient(server.url("/api/trpc").toString(), OkHttpClient()) { "token-1" })
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun shareReply(choices: String) =
        """{"result":{"data":{"id":"s1","token":"t1","language":"ru","createdAt":"2026-10-01T10:00:00.000Z"$choices}}}"""

    // --- the three choices (M15) ---

    @Test
    fun `a new Profile Share starts with Color Formulas and after photos, without before and other photos`() {
        assertEquals(setOf(ShareChoice.COLOR_FORMULAS, ShareChoice.AFTER_PHOTOS), ShareChoice.defaults)
    }

    @Test
    fun `creating a Profile Share states every choice, also the ones that are off`() = runTest {
        enqueue(shareReply(""","includeColorFormulas":false,"includeAfterPhotos":true,"includeOtherPhotos":true"""))

        val share = api.createProfileShare("c1", setOf(ShareChoice.AFTER_PHOTOS, ShareChoice.OTHER_PHOTOS))
        val request = server.takeRequest()

        assertEquals("/api/trpc/clientProfile.createProfileShare", request.url.encodedPath)
        assertEquals(
            """{"clientId":"c1","includeColorFormulas":false,"includeAfterPhotos":true,"includeOtherPhotos":true}""",
            request.body?.utf8(),
        )
        assertEquals(setOf(ShareChoice.AFTER_PHOTOS, ShareChoice.OTHER_PHOTOS), share.included)
    }

    @Test
    fun `the choices shown are the ones of the reply, not the ones asked for`() = runTest {
        // The Client already had a Profile Share, made on another device with everything on: the server answers with that one.
        enqueue(shareReply(""","includeColorFormulas":true,"includeAfterPhotos":true,"includeOtherPhotos":true"""))

        val share = api.createProfileShare("c1", emptySet())

        assertEquals(ShareChoice.entries.toSet(), share.included)
    }

    @Test
    fun `changing one choice sends that choice alone, so the others stay as the server has them`() = runTest {
        enqueue(shareReply(""","includeColorFormulas":false,"includeAfterPhotos":true,"includeOtherPhotos":false"""))

        val share = api.updateProfileShare("c1", ShareChoice.COLOR_FORMULAS, included = false)
        val request = server.takeRequest()

        assertEquals("/api/trpc/clientProfile.updateProfileShare", request.url.encodedPath)
        assertEquals("""{"clientId":"c1","includeColorFormulas":false}""", request.body?.utf8())
        assertEquals(setOf(ShareChoice.AFTER_PHOTOS), share.included)
        // The link is the same one.
        assertEquals("t1", share.token)
    }

    @Test
    fun `a Profile Share whose reply names no choices has the defaults`() = runTest {
        enqueue(shareReply(""))

        assertEquals(ShareChoice.defaults, api.createProfileShare("c1", ShareChoice.defaults).included)
    }

    @Test
    fun `a choice for a Profile Share that was revoked elsewhere says so and has the screen loaded again`() = runTest {
        enqueue("""{"error":{"message":"Profile Share not found.","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)

        try {
            api.updateProfileShare("c1", ShareChoice.OTHER_PHOTOS, included = true)
            fail("expected an ApiException")
        } catch (error: ApiException) {
            // Not found is what makes the action runner reload everything.
            assertTrue(error.isNotFound)
            assertEquals(R.string.error_profile_share_gone, error.messageRes())
        }
    }

    @Test
    fun `the choices of a Profile Share that is not created yet come back as they were, also with nothing on`() {
        fun roundTrip(included: Set<ShareChoice>) = with(ShareChoicesSaver) { SaverScope { true }.save(included) }?.let(ShareChoicesSaver::restore)

        assertEquals(ShareChoice.defaults, roundTrip(ShareChoice.defaults))
        assertEquals(ShareChoice.entries.toSet(), roundTrip(ShareChoice.entries.toSet()))
        assertEquals(setOf(ShareChoice.OTHER_PHOTOS), roundTrip(setOf(ShareChoice.OTHER_PHOTOS)))
        // Everything off is a choice too, and must not come back as the defaults.
        assertEquals(emptySet<ShareChoice>(), roundTrip(emptySet()))
    }

    // --- the card after a change, before the list is loaded again (L51, L53) ---

    private val share = ProfileShare(id = "s1", token = "t1", language = Language.RU, createdAt = "2026-10-01T10:00:00.000Z")

    private fun client(id: String, share: ProfileShare? = null) =
        ClientProfile(id = id, name = id, language = Language.RU, activeProfileShare = share)

    @Test
    fun `a created, changed or revoked Profile Share is on its Client at once and on no other`() {
        val other = ProfileShare(id = "s2", token = "t2", language = Language.EN, createdAt = "2026-10-02T10:00:00.000Z")
        val clients = listOf(client("c1"), client("c2", other))

        val created = clients.withProfileShare("c1", share)
        assertEquals(listOf(share, other), created.map { it.activeProfileShare })

        val changed = created.withProfileShare("c1", share.copy(includeColorFormulas = false))
        assertEquals(setOf(ShareChoice.AFTER_PHOTOS), changed.first().activeProfileShare?.included)

        val revoked = changed.withProfileShare("c1", null)
        assertEquals(listOf(null, other), revoked.map { it.activeProfileShare })
    }

    @Test
    fun `a second tap on Create while the first is running sends nothing, and neither does a choice or Revoke`() = runTest {
        val failures = mutableListOf<ApiException>()
        val runner = ActionRunner(backgroundScope, { failures += it })
        val firstReply = CompletableDeferred<Unit>()
        var requests = 0

        // Create, Create again, a choice and Revoke for the same Client share one key.
        repeat(4) {
            runner.run(profileShareKey("c1"), change = {
                requests++
                firstReply.await()
            })
        }
        // Another Client's Profile Share is not held back.
        runner.run(profileShareKey("c2"), change = { requests++ })

        assertEquals(2, requests)
        assertTrue(runner.busy.value)

        firstReply.complete(Unit)
        runCurrent()

        assertFalse(runner.busy.value)
        assertEquals(emptyList<ApiException>(), failures)

        // Once it has ended, the next change runs.
        runner.run(profileShareKey("c1"), change = { requests++ })
        assertEquals(3, requests)
    }

    // --- the Share Image as a file (H5, L59) ---

    private fun picture(height: Int) =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1200" height="$height" viewBox="0 0 1200 $height">""" +
            """<rect width="1200" height="$height" fill="#f7f1e8"/><text x="120" y="200"><![CDATA[Cut & Color <b>bold</b> "Q"]]></text>""" +
            """<image x="120" y="900" width="460" height="613" xlink:href="data:image/jpeg;base64,AAAA"/></svg>"""

    @Test
    fun `the size of a Share Image is the one its own first tag states`() {
        assertEquals(PixelSize(1200, 1600), ShareImageSize.of(picture(1600)))
        // With photos and long texts the picture is taller: the size is read, never assumed.
        assertEquals(PixelSize(1200, 9340), ShareImageSize.of(picture(9340)))
        // Not the size of something inside the picture.
        assertEquals(PixelSize(1200, 2400), ShareImageSize.of("""<?xml version="1.0"?><svg height="2400" width="1200"><image width="460" height="613"/></svg>"""))
    }

    @Test
    fun `a picture that states no usable size has none`() {
        assertNull(ShareImageSize.of(""))
        assertNull(ShareImageSize.of("""<svg viewBox="0 0 1200 1600"><rect width="1200" height="1600"/></svg>"""))
        assertNull(ShareImageSize.of("""<svg width="100%" height="100%"></svg>"""))
        assertNull(ShareImageSize.of("""<svg width="0" height="1600"></svg>"""))
    }

    @Test
    fun `a Share Image is drawn at its own size, also when it is taller than 4096 px`() {
        // 4096 px a side is what the image loader draws when it is not told otherwise.
        listOf(1600, 4096, 4097, 8000, 10_000).forEach { height ->
            assertEquals(PixelSize(1200, height), ShareImageSize.drawn(PixelSize(1200, height)))
        }
    }

    @Test
    fun `a Share Image too tall to draw in one piece gets smaller as a whole`() {
        val stated = PixelSize(1200, 20_000)
        val drawn = ShareImageSize.drawn(stated)

        assertTrue(drawn.width.toLong() * drawn.height <= ShareImageSize.MAX_PIXELS)
        // In the same proportions, to the pixel a side: nothing is cut off and nothing is stretched.
        assertEquals(stated.height.toDouble() / stated.width, drawn.height.toDouble() / drawn.width, 0.02)
        // Still wide enough to read: letters of 28 px are about 20 px.
        assertTrue(drawn.width >= 840)
    }

    // --- the Stylist's name (L56) ---

    @Test
    fun `the Stylist's name is saved as one line a part, without spaces at the ends`() {
        assertEquals(AccountName("Maria", "Ivanova"), AccountName.of("  Maria ", "Ivanova\n"))
        assertEquals(AccountName("Anna Maria", "Petrova-Vodkina"), AccountName.of("Anna\nMaria", "Petrova-Vodkina"))
        // An empty part takes that part off the account, and two empty parts are an account without a name.
        assertEquals(AccountName("Maria", ""), AccountName.of("Maria", "   "))
        assertEquals(AccountName(), AccountName.of(" ", ""))
    }

    @Test
    fun `a part of the Stylist's name is no longer than its limit and never ends in half a character`() {
        val long = "a".repeat(AccountName.MAX_LENGTH + 20)

        assertEquals(AccountName.MAX_LENGTH, AccountName.typed(long).length)
        assertEquals("Maria", AccountName.typed("Maria"))
        assertEquals(AccountName.MAX_LENGTH, AccountName.of(long, "").first.length)

        // An emoji takes two places: one that straddles the limit is left out whole.
        val straddling = "a".repeat(AccountName.MAX_LENGTH - 1) + "😀"
        assertEquals("a".repeat(AccountName.MAX_LENGTH - 1), AccountName.typed(straddling))
    }

    @Test
    fun `a name that the sign-in provider did not save is said in the app language`() {
        assertEquals(R.string.account_name_not_saved, ApiException("Clerk did not save the name.", code = ACCOUNT_NAME_NOT_SAVED).messageRes())
        // Without a connection it is the connection that is named.
        assertEquals(R.string.network_error, ApiException("Clerk did not save the name.", code = ACCOUNT_NAME_NOT_SAVED, isNetwork = true).messageRes())
    }
}
