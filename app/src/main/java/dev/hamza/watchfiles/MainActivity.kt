package dev.hamza.watchfiles

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.hamza.watchfiles.ui.BrowseScreen
import dev.hamza.watchfiles.ui.EditServerScreen
import dev.hamza.watchfiles.ui.MainViewModel
import dev.hamza.watchfiles.ui.NewFolderScreen
import dev.hamza.watchfiles.ui.RemoteFileScreen
import dev.hamza.watchfiles.ui.ServersScreen
import dev.hamza.watchfiles.ui.TransferScreen
import dev.hamza.watchfiles.ui.WatchFileScreen
import dev.hamza.watchfiles.ui.WatchFilesScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just granted Downloads access in Settings.
        vm.refreshStorage()
    }
}

private fun browseRoute(path: String) = "browse?path=" + Uri.encode(path)
private fun newFolderRoute(path: String) = "newfolder?path=" + Uri.encode(path)
private fun uploadRoute(path: String) = "watchfiles?upload=" + Uri.encode(path)

@Composable
private fun App(vm: MainViewModel) {
    val nav = rememberSwipeDismissableNavController()
    val pathArg = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })

    SwipeDismissableNavHost(navController = nav, startDestination = "servers") {
        composable("servers") {
            ServersScreen(
                vm,
                onBrowse = { nav.navigate(browseRoute("")) },
                onAdd = { nav.navigate("edit") },
                onWatchFiles = { nav.navigate("watchfiles") },
            )
        }
        composable("edit") {
            EditServerScreen(vm, onDone = { nav.popBackStack("servers", inclusive = false) })
        }
        composable("browse?path={path}", arguments = pathArg) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            BrowseScreen(
                vm, path,
                onFolder = { nav.navigate(browseRoute(it)) },
                onFile = { vm.selectedRemote = it; nav.navigate("remotefile") },
                onUploadHere = { nav.navigate(uploadRoute(path)) },
                onNewFolder = { nav.navigate(newFolderRoute(path)) },
                onEditServer = { nav.navigate("edit") },
            )
        }
        composable("remotefile") {
            RemoteFileScreen(vm, onDownload = { nav.navigate("transfer") { popUpTo("remotefile") { inclusive = true } } }, onDeleted = { nav.popBackStack() })
        }
        composable("newfolder?path={path}", arguments = pathArg) { entry ->
            NewFolderScreen(vm, entry.arguments?.getString("path").orEmpty(), onDone = { nav.popBackStack() })
        }
        composable(
            "watchfiles?upload={upload}",
            arguments = listOf(navArgument("upload") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val uploadTo = entry.arguments?.getString("upload")
            WatchFilesScreen(
                vm, uploadTo,
                onFile = { vm.selectedLocal = it; nav.navigate("watchfile") },
                onUploadStarted = { nav.navigate("transfer") { popUpTo("watchfiles?upload={upload}") { inclusive = true } } },
            )
        }
        composable("watchfile") {
            WatchFileScreen(vm, onDeleted = { nav.popBackStack() })
        }
        composable("transfer") {
            TransferScreen(vm, onClose = { nav.popBackStack() })
        }
    }
}
