package com.zimdarselectric.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zimdarselectric.app.ui.BooksScreen
import com.zimdarselectric.app.ui.CustomersScreen
import com.zimdarselectric.app.ui.ExpenseDetailScreen
import com.zimdarselectric.app.ui.HomeScreen
import com.zimdarselectric.app.ui.Mono
import com.zimdarselectric.app.ui.MoreScreen
import com.zimdarselectric.app.ui.QuoteEditor
import com.zimdarselectric.app.ui.QuotesScreen
import com.zimdarselectric.app.ui.SettingsScreen
import com.zimdarselectric.app.ui.StockScreen
import com.zimdarselectric.app.ui.ToolsScreen
import com.zimdarselectric.app.ui.Z
import com.zimdarselectric.app.ui.ZTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ZTheme { App() } }
    }
}

enum class Tab(val label: String) { HOME("Home"), QUOTES("Quotes"), STOCK("Stock"), BOOKS("Books"), MORE("More") }

/** Screens opened on top of the tabs. */
sealed interface Page {
    data class EditQuote(val id: Long) : Page
    data object Customers : Page
    data object Tools : Page
    data object Settings : Page
    data class ExpenseDetail(val id: Long) : Page
}

class Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Page>()
    fun open(p: Page) { stack.add(p) }
    fun back(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
    fun goTab(t: Tab) { stack.clear(); tab = t }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val nav = remember { Nav() }
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.HOME) {
        if (!nav.back()) nav.tab = Tab.HOME
    }
    Column(Modifier.fillMaxSize().background(Z.bg).statusBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val top = nav.stack.lastOrNull()) {
                is Page.EditQuote -> QuoteEditor(vm, top.id, onBack = { nav.back() })
                Page.Customers -> CustomersScreen(vm, onBack = { nav.back() })
                Page.Tools -> ToolsScreen(onBack = { nav.back() })
                Page.Settings -> SettingsScreen(vm, onBack = { nav.back() })
                is Page.ExpenseDetail -> ExpenseDetailScreen(vm, top.id, onBack = { nav.back() })
                null -> when (nav.tab) {
                    Tab.HOME -> HomeScreen(vm, nav)
                    Tab.QUOTES -> QuotesScreen(vm, nav)
                    Tab.STOCK -> StockScreen(vm)
                    Tab.BOOKS -> BooksScreen(vm, nav)
                    Tab.MORE -> MoreScreen(nav)
                }
            }
        }
        if (nav.stack.isEmpty()) BottomTabs(nav)
        else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BottomTabs(nav: Nav) {
    Row(Modifier.fillMaxWidth().background(Z.tab).navigationBarsPadding()) {
        Tab.entries.forEach { t ->
            val active = t == nav.tab
            Column(
                Modifier.weight(1f).clickable { nav.goTab(t) }.padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).background(if (active) Z.neon else Z.tab))
                Spacer(Modifier.height(12.dp))
                Text(t.label.uppercase(), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = if (active) Z.neon else Z.muted)
            }
        }
    }
}
