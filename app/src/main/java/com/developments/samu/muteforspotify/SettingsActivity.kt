package com.developments.samu.muteforspotify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.developments.samu.muteforspotify.utilities.AppUtil

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val mutePref: EditTextPreference? =
                this.findPreference(getString(R.string.settings_mute_key))
            val unmutePref: EditTextPreference? =
                this.findPreference(getString(R.string.settings_unmute_key))

            mutePref?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED
                }
                setOnPreferenceChangeListener { preference, newValue ->
                    with(newValue as String) {
                        isNotBlank()
                        toLongOrNull() != null
                    }
                }
            }

            unmutePref?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED
                }
                setOnPreferenceChangeListener { preference, newValue ->
                    with(newValue as String) {
                        isNotBlank()
                        toLongOrNull() != null
                    }
                }
            }

            this.findPreference<Preference>(getString(R.string.settings_doki_key))?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUtil.DKMA_URL)))
                    true
                }
            }
        }
    }
}
