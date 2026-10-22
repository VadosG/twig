package com.twig.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.twig.app.R
import com.twig.app.WebsiteStore

/** Public browser entry point. Forward only the validated URL, never caller-supplied extras. */
class WebsiteLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val address = if (intent.action == Intent.ACTION_VIEW &&
            uri?.scheme?.lowercase() in setOf("http", "https")) {
            WebsiteStore.normalizeUrl(uri.toString())
        } else null
        if (address == null) Toast.makeText(this, R.string.website_invalid_url, Toast.LENGTH_SHORT).show()
        else startActivity(WebsiteActivity.browserIntent(this, address))
        finish()
    }
}
