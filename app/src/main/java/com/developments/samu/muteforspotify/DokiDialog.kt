package com.developments.samu.muteforspotify

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.doubledot.doki.views.DokiContentView


class DokiDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dokiCustomView = View.inflate(context, R.layout.activity_doki, null)
        dokiCustomView?.findViewById<DokiContentView?>(R.id.doki_content)?.let {
            it.setButtonsVisibility(false)
            it.loadContent()
        }
        return MaterialAlertDialogBuilder(requireContext()).apply {
            setView(dokiCustomView)
            setPositiveButton(R.string.doki_close) { _, _ ->
                dismiss()
            }
        }.create()
    }

    fun show(context: FragmentActivity, tag: String = DOKI_DIALOG_TAG) {
        show(context.supportFragmentManager, tag)
    }

    companion object {
        private const val DOKI_DIALOG_TAG = "doki_dialog"
        fun show(context: FragmentActivity, tag: String = DOKI_DIALOG_TAG) {
            DokiDialog().show(context, tag)
        }
    }
}

class DokiThemedActivity : AppCompatActivity() {

    private val dokiContent: DokiContentView? by lazy {
        findViewById<DokiContentView?>(R.id.doki_content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doki)

        dokiContent?.setOnCloseListener { supportFinishAfterTransition() }
        dokiContent?.loadContent()
    }

}