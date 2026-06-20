package com.privacycamera

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privacycamera.ui.CameraScreen
import com.privacycamera.ui.GalleryScreen
import com.privacycamera.ui.ViewerScreen
import com.privacycamera.ui.theme.PrivacyCameraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots and exclude sensitive content from the recents preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()
        setContent {
            PrivacyCameraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

private object Routes {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val VIEWER = "viewer/{id}"
    fun viewer(id: String) = "viewer/$id"
}

@androidx.compose.runtime.Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // Single ViewModel scoped to the Activity so all screens share the same photo list.
    val viewModel: com.privacycamera.viewmodel.PhotoViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            CameraScreen(
                onOpenGallery = { navController.navigate(Routes.GALLERY) },
                viewModel = viewModel
            )
        }
        composable(Routes.GALLERY) {
            GalleryScreen(
                onBack = { navController.popBackStack() },
                onOpenPhoto = { id -> navController.navigate(Routes.viewer(id)) },
                viewModel = viewModel
            )
        }
        composable(Routes.VIEWER) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ViewerScreen(
                photoId = id,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
    }
}
