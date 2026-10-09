package com.northphoenix.hairdresserclientmanager.auth

import android.content.Context
import com.northphoenix.hairdresserclientmanager.core.AppConfig

/** Release builds have no dev sign-in. The real implementation lives in the debug source set. */
@Suppress("UNUSED_PARAMETER")
fun createDevAuthGateway(context: Context, config: AppConfig): AuthGateway? = null
