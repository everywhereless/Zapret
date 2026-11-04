package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class StrategyPickerActivity : AppCompatActivity() {
    private lateinit var cards: Map<StrategyProfiles.Profile, MaterialCardView>
    private var selected = StrategyProfiles.Profile.Balanced
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_picker)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.strategy_picker_title)
        val ids = mapOf(
            StrategyProfiles.Profile.Auto to R.id.strategy_auto_card,
            StrategyProfiles.Profile.Balanced to R.id.strategy_balanced_card,
            StrategyProfiles.Profile.Split to R.id.strategy_split_card,
            StrategyProfiles.Profile.Tls to R.id.strategy_tls_card,
            StrategyProfiles.Profile.Messaging to R.id.strategy_messaging_card,
            StrategyProfiles.Profile.Games to R.id.strategy_games_card,
            StrategyProfiles.Profile.Strong to R.id.strategy_strong_card)
        cards = ids.mapValues { (_, id) -> findViewById(id) }
        cards.forEach { (profile, card) ->
            card.isCheckable = true
            card.contentDescription = getString(profile.title)
            card.setOnClickListener { select(profile) }
        }
        val restored = savedInstanceState?.getString("selected_profile")
        select(StrategyProfiles.Profile.entries.firstOrNull { it.id == restored }
            ?: StrategyProfiles.selected(getPreferences()))
        findViewById<com.google.android.material.button.MaterialButton>(R.id.strategy_apply_button)
            .setOnClickListener {
                StrategyProfiles.apply(getPreferences(), selected)
                setResult(RESULT_OK)
                finish()
            }
    }
    private fun select(profile: StrategyProfiles.Profile) {
        selected = profile
        cards.forEach { (candidate, card) ->
            val checked = candidate == profile
            card.isChecked = checked
            card.strokeColor = ContextCompat.getColor(this,
                if (checked) R.color.zapret_ink else R.color.zapret_line)
            card.strokeWidth = ((if (checked) 1.5f else 1f) * resources.displayMetrics.density).toInt()
            ViewCompat.setStateDescription(card,
                getString(if (checked) R.string.strategy_selected else R.string.strategy_not_selected))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_profile", selected.id)
        super.onSaveInstanceState(outState)
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
