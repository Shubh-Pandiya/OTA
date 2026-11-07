package com.example.otaapplication.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SignInGateTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("sign_in_gate_test", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun signInErrorsAreReadable() {
        assertTrue(
            SupabaseAuth.signInErrorMessage(400, """{"code":"email_not_confirmed","message":"Email not confirmed"}""")
                .startsWith("Email not confirmed")
        )
        assertTrue(
            SupabaseAuth.signInErrorMessage(400, """{"error_code":"email_not_confirmed","msg":"Email not confirmed"}""")
                .startsWith("Email not confirmed")
        )
        assertEquals(
            "Wrong email or password",
            SupabaseAuth.signInErrorMessage(400, """{"code":"invalid_credentials","message":"Invalid login credentials"}""")
        )
        assertEquals("Wrong email or password", SupabaseAuth.signInErrorMessage(400, """{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
        assertTrue(SupabaseAuth.signInErrorMessage(429, """{"msg":"Request rate limit reached"}""").startsWith("Too many attempts"))
        assertTrue(SupabaseAuth.signInErrorMessage(503, "upstream down").contains("503"))
    }

    @Test
    fun expiredSessionSurvivesWhenOffline() = runBlocking {
        val store = SessionStore(prefs)
        store.save(CloudSession("old-access", "refresh", expiresAtMillis = 1, userId = "u1", email = "staff@ota.com"))
        // Nothing listens on this port: the refresh fails like it does with no network.
        val auth = SupabaseAuth(SupabaseConfig("https://127.0.0.1:9", "sb_publishable_test"), store)
        try {
            auth.accessToken()
            fail("refresh should fail without a network")
        } catch (e: IOException) {
            assertTrue(e !is CloudException || !e.authFailed)
        }
        assertNotNull("offline must not sign the user out", auth.session.value)
        assertEquals("staff@ota.com", SupabaseAuth(SupabaseConfig("https://127.0.0.1:9", "k"), SessionStore(prefs)).session.value?.email)
    }
}
