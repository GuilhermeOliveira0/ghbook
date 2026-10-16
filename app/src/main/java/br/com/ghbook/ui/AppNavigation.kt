package br.com.ghbook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.ghbook.AppGraph
import br.com.ghbook.IncomingEvents

object Routes {
    const val LIBRARY = "library"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    fun reader(id: String, chapter: Int = -1, paragraph: Int = -1) = "reader/$id?chapter=$chapter&paragraph=$paragraph"
    fun player(id: String) = "player/$id"
    fun chapters(id: String) = "chapters/$id"
    fun audio(id: String? = null) = if (id == null) "audio" else "audio?bookId=$id"
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val incomingPdf by IncomingEvents.pdf.collectAsState()
    val openPlayer by IncomingEvents.openPlayer.collectAsState()
    val playback by AppGraph.playback.state.collectAsState()

    LaunchedEffect(incomingPdf) {
        if (incomingPdf != null) nav.navigate(Routes.IMPORT) { launchSingleTop = true }
    }
    LaunchedEffect(openPlayer) {
        if (openPlayer) {
            IncomingEvents.openPlayer.value = false
            playback.bookId?.let { nav.navigate(Routes.player(it)) { launchSingleTop = true } }
        }
    }

    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) { LibraryScreen(nav) }
        composable(Routes.IMPORT) { ImportScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(
            "reader/{bookId}?chapter={chapter}&paragraph={paragraph}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType },
                navArgument("chapter") { type = NavType.IntType; defaultValue = -1 },
                navArgument("paragraph") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { e ->
            ReaderScreen(
                nav,
                e.arguments!!.getString("bookId")!!,
                e.arguments!!.getInt("chapter"),
                e.arguments!!.getInt("paragraph"),
            )
        }
        composable("player/{bookId}") { e -> PlayerScreen(nav, e.arguments!!.getString("bookId")!!) }
        composable("chapters/{bookId}") { e -> ChaptersScreen(nav, e.arguments!!.getString("bookId")!!) }
        composable(
            "audio?bookId={bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { e -> AudioManagerScreen(nav, e.arguments?.getString("bookId")) }
    }
}

fun NavHostController.backOrLibrary() {
    if (!popBackStack()) navigate(Routes.LIBRARY)
}
