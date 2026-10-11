package com.northphoenix.hairdresserclientmanager.auth

import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A step of signing in or of creating an account. Email and password only (ADR 0004). */
enum class SignInStep {
    /** The email address. It decides between signing in and creating an account. */
    EMAIL,

    /** The password of the account the email belongs to. */
    PASSWORD,

    /** The password for an account that does not exist yet. */
    CREATE_PASSWORD,

    /** The code that was sent to the email address: see [CodePurpose]. */
    CODE,

    /** A new password, once the code of a password reset was right. */
    NEW_PASSWORD,
}

/** What the emailed code is for. */
enum class CodePurpose {
    /** Confirms the address of a new account. */
    SIGN_UP,

    /** Signs in without the password. */
    SIGN_IN,

    /** Confirms a sign-in the provider wants a second proof for, such as one from a new device. */
    NEW_DEVICE,

    /** Allows a new password to be set. */
    PASSWORD_RESET,
}

/**
 * Why a step was refused. The provider's own text is English only and is never shown: each of
 * these has a message in the app language (`SignInError.messageRes`).
 */
enum class SignInError {
    EMAIL_INVALID,
    EMAIL_TAKEN,
    NO_ACCOUNT,
    WRONG_PASSWORD,
    PASSWORD_TOO_SHORT,
    PASSWORD_COMPROMISED,
    PASSWORD_REFUSED,
    PASSWORDS_DIFFER,
    WRONG_CODE,
    CODE_EXPIRED,
    CODE_ATTEMPTS_USED_UP,
    TOO_MANY_REQUESTS,
    ACCOUNT_LOCKED,

    /** The account needs a way of signing in the app does not offer. */
    UNSUPPORTED,

    /** The provider no longer has the attempt this step belongs to: it has to start again. */
    INTERRUPTED,
    NETWORK,
    GENERIC,
}

/**
 * The [SignInError] for what Clerk reports: the [code] of its error, the [param] the error is
 * about, the HTTP [status], and whether the request failed for lack of a connection.
 */
fun signInError(code: String?, param: String? = null, status: Int? = null, isConnection: Boolean = false): SignInError =
    when {
        code == "form_password_incorrect" || code == "form_password_or_identifier_incorrect" -> SignInError.WRONG_PASSWORD
        code == "form_password_pwned" -> SignInError.PASSWORD_COMPROMISED
        // Too short, too long, not strong enough: the rules are the provider's and can be changed there.
        code != null && code.startsWith("form_password_") -> SignInError.PASSWORD_REFUSED
        code == "form_code_incorrect" -> SignInError.WRONG_CODE
        code == "verification_expired" -> SignInError.CODE_EXPIRED
        code == "verification_failed" -> SignInError.CODE_ATTEMPTS_USED_UP
        code == "user_locked" -> SignInError.ACCOUNT_LOCKED
        code == "too_many_requests" || status == 429 -> SignInError.TOO_MANY_REQUESTS
        code == "form_identifier_exists" -> SignInError.EMAIL_TAKEN
        code == "form_identifier_not_found" -> SignInError.NO_ACCOUNT
        code == "strategy_for_user_invalid" -> SignInError.UNSUPPORTED
        code in INVALID_VALUE_CODES && (param == "identifier" || param == "email_address") -> SignInError.EMAIL_INVALID
        code in INVALID_VALUE_CODES && param == "password" -> SignInError.PASSWORD_REFUSED
        code in INVALID_VALUE_CODES && param == "code" -> SignInError.WRONG_CODE
        code == null && isConnection -> SignInError.NETWORK
        else -> SignInError.GENERIC
    }

private val INVALID_VALUE_CODES = setOf("form_param_format_invalid", "form_param_value_invalid")

/** What the provider made of a step. */
sealed interface SignInResult {
    /** A session has started. The gateway's state says so too, and the sign-in screen goes away. */
    data object SignedIn : SignInResult

    /** The email belongs to an account. [canUseEmailCode]: it can also be signed in to with an emailed code. */
    data class AccountFound(val canUseEmailCode: Boolean) : SignInResult

    data object NoAccount : SignInResult

    data class CodeSent(val purpose: CodePurpose) : SignInResult

    data object NewPasswordNeeded : SignInResult

    data class Failed(val error: SignInError) : SignInResult
}

/** The identity provider's part of [SignInFlow]. Clerk in production (`ClerkSignInBackend`). */
interface SignInBackend {
    suspend fun findAccount(email: String): SignInResult

    suspend fun signIn(email: String, password: String): SignInResult

    suspend fun signUp(email: String, password: String): SignInResult

    /** Sends a code for [purpose], the first one or another one. */
    suspend fun sendCode(email: String, purpose: CodePurpose): SignInResult

    suspend fun verifyCode(purpose: CodePurpose, code: String): SignInResult

    suspend fun setNewPassword(password: String): SignInResult
}

data class SignInState(
    val step: SignInStep = SignInStep.EMAIL,
    val email: String = "",
    /** Means something on [SignInStep.CODE] only. */
    val codePurpose: CodePurpose = CodePurpose.SIGN_IN,
    val canUseEmailCode: Boolean = false,
    val busy: Boolean = false,
    val error: SignInError? = null,
    /** Counts the refusals, so that the same one for the same text is still a new one to show. */
    val failures: Int = 0,
    /** The codes sent during this visit to the code step. Zero when the step came back after the app was killed. */
    val codesSent: Int = 0,
) {
    /**
     * What Android keeps while the app is away, also when it kills the app in the meantime: where
     * the Stylist was and the email. Never anything typed into a password field.
     */
    fun kept(): List<String> = arrayListOf(step.name, email, codePurpose.name, canUseEmailCode.toString())

    companion object {
        /** The state [kept] was taken from, or null for anything else. */
        fun fromKept(kept: List<String>): SignInState? {
            if (kept.size != 4) {
                return null
            }

            val step = SignInStep.entries.firstOrNull { it.name == kept[0] } ?: return null
            val purpose = CodePurpose.entries.firstOrNull { it.name == kept[2] } ?: return null

            return SignInState(step = step, email = kept[1], codePurpose = purpose, canUseEmailCode = kept[3].toBoolean())
        }
    }
}

/**
 * Signing in and creating an account, step by step. It outlives the screen (a rotation, a change
 * of font size), so a request that is on its way still arrives. Everything here runs on [scope]'s
 * one thread.
 */
class SignInFlow(private val backend: SignInBackend, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = mutableState.asStateFlow()

    private var request: Job? = null

    /** False until something has happened here in this process, a sign-in included: only then is there nothing newer than what Android kept. */
    private var started = false

    /**
     * Takes up what Android kept ([SignInState.kept]) from before it killed the app. The attempt
     * itself is still with the provider, so the step it was at can go on. A flow that has already
     * started knows better than what was kept, and ignores it. Answers whether it took it up.
     */
    fun restore(kept: List<String>): Boolean {
        if (started) {
            return false
        }

        started = true
        val state = SignInState.fromKept(kept) ?: return false
        mutableState.value = state
        return true
    }

    /**
     * Back to the first step with nothing left over, once someone has signed in. What Android
     * kept is from before that sign-in, so it is not taken up after it either: a sign-out later
     * in this process starts at the first step.
     */
    fun reset() {
        request?.cancel()
        started = true
        mutableState.value = SignInState()
    }

    fun submitEmail(typed: String) {
        val email = typed.trim()

        if (email.isEmpty() || !ClientRules.isEmail(email)) {
            refuse(SignInError.EMAIL_INVALID)
            return
        }

        send(email) { backend.findAccount(email) }
    }

    fun submitPassword(password: String) {
        if (password.isNotEmpty()) {
            send { backend.signIn(current.email, password) }
        }
    }

    fun createAccount(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            refuse(SignInError.PASSWORD_TOO_SHORT)
            return
        }

        send { backend.signUp(current.email, password) }
    }

    fun forgotPassword() = send { backend.sendCode(current.email, CodePurpose.PASSWORD_RESET) }

    fun useEmailCode() = send { backend.sendCode(current.email, CodePurpose.SIGN_IN) }

    fun resendCode() = send { backend.sendCode(current.email, current.codePurpose) }

    fun submitCode(code: String) {
        if (code.length == CODE_LENGTH) {
            send { backend.verifyCode(current.codePurpose, code) }
        }
    }

    fun setNewPassword(password: String, repeated: String) {
        when {
            password.length < MIN_PASSWORD_LENGTH -> refuse(SignInError.PASSWORD_TOO_SHORT)
            password != repeated -> refuse(SignInError.PASSWORDS_DIFFER)
            else -> send { backend.setNewPassword(password) }
        }
    }

    /** The Stylist is changing what was refused: the refusal no longer describes what is in the field. */
    fun clearError() {
        if (current.error != null) {
            mutableState.update { it.copy(error = null) }
        }
    }

    /**
     * One step back, at once: a request that is still on its way is given up, and the step it
     * leads to starts clean, without the error of an earlier visit.
     */
    fun back() {
        val previous = when (current.step) {
            SignInStep.EMAIL -> return
            SignInStep.PASSWORD, SignInStep.CREATE_PASSWORD -> SignInStep.EMAIL
            SignInStep.CODE -> if (current.codePurpose == CodePurpose.SIGN_UP) SignInStep.CREATE_PASSWORD else SignInStep.PASSWORD
            SignInStep.NEW_PASSWORD -> SignInStep.PASSWORD
        }

        request?.cancel()
        started = true
        mutableState.update { it.at(previous) }
    }

    private val current: SignInState
        get() = mutableState.value

    private fun refuse(error: SignInError) {
        started = true
        mutableState.update { it.copy(error = error, failures = it.failures + 1) }
    }

    /** Runs one request at a time: a second tap while the first is on its way does nothing. */
    private fun send(email: String = current.email, call: suspend () -> SignInResult) {
        if (current.busy) {
            return
        }

        started = true
        mutableState.update { it.copy(email = email, busy = true, error = null) }
        request = scope.launch {
            val result = call()

            // Given up meanwhile ([back], [reset]): its answer belongs to a step that is gone.
            ensureActive()
            mutableState.update { it.after(result) }
        }
    }

    private fun SignInState.after(result: SignInResult): SignInState =
        when (result) {
            // Stays busy: the gateway reports the session, the screen goes away, and [reset] follows.
            SignInResult.SignedIn -> this
            is SignInResult.AccountFound -> at(SignInStep.PASSWORD).copy(canUseEmailCode = result.canUseEmailCode)
            SignInResult.NoAccount -> at(SignInStep.CREATE_PASSWORD)
            is SignInResult.CodeSent -> {
                val another = step == SignInStep.CODE && codePurpose == result.purpose

                at(SignInStep.CODE).copy(codePurpose = result.purpose, codesSent = if (another) codesSent + 1 else 1)
            }
            SignInResult.NewPasswordNeeded -> at(SignInStep.NEW_PASSWORD)
            is SignInResult.Failed ->
                if (result.error == SignInError.INTERRUPTED) {
                    at(SignInStep.EMAIL).copy(error = result.error, failures = failures + 1)
                } else {
                    copy(busy = false, error = result.error, failures = failures + 1)
                }
        }

    /** This state at [step], with nothing of the step before it: not busy, no error, no code counted. */
    private fun SignInState.at(step: SignInStep): SignInState = copy(step = step, busy = false, error = null, codesSent = 0)

    companion object {
        /** Clerk's codes are six digits. */
        const val CODE_LENGTH = 6

        /** The shortest password Clerk can be set to accept. A stricter rule of its own it enforces itself. */
        const val MIN_PASSWORD_LENGTH = 8
    }
}
