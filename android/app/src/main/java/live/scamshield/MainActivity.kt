package live.scamshield

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import live.scamshield.ui.MainScreen
import live.scamshield.ui.ScamShieldTheme

class MainActivity : ComponentActivity() {
    private val scrollToAnalysis = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { ScamShieldTheme { MainScreen(scrollToAnalysisSignal = scrollToAnalysis.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SCROLL_TO_ANALYSIS, false) == true) scrollToAnalysis.intValue++
    }

    companion object {
        const val EXTRA_SCROLL_TO_ANALYSIS = "scroll_to_analysis"
    }
}
