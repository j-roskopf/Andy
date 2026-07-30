package app.andy.inspectordemo

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity

/**
 * Pure XML layout for exercising dumpsys activity top / unmerged view tree capture.
 */
class TraditionalHierarchyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traditional)
        findViewById<Button>(R.id.traditional_secondary_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.traditional_primary_button).setOnClickListener {
            findViewById<android.widget.TextView>(R.id.traditional_card_body).text =
                "Primary tapped at ${System.currentTimeMillis()}"
        }
    }
}
