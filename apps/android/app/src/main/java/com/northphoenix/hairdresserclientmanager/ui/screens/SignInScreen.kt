package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.SignInError
import com.northphoenix.hairdresserclientmanager.auth.SignInFlow
import com.northphoenix.hairdresserclientmanager.auth.SignInState
import com.northphoenix.hairdresserclientmanager.auth.SignInStep
import com.northphoenix.hairdresserclientmanager.i18n.bodyRes
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.ui.components.BackButton
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenTitle
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import kotlinx.coroutines.delay

/** How long the Stylist waits after a code before the next one can be asked for. */
private const val RESEND_WAIT_SECONDS = 30

/**
 * Signing in and creating an account on the app's own screens: an email address, a password and
 * a code sent to the address (ADR 0004). Every text is the app's, in the app language. A refusal
 * stays under its field until the Stylist changes the field or tries again.
 */
@Composable
fun SignInScreen(flow: SignInFlow, modifier: Modifier = Modifier) {
    // Android keeps the step and the email while the app is away, also when it kills the app in
    // the meantime (the Stylist is in the mail app, reading the code). The save reads them from
    // the flow at that moment; a flow that has not started in this process takes them up again.
    val kept = rememberSaveable(saver = Saver<List<String>, List<String>>(save = { flow.state.value.kept() }, restore = { it })) { emptyList() }
    remember(flow) { flow.restore(kept) }

    val state by flow.state.collectAsStateWithLifecycle()
    val onFirstStep = state.step == SignInStep.EMAIL
    // The password of the two password steps is kept above them: it is still there when the
    // Stylist comes back from the code step. Another email starts with none.
    var password by rememberSaveable(state.email) { mutableStateOf("") }

    BackHandler(enabled = !onFirstStep, onBack = flow::back)

    // One column that scrolls: at a large font size everything is still reached, each link on a line of its own.
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The arrow's row is there on the first step too, so the heading keeps its place from step to step.
        Box(Modifier.heightIn(min = 56.dp).offset(x = (-16).dp), contentAlignment = Alignment.CenterStart) {
            if (!onFirstStep) {
                BackButton(flow::back)
            }
        }

        when (state.step) {
            SignInStep.EMAIL -> EmailStep(state, flow)
            SignInStep.PASSWORD -> PasswordStep(state, flow, password) { password = it }
            SignInStep.CREATE_PASSWORD -> CreatePasswordStep(state, flow, password) { password = it }
            SignInStep.CODE -> CodeStep(state, flow)
            SignInStep.NEW_PASSWORD -> NewPasswordStep(state, flow)
        }
    }
}

@Composable
private fun EmailStep(state: SignInState, flow: SignInFlow) {
    var email by rememberSaveable { mutableStateOf(state.email) }
    val submit = { flow.submitEmail(email) }

    ScreenTitle(
        title = stringResource(R.string.sign_in_title),
        kicker = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.sign_in_email_body),
    )
    HcmTextField(
        value = email,
        onValueChange = {
            email = it
            flow.clearError()
        },
        label = stringResource(R.string.sign_in_email_label),
        placeholder = "name@example.com",
        keyboardType = KeyboardType.Email,
        capitalization = KeyboardCapitalization.None,
        imeAction = ImeAction.Go,
        onImeAction = submit,
        error = state.errorText(),
        errorAttempt = state.failures,
    )
    PrimaryButton(stringResource(R.string.sign_in_continue), onClick = submit, modifier = Modifier.fillMaxWidth(), enabled = email.isNotBlank(), loading = state.busy)
}

@Composable
private fun PasswordStep(state: SignInState, flow: SignInFlow, password: String, onPasswordChange: (String) -> Unit) {
    val submit = { flow.submitPassword(password) }

    ScreenTitle(title = stringResource(R.string.sign_in_password_title), subtitle = state.email)
    PasswordField(
        value = password,
        onValueChange = {
            onPasswordChange(it)
            flow.clearError()
        },
        label = stringResource(R.string.sign_in_password_label),
        onImeAction = submit,
        modifier = Modifier.focusRequester(rememberStepFocus()),
        error = state.errorText(),
        errorAttempt = state.failures,
    )
    PrimaryButton(stringResource(R.string.sign_in_submit), onClick = submit, modifier = Modifier.fillMaxWidth(), enabled = password.isNotEmpty(), loading = state.busy)
    Column {
        TextAction(stringResource(R.string.sign_in_forgot_password), onClick = flow::forgotPassword, enabled = !state.busy)
        if (state.canUseEmailCode) {
            TextAction(stringResource(R.string.sign_in_use_code), onClick = flow::useEmailCode, enabled = !state.busy)
        }
    }
}

@Composable
private fun CreatePasswordStep(state: SignInState, flow: SignInFlow, password: String, onPasswordChange: (String) -> Unit) {
    val submit = { flow.createAccount(password) }

    ScreenTitle(title = stringResource(R.string.sign_in_create_title), subtitle = stringResource(R.string.sign_in_create_body, state.email))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PasswordField(
            value = password,
            onValueChange = {
                onPasswordChange(it)
                flow.clearError()
            },
            label = stringResource(R.string.sign_in_password_label),
            onImeAction = submit,
            modifier = Modifier.focusRequester(rememberStepFocus()),
            error = state.errorText(),
            errorAttempt = state.failures,
        )
        PasswordRule()
    }
    PrimaryButton(stringResource(R.string.sign_in_create_account), onClick = submit, modifier = Modifier.fillMaxWidth(), enabled = password.isNotEmpty(), loading = state.busy)
}

@Composable
private fun CodeStep(state: SignInState, flow: SignInFlow) {
    // A code that was sent again makes the one before it worthless: the field starts empty with each of them.
    var code by rememberSaveable(state.codesSent) { mutableStateOf("") }

    ScreenTitle(title = stringResource(R.string.sign_in_code_title), subtitle = stringResource(state.codePurpose.bodyRes(), state.email))
    HcmTextField(
        value = code,
        onValueChange = { typed ->
            val digits = typed.filter { it in '0'..'9' }.take(SignInFlow.CODE_LENGTH)

            if (digits != code) {
                code = digits
                flow.clearError()
                // The last digit is the Stylist's "continue".
                flow.submitCode(digits)
            }
        },
        label = stringResource(R.string.sign_in_code_label),
        modifier = Modifier.focusRequester(rememberStepFocus()),
        keyboardType = KeyboardType.Number,
        capitalization = KeyboardCapitalization.None,
        imeAction = ImeAction.Go,
        onImeAction = { flow.submitCode(code) },
        error = state.errorText(),
        errorAttempt = state.failures,
    )
    PrimaryButton(
        stringResource(R.string.sign_in_continue),
        onClick = { flow.submitCode(code) },
        modifier = Modifier.fillMaxWidth(),
        enabled = code.length == SignInFlow.CODE_LENGTH,
        loading = state.busy,
    )
    ResendCode(state, flow)
}

/** Asking for another code, after a wait that starts again with every code sent. */
@Composable
private fun ResendCode(state: SignInState, flow: SignInFlow) {
    // A step that came back after the app was killed has sent nothing in this process: no wait.
    var wait by remember(state.codesSent) { mutableIntStateOf(if (state.codesSent > 0) RESEND_WAIT_SECONDS else 0) }

    LaunchedEffect(state.codesSent) {
        while (wait > 0) {
            delay(1_000)
            wait--
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.codesSent > 1) {
            Text(
                stringResource(R.string.sign_in_code_sent_again),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (wait > 0) {
            Text(stringResource(R.string.sign_in_code_resend_wait, wait), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextAction(stringResource(R.string.sign_in_code_resend), onClick = flow::resendCode, enabled = !state.busy)
        }
    }
}

@Composable
private fun NewPasswordStep(state: SignInState, flow: SignInFlow) {
    var password by rememberSaveable { mutableStateOf("") }
    var repeated by rememberSaveable { mutableStateOf("") }
    val submit = { flow.setNewPassword(password, repeated) }
    // What is wrong with the second field is said under it; everything else is about the first.
    val aboutRepeat = state.error == SignInError.PASSWORDS_DIFFER

    ScreenTitle(title = stringResource(R.string.sign_in_new_password_title), subtitle = stringResource(R.string.sign_in_new_password_body, state.email))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                flow.clearError()
            },
            label = stringResource(R.string.sign_in_new_password_label),
            imeAction = ImeAction.Next,
            modifier = Modifier.focusRequester(rememberStepFocus()),
            error = state.errorText().takeIf { !aboutRepeat },
            errorAttempt = state.failures,
        )
        PasswordRule()
    }
    PasswordField(
        value = repeated,
        onValueChange = {
            repeated = it
            flow.clearError()
        },
        label = stringResource(R.string.sign_in_new_password_repeat_label),
        onImeAction = submit,
        error = state.errorText().takeIf { aboutRepeat },
        errorAttempt = state.failures,
    )
    PrimaryButton(
        stringResource(R.string.sign_in_new_password_save),
        onClick = submit,
        modifier = Modifier.fillMaxWidth(),
        enabled = password.isNotEmpty() && repeated.isNotEmpty(),
        loading = state.busy,
    )
}

/** A password field whose eye shows and hides what was typed. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Go,
    onImeAction: (() -> Unit)? = null,
    error: String? = null,
    errorAttempt: Int = 0,
) {
    var shown by rememberSaveable { mutableStateOf(false) }

    HcmTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        keyboardType = KeyboardType.Password,
        capitalization = KeyboardCapitalization.None,
        imeAction = imeAction,
        onImeAction = onImeAction,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (shown) R.string.sign_in_hide_password else R.string.sign_in_show_password),
                )
            }
        },
        error = error,
        errorAttempt = errorAttempt,
    )
}

@Composable
private fun PasswordRule() {
    Text(stringResource(R.string.sign_in_password_rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SignInState.errorText(): String? = error?.let { stringResource(it.messageRes()) }

/**
 * Puts the cursor into the step's first field when the step opens, so the keyboard stays up from
 * one step to the next. Put it on the field's modifier.
 */
@Composable
private fun rememberStepFocus(): FocusRequester {
    val focus = remember { FocusRequester() }

    // A field that cannot take the focus yet is not worth failing for: the Stylist taps it.
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }

    return focus
}
