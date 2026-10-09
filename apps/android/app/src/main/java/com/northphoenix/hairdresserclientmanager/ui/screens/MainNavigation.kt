package com.northphoenix.hairdresserclientmanager.ui.screens

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.HcmSnackbarHost
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalDate

private object Routes {
    const val MAIN = "main"
    const val SERVICES = "services"
    const val APPOINTMENT = "appointment/{id}?startsAt={startsAt}"
    const val NEW_APPOINTMENT = "appointment-new?date={date}"
    const val CLIENT = "client?id={id}"

    fun appointment(id: String, startsAt: String) = "appointment/$id?startsAt=${Uri.encode(startsAt)}"

    fun newAppointment(date: LocalDate?) = "appointment-new" + (date?.let { "?date=$it" } ?: "")

    fun client(id: String?) = "client" + (id?.let { "?id=$it" } ?: "")
}

private enum class MainTab(@StringRes val label: Int, val icon: ImageVector) {
    TODAY(R.string.tab_today, Icons.Rounded.WbSunny),
    CALENDAR(R.string.tab_calendar, Icons.Rounded.CalendarMonth),
    CLIENTS(R.string.tab_clients, Icons.Rounded.Group),
    PROFILE(R.string.tab_profile, Icons.Rounded.Settings),
}

@Composable
fun MainNavigation(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val openAppointment: (String, String) -> Unit = { id, startsAt -> navController.navigate(Routes.appointment(id, startsAt)) }

    NavHost(navController, startDestination = Routes.MAIN, modifier = Modifier.fillMaxSize()) {
        composable(Routes.MAIN) {
            MainScreen(viewModel, navController, openAppointment)
        }
        composable(Routes.SERVICES) {
            ServiceMenuScreen(viewModel, onBack = navController::popBackStack)
        }
        composable(
            Routes.APPOINTMENT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("startsAt") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            AppointmentDetailScreen(
                viewModel = viewModel,
                appointmentId = entry.arguments?.getString("id").orEmpty(),
                startsAt = entry.arguments?.getString("startsAt").orEmpty(),
                onBack = navController::popBackStack,
                onOpenClient = { navController.navigate(Routes.client(it)) },
            )
        }
        composable(
            Routes.NEW_APPOINTMENT,
            arguments = listOf(navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            NewAppointmentScreen(
                viewModel = viewModel,
                initialDate = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                onBack = navController::popBackStack,
                onCreated = { appointment ->
                    navController.popBackStack()
                    openAppointment(appointment.id, appointment.startsAt)
                },
            )
        }
        composable(
            Routes.CLIENT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ClientEditorScreen(
                viewModel = viewModel,
                clientId = entry.arguments?.getString("id"),
                onBack = navController::popBackStack,
                onOpenAppointment = openAppointment,
            )
        }
    }
}

@Composable
private fun MainScreen(viewModel: AppViewModel, navController: NavHostController, openAppointment: (String, String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainTab.TODAY) }
    val calendarDate by viewModel.calendarDate.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { HcmSnackbarHost() },
        bottomBar = {
            Column {
                Hairline()
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                            label = { Text(stringResource(item.label), style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                MainTab.TODAY -> NewFab(stringResource(R.string.new_appointment_title), Icons.Rounded.Add) { navController.navigate(Routes.newAppointment(null)) }
                MainTab.CALENDAR -> NewFab(stringResource(R.string.new_appointment_title), Icons.Rounded.Add) { navController.navigate(Routes.newAppointment(calendarDate)) }
                MainTab.CLIENTS -> NewFab(stringResource(R.string.new_client_action), Icons.Rounded.PersonAddAlt1) { navController.navigate(Routes.client(null)) }
                MainTab.PROFILE -> Unit
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.TODAY -> TodayScreen(viewModel, openAppointment)
                MainTab.CALENDAR -> CalendarScreen(viewModel, openAppointment)
                MainTab.CLIENTS -> ClientsScreen(viewModel, onOpenClient = { navController.navigate(Routes.client(it)) })
                MainTab.PROFILE -> ProfileScreen(viewModel, onOpenServiceMenu = { navController.navigate(Routes.SERVICES) })
            }
            BusyBar(busy, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun NewFab(text: String, icon: ImageVector, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        // The extended FAB does not expose its label to accessibility services on its own.
        modifier = Modifier.semantics { contentDescription = text },
        containerColor = HcmColors.Ink,
        contentColor = HcmColors.Surface,
        shape = MaterialTheme.shapes.large,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(text, style = MaterialTheme.typography.labelLarge) },
    )
}

/** Thin progress line shown while a change is being saved. */
@Composable
fun BusyBar(busy: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(busy, modifier = modifier.fillMaxWidth(), enter = fadeIn(), exit = fadeOut()) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

