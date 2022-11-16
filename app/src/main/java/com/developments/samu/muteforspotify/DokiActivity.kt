package com.developments.samu.muteforspotify

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.doubledot.doki.views.DokiContentView

class DokiActivity : AppCompatActivity() {

    private val dokiContent: DokiContentView? by lazy {
        findViewById(R.id.doki_content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doki)

        dokiContent?.setOnCloseListener { supportFinishAfterTransition() }
        dokiContent?.loadContent()
    }
}
