package com.alcoholchecker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alcoholchecker.ui.*
import com.alcoholchecker.ui.theme.AlcoholCheckerTheme
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel

class MainActivity : ComponentActivity() {

    private val vm: AlcoholCheckerViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled reactively via BleManager state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()

        setContent {
            AlcoholCheckerTheme {
                MainNavigation(vm)
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.BLUETOOTH)
                @Suppress("DEPRECATION")
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val navItems = listOf(
    NavItem("home",    "ホーム",   Icons.Default.Home),
    NavItem("check",   "チェック", Icons.Default.Air),
    NavItem("history", "記録",     Icons.Default.History),
    NavItem("drivers", "ドライバー", Icons.Default.People),
)

@Composable
private fun MainNavigation(vm: AlcoholCheckerViewModel) {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute   = backstackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = backstackEntry?.destination?.hierarchy?.any { it.route == item.route } == true,
                        onClick  = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        icon  = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = "home",
            modifier         = Modifier.padding(innerPadding)
        ) {
            composable("home")    { HomeScreen(vm) }
            composable("check")   { CheckScreen(vm) }
            composable("history") { HistoryScreen(vm) }
            composable("drivers") { DriverScreen(vm) }
        }
    }
}
