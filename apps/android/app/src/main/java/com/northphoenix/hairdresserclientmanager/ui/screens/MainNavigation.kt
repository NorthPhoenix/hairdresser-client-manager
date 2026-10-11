package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
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
import com.northphoenix.hairdresserclientmanager.ui.components.fittedTextStyle
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalDate
import kotlinx.coroutines.delay

private object Routes {
    const val MAIN = "main"
    const val SERVICES = "services"
    const val APPOINTMENT = "appointment/{id}"
    const val NEW_APPOINTMENT = "appointment-new?date={date}"
    const val CLIENT = "client?id={id}"

    fun appointment(id: String) = "appointment/$id"

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
    // Set while the app is out of view, so that only a return reloads, not the first start.
    var away by remember { mutableStateOf(false) }

    // Back in front: the lists may have been changed elsewhere meanwhile. A screen that shows one
    // item (an Appointment, a Client's history) asks for it again itself when it comes into view.
    LifecycleStartEffect(viewModel) {
        if (away) {
            viewModel.refreshOnReturn()
        }
        away = false
        onStopOrDispose { away = true }
    }

    // In view: the Stylist's day can end, at midnight in the Stylist's timezone. A new timezone has another midnight to wait for.
    // The clock is looked at before the first wait too: Android may have built the screen again, around lists that are of yesterday.
    val lifecycleOwner = LocalLifecycleOwner.current
    val zone by viewModel.zone.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, lifecycleOwner, zone) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.checkDay()
                delay(viewModel.millisUntilDayCheck())
            }
        }
    }

    NavHost(navController, startDestination = Routes.MAIN, modifier = Modifier.fillMaxSize()) {
        composable(Routes.MAIN) { entry ->
            MainScreen(viewModel, open = { route -> navController.from(entry) { navigate(route) } })
        }
        composable(Routes.SERVICES) { entry ->
            ServiceMenuScreen(viewModel, onBack = { navController.from(entry) { popBackStack() } })
        }
        composable(
            Routes.APPOINTMENT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            AppointmentDetailScreen(
                viewModel = viewModel,
                appointmentId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenClient = { navController.from(entry) { navigate(Routes.client(it)) } },
            )
        }
        composable(
            Routes.NEW_APPOINTMENT,
            arguments = listOf(navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            NewAppointmentScreen(
                viewModel = viewModel,
                initialDate = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                onBack = { navController.from(entry) { popBackStack() } },
                onCreated = { appointment ->
                    navController.from(entry) {
                        popBackStack()
                        navigate(Routes.appointment(appointment.id))
                    }
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
                onBack = { navController.from(entry) { popBackStack() } },
                onOpenAppointment = { id -> navController.from(entry) { navigate(Routes.appointment(id)) } },
            )
        }
    }
}

/**
 * Navigates from [entry]'s screen only while that screen is still the one on top. Every screen
 * leaves and opens other screens through here, so each of them does it once: the back stack
 * changes with the first tap, and a second tap during the transition, on Back or on whatever
 * opens a screen, finds another screen on top and does nothing. The same goes for a callback that
 * arrives after the Stylist has left (a save that finishes late), which would otherwise act on the
 * screen underneath.
 */
private fun NavHostController.from(entry: NavBackStackEntry, action: NavHostController.() -> Unit) {
    if (currentBackStackEntry?.id == entry.id) {
        action()
    }
}

@Composable
private fun MainScreen(viewModel: AppViewModel, open: (route: String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MainTab.TODAY) }
    // What a tab holds (search text, list position, an unsaved Settings draft) is kept while another tab is shown.
    val tabStates = rememberSaveableStateHolder()
    val openAppointment: (String) -> Unit = { id -> open(Routes.appointment(id)) }
    val calendarDate by viewModel.calendarDate.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    // The tab labels get smaller together, as far as the longest of them needs to stay whole in its tab.
    val tabLabels = MainTab.entries.map { stringResource(it.label) }
    val barWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val tabLabelStyle = fittedTextStyle(tabLabels, MaterialTheme.typography.labelMedium, tabLabelWidth(barWidth, tabLabels.size))

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
                            // One line at every font size. The ellipsis is for a tab too narrow for its label at any readable size.
                            label = { Text(stringResource(item.label), style = tabLabelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
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
                MainTab.TODAY -> NewFab(stringResource(R.string.new_appointment_title), Icons.Rounded.Add) { open(Routes.newAppointment(null)) }
                MainTab.CALENDAR -> NewFab(stringResource(R.string.new_appointment_title), Icons.Rounded.Add) { open(Routes.newAppointment(calendarDate)) }
                MainTab.CLIENTS -> NewFab(stringResource(R.string.new_client_action), Icons.Rounded.PersonAddAlt1) { open(Routes.client(null)) }
                MainTab.PROFILE -> Unit
            }
        },
    ) { padding ->
        // The tab bar's part of the screen is taken off here, so a tab that makes room for the keyboard makes room for the rest of it only.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.TODAY -> TodayScreen(viewModel, openAppointment)
                    MainTab.CALENDAR -> CalendarScreen(viewModel, openAppointment)
                    MainTab.CLIENTS -> ClientsScreen(viewModel, onOpenClient = { open(Routes.client(it)) })
                    MainTab.PROFILE -> ProfileScreen(viewModel, onOpenServiceMenu = { open(Routes.SERVICES) })
                }
            }
            BusyBar(busy, Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * The width a label has in its tab of the [NavigationBar]. The bar shares its width among the tabs
 * with 8 dp between them: Material's own measure, which it does not expose. 4 dp are kept clear at
 * both edges of a tab, as some releases of Material do themselves and others leave to the label,
 * and one more dp for the rounding to whole pixels.
 */
internal fun tabLabelWidth(barWidth: Dp, tabs: Int): Dp = (barWidth - 8.dp * (tabs - 1)) / tabs - 4.dp * 2 - 1.dp

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

