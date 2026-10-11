package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.UUID

/**
 * The key that names one intended creation for the server (`requestKey`), for a form or sheet
 * that creates something: a call sent again with it, a second tap or a retry after a reply that
 * never came, creates nothing more. It is made when the form or sheet comes on screen and is the
 * same for as long as that one is open, also across Android recreating the screen. A sheet that
 * is opened again gets another one; a form that stays open after it has created something asks
 * for another by changing [inputs].
 */
@Composable
fun rememberRequestKey(vararg inputs: Any?): String = rememberSaveable(*inputs) { UUID.randomUUID().toString() }
