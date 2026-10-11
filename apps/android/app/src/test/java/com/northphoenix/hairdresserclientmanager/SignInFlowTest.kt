package com.northphoenix.hairdresserclientmanager

import com.clerk.api.network.model.factor.Factor
import com.northphoenix.hairdresserclientmanager.auth.CodePurpose
import com.northphoenix.hairdresserclientmanager.auth.SignInBackend
import com.northphoenix.hairdresserclientmanager.auth.SignInError
import com.northphoenix.hairdresserclientmanager.auth.SignInFlow
import com.northphoenix.hairdresserclientmanager.auth.SignInResult
import com.northphoenix.hairdresserclientmanager.auth.SignInState
import com.northphoenix.hairdresserclientmanager.auth.SignInStep
import com.northphoenix.hairdresserclientmanager.auth.resetCodeAddressId
import com.northphoenix.hairdresserclientmanager.auth.signInError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in and creating an account on the app's own screens: the steps, what is refused, Back, and what survives the app being killed. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInFlowTest {
    private val email = "anna@example.com"

    /** The identity provider, answering each step with [next]. */
    private class Provider : SignInBackend {
        val asked = mutableListOf<String>()
        var next: SignInResult = SignInResult.Failed(SignInError.GENERIC)

        /** While set, a request does not come back: it is on its way. */
        var onItsWay: CompletableDeferred<Unit>? = null

        private suspend fun answer(step: String): SignInResult {
            asked += step
            onItsWay?.await()
            return next
        }

        override suspend fun findAccount(email: String) = answer("findAccount $email")

        override suspend fun signIn(email: String, password: String) = answer("signIn $email $password")

        override suspend fun signUp(email: String, password: String) = answer("signUp $email $password")

        override suspend fun sendCode(email: String, purpose: CodePurpose) = answer("sendCode $email $purpose")

        override suspend fun verifyCode(purpose: CodePurpose, code: String) = answer("verifyCode $purpose $code")

        override suspend fun setNewPassword(password: String) = answer("setNewPassword $password")
    }

    private val provider = Provider()

    private fun TestScope.flow() = SignInFlow(provider, backgroundScope)

    private val SignInFlow.now: SignInState
        get() = state.value

    /** A flow on the password step of an account that exists. */
    private fun TestScope.flowAtPassword(canUseEmailCode: Boolean = true): SignInFlow {
        val flow = flow()

        provider.next = SignInResult.AccountFound(canUseEmailCode)
        flow.submitEmail(email)
        runCurrent()
        assertEquals(SignInStep.PASSWORD, flow.now.step)

        return flow
    }

    /** A flow on the code step of an account that is being created. */
    private fun TestScope.flowAtSignUpCode(): SignInFlow {
        val flow = flow()

        provider.next = SignInResult.NoAccount
        flow.submitEmail(email)
        runCurrent()
        provider.next = SignInResult.CodeSent(CodePurpose.SIGN_UP)
        flow.createAccount("long-enough")
        runCurrent()
        assertEquals(SignInStep.CODE, flow.now.step)

        return flow
    }

    // --- the steps ---

    @Test
    fun `an email with an account asks for its password, and one without offers to create the account`() = runTest {
        val known = flow()
        provider.next = SignInResult.AccountFound(canUseEmailCode = true)
        known.submitEmail("  $email ")
        runCurrent()

        assertEquals(listOf("findAccount $email"), provider.asked)
        assertEquals(SignInState(step = SignInStep.PASSWORD, email = email, canUseEmailCode = true), known.now)

        val unknown = flow()
        provider.next = SignInResult.NoAccount
        unknown.submitEmail("new@example.com")
        runCurrent()

        assertEquals(SignInStep.CREATE_PASSWORD, unknown.now.step)
        assertEquals("new@example.com", unknown.now.email)
    }

    @Test
    fun `a mistyped email is refused before anything is sent`() = runTest {
        val flow = flow()

        for (typed in listOf("bad@@email", "not-an-email", "anna@example", " ")) {
            flow.submitEmail(typed)
            runCurrent()

            assertEquals(typed, SignInError.EMAIL_INVALID, flow.now.error)
            assertEquals(SignInStep.EMAIL, flow.now.step)
        }

        assertTrue(provider.asked.isEmpty())
        // Each refusal is a new one to show, also for the same text.
        assertEquals(4, flow.now.failures)
    }

    @Test
    fun `a wrong password is said on its step, stays there, and goes away when the Stylist changes the password`() = runTest {
        val flow = flowAtPassword()

        provider.next = SignInResult.Failed(SignInError.WRONG_PASSWORD)
        flow.submitPassword("wrong-pass-1")
        assertTrue(flow.now.busy)
        runCurrent()

        assertEquals(SignInStep.PASSWORD, flow.now.step)
        assertEquals(SignInError.WRONG_PASSWORD, flow.now.error)
        assertFalse(flow.now.busy)
        assertEquals("signIn $email wrong-pass-1", provider.asked.last())

        flow.clearError()
        assertNull(flow.now.error)

        provider.next = SignInResult.SignedIn
        flow.submitPassword("right-pass-1")
        runCurrent()

        // The screen goes away with the session: until then it stays busy instead of offering the form again.
        assertTrue(flow.now.busy)
        assertNull(flow.now.error)
    }

    @Test
    fun `a sign-in from a new device asks for the code that was sent`() = runTest {
        val flow = flowAtPassword()

        provider.next = SignInResult.CodeSent(CodePurpose.NEW_DEVICE)
        flow.submitPassword("right-pass-1")
        runCurrent()

        assertEquals(SignInStep.CODE, flow.now.step)
        assertEquals(CodePurpose.NEW_DEVICE, flow.now.codePurpose)

        provider.next = SignInResult.SignedIn
        flow.submitCode("424242")
        runCurrent()

        assertEquals("verifyCode NEW_DEVICE 424242", provider.asked.last())
    }

    @Test
    fun `a second tap while a request is on its way sends nothing more`() = runTest {
        val flow = flowAtPassword()

        provider.onItsWay = CompletableDeferred()
        flow.submitPassword("right-pass-1")
        flow.submitPassword("right-pass-1")
        flow.forgotPassword()
        runCurrent()

        assertEquals(listOf("findAccount $email", "signIn $email right-pass-1"), provider.asked)
        assertTrue(flow.now.busy)
    }

    @Test
    fun `creating an account takes a password of eight characters and goes on to the code`() = runTest {
        val flow = flow()
        provider.next = SignInResult.NoAccount
        flow.submitEmail(email)
        runCurrent()

        flow.createAccount("short")
        runCurrent()
        assertEquals(SignInError.PASSWORD_TOO_SHORT, flow.now.error)
        assertEquals(listOf("findAccount $email"), provider.asked)

        provider.next = SignInResult.CodeSent(CodePurpose.SIGN_UP)
        flow.createAccount("long-enough")
        runCurrent()

        assertEquals("signUp $email long-enough", provider.asked.last())
        assertEquals(SignInState(step = SignInStep.CODE, email = email, codePurpose = CodePurpose.SIGN_UP, failures = 1, codesSent = 1), flow.now)

        // A code with a digit missing is not sent.
        flow.submitCode("12345")
        runCurrent()
        assertEquals("signUp $email long-enough", provider.asked.last())

        provider.next = SignInResult.SignedIn
        flow.submitCode("424242")
        runCurrent()
        assertEquals("verifyCode SIGN_UP 424242", provider.asked.last())
        assertTrue(flow.now.busy)
    }

    @Test
    fun `a forgotten password is reset through a code and a new password typed the same twice`() = runTest {
        val flow = flowAtPassword()

        provider.next = SignInResult.CodeSent(CodePurpose.PASSWORD_RESET)
        flow.forgotPassword()
        runCurrent()
        assertEquals("sendCode $email PASSWORD_RESET", provider.asked.last())
        assertEquals(CodePurpose.PASSWORD_RESET, flow.now.codePurpose)

        provider.next = SignInResult.NewPasswordNeeded
        flow.submitCode("424242")
        runCurrent()
        assertEquals(SignInStep.NEW_PASSWORD, flow.now.step)

        val asked = provider.asked.size
        flow.setNewPassword("short", "short")
        assertEquals(SignInError.PASSWORD_TOO_SHORT, flow.now.error)
        flow.setNewPassword("new-password-1", "new-password-2")
        assertEquals(SignInError.PASSWORDS_DIFFER, flow.now.error)
        runCurrent()
        assertEquals(asked, provider.asked.size)

        provider.next = SignInResult.SignedIn
        flow.setNewPassword("new-password-1", "new-password-1")
        runCurrent()
        assertEquals("setNewPassword new-password-1", provider.asked.last())
        assertNull(flow.now.error)
    }

    @Test
    fun `signing in with an emailed code sends one and checks it`() = runTest {
        val flow = flowAtPassword()

        provider.next = SignInResult.CodeSent(CodePurpose.SIGN_IN)
        flow.useEmailCode()
        runCurrent()

        assertEquals("sendCode $email SIGN_IN", provider.asked.last())
        assertEquals(SignInStep.CODE, flow.now.step)
        assertEquals(CodePurpose.SIGN_IN, flow.now.codePurpose)
    }

    @Test
    fun `a wrong code is said on the code step, and another code starts the step afresh`() = runTest {
        val flow = flowAtSignUpCode()
        assertEquals(1, flow.now.codesSent)

        provider.next = SignInResult.Failed(SignInError.WRONG_CODE)
        flow.submitCode("111111")
        runCurrent()
        assertEquals(SignInStep.CODE, flow.now.step)
        assertEquals(SignInError.WRONG_CODE, flow.now.error)

        provider.next = SignInResult.CodeSent(CodePurpose.SIGN_UP)
        flow.resendCode()
        runCurrent()

        assertEquals("sendCode $email SIGN_UP", provider.asked.last())
        // The count is what the screen's wait and its empty field hang on.
        assertEquals(2, flow.now.codesSent)
        assertNull(flow.now.error)
    }

    // --- Back ---

    @Test
    fun `Back on the code step goes back on the first tap, and the step is clean when it is entered again`() = runTest {
        val flow = flowAtSignUpCode()

        provider.next = SignInResult.Failed(SignInError.WRONG_CODE)
        flow.submitCode("111111")
        runCurrent()
        assertEquals(SignInError.WRONG_CODE, flow.now.error)

        flow.back()

        assertEquals(SignInStep.CREATE_PASSWORD, flow.now.step)
        assertNull(flow.now.error)

        provider.next = SignInResult.CodeSent(CodePurpose.SIGN_UP)
        flow.createAccount("long-enough")
        runCurrent()

        assertEquals(SignInStep.CODE, flow.now.step)
        assertNull(flow.now.error)
        assertEquals(1, flow.now.codesSent)
    }

    @Test
    fun `Back works while a code is being checked, and the answer that comes too late changes nothing`() = runTest {
        val flow = flowAtSignUpCode()

        val onItsWay = CompletableDeferred<Unit>()
        provider.onItsWay = onItsWay
        provider.next = SignInResult.Failed(SignInError.WRONG_CODE)
        flow.submitCode("111111")
        runCurrent()
        assertTrue(flow.now.busy)

        flow.back()

        assertEquals(SignInStep.CREATE_PASSWORD, flow.now.step)
        assertFalse(flow.now.busy)

        onItsWay.complete(Unit)
        runCurrent()

        assertEquals(SignInStep.CREATE_PASSWORD, flow.now.step)
        assertNull(flow.now.error)
        assertFalse(flow.now.busy)
    }

    @Test
    fun `Back leads from every step to the one before it, and nowhere from the first`() = runTest {
        val signIn = flowAtPassword()

        for (purpose in listOf(CodePurpose.SIGN_IN, CodePurpose.NEW_DEVICE, CodePurpose.PASSWORD_RESET)) {
            provider.next = SignInResult.CodeSent(purpose)
            signIn.forgotPassword()
            runCurrent()
            assertEquals(SignInStep.CODE, signIn.now.step)

            signIn.back()
            assertEquals(purpose.name, SignInStep.PASSWORD, signIn.now.step)
        }

        provider.next = SignInResult.NewPasswordNeeded
        signIn.forgotPassword()
        runCurrent()
        signIn.back()
        assertEquals(SignInStep.PASSWORD, signIn.now.step)

        signIn.back()
        assertEquals(SignInStep.EMAIL, signIn.now.step)
        // The email is still there to be corrected.
        assertEquals(email, signIn.now.email)

        signIn.back()
        assertEquals(SignInStep.EMAIL, signIn.now.step)
    }

    // --- what survives the app being killed ---

    @Test
    fun `the code step and the email come back after Android killed the app`() = runTest {
        val kept = flowAtSignUpCode().now.kept()

        // A new process: a new flow, and what Android kept.
        val restored = flow()
        restored.restore(kept)

        assertEquals(SignInState(step = SignInStep.CODE, email = email, codePurpose = CodePurpose.SIGN_UP), restored.now)

        // The attempt is still with the provider: the code from the email is checked against it.
        provider.next = SignInResult.SignedIn
        restored.submitCode("424242")
        runCurrent()
        assertEquals("verifyCode SIGN_UP 424242", provider.asked.last())
    }

    @Test
    fun `every step is kept as it is, and nothing that was typed into a password field`() = runTest {
        for (step in SignInStep.entries) {
            for (purpose in CodePurpose.entries) {
                val state = SignInState(step = step, email = email, codePurpose = purpose, canUseEmailCode = step == SignInStep.PASSWORD)

                assertEquals(state, SignInState.fromKept(state.kept()))
            }
        }

        val busy = SignInState(step = SignInStep.CODE, email = email, busy = true, error = SignInError.WRONG_CODE, failures = 3, codesSent = 2)

        // A step that comes back is not busy and has nothing refused.
        assertEquals(SignInState(step = SignInStep.CODE, email = email), SignInState.fromKept(busy.kept()))
        assertEquals(listOf("CODE", email, "SIGN_IN", "false"), busy.kept())
    }

    @Test
    fun `what was kept in another shape restores nothing`() = runTest {
        assertNull(SignInState.fromKept(emptyList()))
        assertNull(SignInState.fromKept(listOf("CODE", email)))
        assertNull(SignInState.fromKept(listOf("GONE", email, "SIGN_IN", "false")))
        assertNull(SignInState.fromKept(listOf("CODE", email, "GONE", "false")))

        val flow = flow()
        flow.restore(listOf("GONE", email, "SIGN_IN", "false"))
        assertEquals(SignInState(), flow.now)
    }

    @Test
    fun `a flow that has gone on ignores what was kept before`() = runTest {
        // The screen is recreated (a rotation): the flow lived on and is ahead of what was kept.
        val flow = flowAtPassword()
        val keptAtPassword = flow.now.kept()

        provider.next = SignInResult.CodeSent(CodePurpose.PASSWORD_RESET)
        flow.forgotPassword()
        runCurrent()
        flow.restore(keptAtPassword)

        assertEquals(SignInStep.CODE, flow.now.step)

        // Someone signed in: the next visit starts at the first step, and stays there.
        flow.reset()
        assertEquals(SignInState(), flow.now)
        flow.restore(keptAtPassword)
        assertEquals(SignInState(), flow.now)

        // Going back counts as going on, too.
        val back = flowAtPassword()
        back.back()
        back.restore(keptAtPassword)
        assertEquals(SignInStep.EMAIL, back.now.step)
    }

    @Test
    fun `what was kept before a sign-in is not taken up when the Stylist signs out later`() = runTest {
        // The code was being checked when the Stylist left the app: this is what Android kept.
        val kept = flowAtSignUpCode().now.kept()

        // The sign-in went through, and Android killed the app after it. A new process: the Stylist is signed in.
        val flow = flow()
        flow.reset()

        // The Stylist signs out. Android still holds what it kept, and the sign-in screen hands it over.
        flow.restore(kept)

        assertEquals(SignInState(), flow.now)
    }

    @Test
    fun `an attempt the provider no longer has starts again at the email, which stays filled in`() = runTest {
        val flow = flow()
        flow.restore(SignInState(step = SignInStep.CODE, email = email, codePurpose = CodePurpose.SIGN_UP).kept())

        provider.next = SignInResult.Failed(SignInError.INTERRUPTED)
        flow.submitCode("424242")
        runCurrent()

        assertEquals(SignInStep.EMAIL, flow.now.step)
        assertEquals(email, flow.now.email)
        assertEquals(SignInError.INTERRUPTED, flow.now.error)
        assertFalse(flow.now.busy)
    }

    @Test
    fun `a reset gives up the request that is on its way`() = runTest {
        val flow = flowAtPassword()

        val onItsWay = CompletableDeferred<Unit>()
        provider.onItsWay = onItsWay
        provider.next = SignInResult.Failed(SignInError.WRONG_PASSWORD)
        flow.submitPassword("wrong-pass-1")
        runCurrent()

        flow.reset()
        onItsWay.complete(Unit)
        runCurrent()

        assertEquals(SignInState(), flow.now)
    }

    // --- what Clerk reports ---

    @Test
    fun `the code of a password reset goes to the address of the reset's own factor`() {
        val password = Factor(strategy = "password")
        val emailCode = Factor(strategy = "email_code", emailAddressId = "idn_code")
        val reset = Factor(strategy = "reset_password_email_code", emailAddressId = "idn_reset")

        // An instance that does not offer signing in by emailed code still resets a password.
        assertEquals("idn_reset", resetCodeAddressId(listOf(password, reset)))
        assertEquals("idn_reset", resetCodeAddressId(listOf(password, emailCode, reset)))

        // An account whose password cannot be reset by email: never the address of another factor, never an empty one.
        assertNull(resetCodeAddressId(listOf(password, emailCode)))
        assertNull(resetCodeAddressId(emptyList()))
        assertNull(resetCodeAddressId(null))
    }

    @Test
    fun `what Clerk reports becomes a reason of the app's own, never Clerk's English text`() {
        // The three messages of the report: 'Identifier is invalid.', 'Password is incorrect…', 'Incorrect code'.
        assertEquals(SignInError.EMAIL_INVALID, signInError("form_param_format_invalid", param = "identifier", status = 422))
        assertEquals(SignInError.WRONG_PASSWORD, signInError("form_password_incorrect", status = 422))
        assertEquals(SignInError.WRONG_CODE, signInError("form_code_incorrect", param = "code", status = 422))

        assertEquals(SignInError.EMAIL_INVALID, signInError("form_param_format_invalid", param = "email_address"))
        assertEquals(SignInError.WRONG_PASSWORD, signInError("form_password_or_identifier_incorrect"))
        assertEquals(SignInError.PASSWORD_COMPROMISED, signInError("form_password_pwned", param = "password"))
        assertEquals(SignInError.PASSWORD_REFUSED, signInError("form_password_length_too_short", param = "password"))
        assertEquals(SignInError.PASSWORD_REFUSED, signInError("form_password_not_strong_enough"))
        assertEquals(SignInError.PASSWORD_REFUSED, signInError("form_param_format_invalid", param = "password"))
        assertEquals(SignInError.WRONG_CODE, signInError("form_param_format_invalid", param = "code"))
        assertEquals(SignInError.CODE_EXPIRED, signInError("verification_expired"))
        assertEquals(SignInError.CODE_ATTEMPTS_USED_UP, signInError("verification_failed"))
        assertEquals(SignInError.ACCOUNT_LOCKED, signInError("user_locked", status = 403))
        assertEquals(SignInError.TOO_MANY_REQUESTS, signInError("too_many_requests", status = 429))
        assertEquals(SignInError.TOO_MANY_REQUESTS, signInError(null, status = 429))
        assertEquals(SignInError.EMAIL_TAKEN, signInError("form_identifier_exists", param = "email_address"))
        assertEquals(SignInError.NO_ACCOUNT, signInError("form_identifier_not_found", param = "identifier"))
        assertEquals(SignInError.UNSUPPORTED, signInError("strategy_for_user_invalid"))

        // No answer at all for lack of a connection, and an answer nobody has a word for.
        assertEquals(SignInError.NETWORK, signInError(null, isConnection = true))
        assertEquals(SignInError.GENERIC, signInError(null))
        assertEquals(SignInError.GENERIC, signInError("something_new", status = 422))
        assertEquals(SignInError.GENERIC, signInError("form_param_format_invalid", param = "first_name"))
    }
}
