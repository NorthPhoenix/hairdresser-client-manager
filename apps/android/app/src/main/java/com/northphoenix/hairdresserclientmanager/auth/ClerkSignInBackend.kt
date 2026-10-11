package com.northphoenix.hairdresserclientmanager.auth

import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.model.factor.Factor
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.attemptFirstFactor
import com.clerk.api.signin.attemptSecondFactor
import com.clerk.api.signin.prepareFirstFactor
import com.clerk.api.signin.resetPassword
import com.clerk.api.signin.sendCode
import com.clerk.api.signin.sendMfaEmailCode
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.sendEmailCode
import com.clerk.api.signup.verifyCode
import com.northphoenix.hairdresserclientmanager.data.isConnectionFailure
import com.northphoenix.hairdresserclientmanager.data.textOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The steps of [SignInFlow] on Clerk's headless API: an email address, a password, and a code
 * sent to that address where Clerk wants one (ADR 0004). Clerk's prebuilt views are not used:
 * they show Clerk's English errors as they are and keep their step in memory only.
 */
internal class ClerkSignInBackend : SignInBackend {
    // The attempt of this process. After Android killed the app there is none here, but Clerk's
    // client keeps the attempt on its server and has it again once it has started.
    private var signIn: SignIn? = null
    private var signUp: SignUp? = null

    override suspend fun findAccount(email: String): SignInResult =
        answer {
            val result = Clerk.auth.signIn { this.email = email }

            if (result is ClerkResult.Failure && result.firstError()?.code in NO_ACCOUNT_CODES) {
                SignInResult.NoAccount
            } else {
                result.then { attempt ->
                    signIn = attempt
                    SignInResult.AccountFound(canUseEmailCode = attempt.supportedFirstFactors.orEmpty().any { it.strategy == EMAIL_CODE })
                }
            }
        }

    // A sign-in of its own every time, here and in [sendCode]: it needs nothing from an earlier
    // step, so it works the same after a wrong password, after going back and after the app was killed.
    override suspend fun signIn(email: String, password: String): SignInResult =
        answer {
            Clerk.auth.signInWithPassword {
                identifier = email
                this.password = password
            }.then { after(it) }
        }

    override suspend fun signUp(email: String, password: String): SignInResult =
        answer {
            Clerk.auth.signUp {
                this.email = email
                this.password = password
            }.then { attempt ->
                signUp = attempt

                when {
                    attempt.status == SignUp.Status.COMPLETE -> sessionStarted(attempt.createdSessionId)
                    EMAIL_ADDRESS in attempt.unverifiedFields -> sendSignUpCode(attempt)
                    // The instance asks for something the app has no step for.
                    else -> SignInResult.Failed(SignInError.UNSUPPORTED)
                }
            }
        }

    override suspend fun sendCode(email: String, purpose: CodePurpose): SignInResult =
        answer {
            when (purpose) {
                CodePurpose.SIGN_UP -> signUpAttempt()?.let { sendSignUpCode(it) } ?: interrupted
                CodePurpose.SIGN_IN ->
                    Clerk.auth.signIn { this.email = email }.then { attempt ->
                        attempt.sendCode { this.email = email }.then { codeSent(it, purpose) }
                    }
                CodePurpose.PASSWORD_RESET -> Clerk.auth.signIn { this.email = email }.then { sendResetCode(it) }
                CodePurpose.NEW_DEVICE -> signInAttempt(*SECOND_STEP)?.let { sendSecondStepCode(it) } ?: interrupted
            }
        }

    override suspend fun verifyCode(purpose: CodePurpose, code: String): SignInResult =
        answer {
            when (purpose) {
                CodePurpose.SIGN_UP ->
                    signUpAttempt()?.let { attempt ->
                        attempt.verifyCode(code, VerificationType.EMAIL).then { verified ->
                            signUp = verified

                            if (verified.status == SignUp.Status.COMPLETE) {
                                sessionStarted(verified.createdSessionId)
                            } else {
                                SignInResult.Failed(SignInError.UNSUPPORTED)
                            }
                        }
                    } ?: interrupted
                CodePurpose.SIGN_IN ->
                    signInAttempt(SignIn.Status.NEEDS_FIRST_FACTOR)
                        ?.attemptFirstFactor(SignIn.AttemptFirstFactorParams.EmailCode(code))
                        ?.then { after(it) }
                        ?: interrupted
                CodePurpose.PASSWORD_RESET ->
                    signInAttempt(SignIn.Status.NEEDS_FIRST_FACTOR)
                        ?.attemptFirstFactor(SignIn.AttemptFirstFactorParams.ResetPasswordEmailCode(code))
                        ?.then { after(it) }
                        ?: interrupted
                CodePurpose.NEW_DEVICE ->
                    signInAttempt(*SECOND_STEP)
                        ?.attemptSecondFactor(SignIn.AttemptSecondFactorParams.EmailCode(code))
                        ?.then { after(it) }
                        ?: interrupted
            }
        }

    override suspend fun setNewPassword(password: String): SignInResult =
        answer {
            // The other devices stay signed in: the Stylist asked for a new password, not to be signed out elsewhere.
            signInAttempt(SignIn.Status.NEEDS_NEW_PASSWORD)?.resetPassword(password, false)?.then { after(it) } ?: interrupted
        }

    /** The sign-in under way if it waits for one of [statuses]: this process's, or the one Clerk's client kept. */
    private fun signInAttempt(vararg statuses: SignIn.Status): SignIn? = (signIn ?: Clerk.auth.currentSignIn)?.takeIf { it.status in statuses }

    private fun signUpAttempt(): SignUp? = signUp ?: Clerk.auth.currentSignUp

    private val interrupted: SignInResult
        get() = SignInResult.Failed(SignInError.INTERRUPTED)

    /** What comes after a step of signing in that Clerk accepted. */
    private suspend fun after(attempt: SignIn): SignInResult {
        signIn = attempt

        return when (attempt.status) {
            SignIn.Status.COMPLETE -> sessionStarted(attempt.createdSessionId)
            SignIn.Status.NEEDS_NEW_PASSWORD -> SignInResult.NewPasswordNeeded
            // A new device, or an account with a second step: the proof the app offers is a code sent to the address.
            SignIn.Status.NEEDS_CLIENT_TRUST, SignIn.Status.NEEDS_SECOND_FACTOR -> sendSecondStepCode(attempt)
            else -> SignInResult.Failed(SignInError.UNSUPPORTED)
        }
    }

    private suspend fun sendSecondStepCode(attempt: SignIn): SignInResult =
        if (attempt.supportedSecondFactors.orEmpty().any { it.strategy == EMAIL_CODE }) {
            attempt.sendMfaEmailCode().then { codeSent(it, CodePurpose.NEW_DEVICE) }
        } else {
            SignInResult.Failed(SignInError.UNSUPPORTED)
        }

    /**
     * Sends the code of a password reset. Clerk's own `sendResetPasswordCode` is not used: it takes
     * the address from the factor for signing in by emailed code, and where the instance does not
     * offer that way of signing in it sends an empty address, which Clerk refuses.
     */
    private suspend fun sendResetCode(attempt: SignIn): SignInResult {
        // No such factor: the account has no password that a code could reset.
        val addressId = resetCodeAddressId(attempt.supportedFirstFactors) ?: return SignInResult.Failed(SignInError.UNSUPPORTED)

        return attempt
            .prepareFirstFactor(SignIn.PrepareFirstFactorParams.ResetPasswordEmailCode(emailAddressId = addressId))
            .then { codeSent(it, CodePurpose.PASSWORD_RESET) }
    }

    private suspend fun sendSignUpCode(attempt: SignUp): SignInResult =
        attempt.sendEmailCode().then { sent ->
            signUp = sent
            SignInResult.CodeSent(CodePurpose.SIGN_UP)
        }

    private fun codeSent(attempt: SignIn, purpose: CodePurpose): SignInResult {
        signIn = attempt
        return SignInResult.CodeSent(purpose)
    }

    /**
     * Clerk has created a session. The gateway's state follows what Clerk itself reports, so the
     * step is over only once Clerk reports the session: until then the sign-in screen stays busy.
     */
    private suspend fun sessionStarted(sessionId: String?): SignInResult {
        if (Clerk.session == null && sessionId != null) {
            Clerk.auth.setActive(sessionId)
        }

        val reported = withTimeoutOrNull(SESSION_WAIT_MILLIS) {
            combine(Clerk.isAuthFlowCompleteFlow, Clerk.userFlow) { complete, user -> complete && user != null }.first { it }
        }

        signIn = null
        signUp = null

        return if (reported == true) SignInResult.SignedIn else SignInResult.Failed(SignInError.GENERIC)
    }

    /** Clerk's builders throw on input they will not send. Nothing thrown in a step may take the app down. */
    private suspend fun answer(step: suspend () -> SignInResult): SignInResult =
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SignInResult.Failed(if (error.isConnectionFailure()) SignInError.NETWORK else SignInError.GENERIC)
        }

    private inline fun <T : Any> ClerkResult<T, ClerkErrorResponse>.then(next: (T) -> SignInResult): SignInResult =
        when (this) {
            is ClerkResult.Success -> next(value)
            is ClerkResult.Failure -> {
                val first = firstError()

                SignInResult.Failed(
                    signInError(
                        code = first?.code,
                        param = first?.meta?.get("param_name").textOrNull(),
                        status = code,
                        isConnection = throwable?.isConnectionFailure() == true,
                    ),
                )
            }
        }

    private fun ClerkResult.Failure<ClerkErrorResponse>.firstError() = error?.errors?.firstOrNull()

    private companion object {
        const val EMAIL_CODE = "email_code"
        const val EMAIL_ADDRESS = "email_address"
        const val SESSION_WAIT_MILLIS = 10_000L

        /** What Clerk answers a sign-in with when the email has no account: the Stylist is creating one. */
        val NO_ACCOUNT_CODES = setOf("form_identifier_not_found", "invitation_account_not_exists")

        val SECOND_STEP = arrayOf(SignIn.Status.NEEDS_CLIENT_TRUST, SignIn.Status.NEEDS_SECOND_FACTOR)
    }
}

private const val RESET_PASSWORD_EMAIL_CODE = "reset_password_email_code"

/**
 * The id of the address a password reset code goes to: that of the reset's own factor among the
 * [factors] of a sign-in, whatever else the account can be signed in with. Null when it has none.
 */
internal fun resetCodeAddressId(factors: List<Factor>?): String? = factors.orEmpty().firstOrNull { it.strategy == RESET_PASSWORD_EMAIL_CODE }?.emailAddressId
