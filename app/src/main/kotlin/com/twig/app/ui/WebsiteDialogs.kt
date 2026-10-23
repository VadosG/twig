package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.Rect
import android.util.Log
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.WebsitePermission
import com.twig.app.WebsiteOrientation
import com.twig.app.secure.Secrets

object WebsiteDialogs {
    fun edit(ctx: Context, site: Website? = null) = editForm(ctx, site)

    fun add(ctx: Context, initial: Website) = editForm(ctx, initial = initial)

    fun defaults(ctx: Context) = editForm(ctx, defaults = true)

    private fun editForm(ctx: Context, site: Website? = null, defaults: Boolean = false,
        initial: Website = site ?: if (defaults) WebsiteStore.defaults(ctx) else WebsiteStore.newSite(ctx),
        onDefaultsSaved: (() -> Unit)? = null) {
        val padding = (20 * ctx.resources.displayMetrics.density).toInt()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val name = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_name)
            setSingleLine()
            setText(initial.name)
        }
        val url = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(initial.url)
        }
        form.addView(name)
        form.addView(url)
        form.addView(TextView(ctx).apply { setText(R.string.website_basic_auth) })
        val authUsername = EditText(ctx).apply {
            tag = R.string.website_basic_auth_username
            hint = ctx.getString(R.string.website_basic_auth_username)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(site?.basicAuthUsername.orEmpty())
        }
        val authPassword = EditText(ctx).apply {
            tag = R.string.website_basic_auth_password
            hint = ctx.getString(R.string.website_basic_auth_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(site?.basicAuthPassword.orEmpty())
        }
        form.addView(authUsername)
        form.addView(authPassword)
        fun option(label: Int, checked: Boolean) = CheckBox(ctx).apply {
            tag = label // Identify each option within the scrollable form.
            setText(label)
            isChecked = checked
            form.addView(this)
        }
        val openFullscreen = option(R.string.website_open_fullscreen, initial.openFullscreen)
        val fullscreen = option(R.string.website_fullscreen, initial.fullscreen)
        val doubleTap = option(R.string.website_double_tap_toolbar, initial.doubleTapToolbar)
        val blockScreenshots = option(R.string.website_block_screenshots,
            initial.blockScreenshots)
        val backgroundRunning = option(R.string.website_allow_background_running, site?.allowBackgroundRunning ?: false).apply {
            text = ctx.getString(R.string.website_allow_background_running_hint,
                ctx.getString(R.string.website_allow_background_running))
        }
        val statusBar = option(R.string.website_hide_status_bar, initial.hideStatusBar)
        val navigationBar = option(R.string.website_hide_navigation_bar, initial.hideNavigationBar)
        val pullToRefresh = option(R.string.website_pull_to_refresh, initial.pullToRefresh)
        val selection = option(R.string.website_disable_text_selection, initial.disableTextSelection)
        val scrollAxis = option(R.string.website_lock_scroll_axis, initial.lockScrollAxis)
        val desktop = option(R.string.website_desktop_site, site?.desktopSite ?: false)
        form.addView(TextView(ctx).apply { setText(R.string.website_orientation) })
        val orientation = RadioGroup(ctx).apply {
            tag = R.string.website_orientation
            val labels = listOf(
                WebsiteOrientation.SYSTEM to R.string.website_orientation_system,
                WebsiteOrientation.FREE to R.string.website_orientation_free,
                WebsiteOrientation.LOCKED to R.string.website_orientation_locked,
                WebsiteOrientation.LANDSCAPE to R.string.website_orientation_landscape,
                WebsiteOrientation.PORTRAIT to R.string.website_orientation_portrait,
            )
            for ((mode, label) in labels) addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = mode
                setText(label)
            })
            val selected = site?.orientation ?: WebsiteOrientation.SYSTEM
            check(findViewWithTag<RadioButton>(selected).id)
        }
        form.addView(orientation)
        val advanced = LinearLayout(ctx).apply {
            this.orientation = LinearLayout.VERTICAL
            tag = "website_advanced_fields"
            visibility = View.GONE
        }
        val advancedHeader = TextView(ctx).apply {
            tag = R.string.website_advanced
            setText(R.string.website_advanced)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
            compoundDrawablePadding = (8 * ctx.resources.displayMetrics.density).toInt()
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
            val background = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            setBackgroundResource(background.resourceId)
            isFocusable = true
        }
        fun expandAdvanced(show: Boolean) {
            if (!show && advanced.hasFocus()) {
                ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(advanced.windowToken, 0)
                advanced.findFocus()?.clearFocus()
            }
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advancedHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                if (show) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, 0)
        }
        advancedHeader.setOnClickListener { expandAdvanced(advanced.visibility != View.VISIBLE) }
        form.addView(advancedHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * ctx.resources.displayMetrics.density).toInt()))
        form.addView(advanced)
        val permissionFields = LinearLayout(ctx).apply {
            this.orientation = LinearLayout.VERTICAL
            visibility = if (defaults) View.GONE else View.VISIBLE
            advanced.addView(this)
        }
        fun permission(label: Int, value: WebsitePermission) = android.widget.Spinner(ctx).apply {
            tag = label
            permissionFields.addView(TextView(ctx).apply { setText(label) })
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.website_permission_ask, R.string.website_permission_allow,
                    R.string.website_permission_deny).map { ctx.getString(it) })
            setSelection(value.ordinal)
            permissionFields.addView(this)
        }
        val microphone = permission(R.string.website_microphone, initial.microphonePermission)
        val camera = permission(R.string.website_camera, initial.cameraPermission)
        val location = permission(R.string.website_location, initial.locationPermission)
        fun field(label: Int, hint: Int, value: String, multiline: Boolean = false) = EditText(ctx).apply {
            tag = label
            advanced.addView(TextView(ctx).apply { setText(label) })
            this.hint = ctx.getString(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (multiline) {
                minLines = 3
                maxLines = 7
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                typeface = Typeface.MONOSPACE
                textSize = 13f
            } else setSingleLine()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(value)
            advanced.addView(this)
        }
        advanced.addView(TextView(ctx).apply { setText(R.string.website_global_settings_hint) })
        val userAgent = field(R.string.website_user_agent,
            if (defaults) R.string.website_user_agent_hint else R.string.website_user_agent_global_hint, initial.userAgent)
        val headers = field(R.string.website_headers, R.string.website_headers_hint, site?.headers.orEmpty(), true)
        val script = field(R.string.website_script, R.string.website_script_hint, initial.script, true)
        val style = field(R.string.website_style, R.string.website_style_hint, initial.style, true)
        val hidden = field(R.string.website_hidden_elements, R.string.website_hidden_elements_hint, initial.hiddenElements, true)
        val blocked = field(R.string.website_blocked_requests, R.string.website_blocked_requests_hint, initial.blockedRequests, true)
        advanced.addView(TextView(ctx).apply { setText(R.string.website_custom_scope) })
        if (defaults) {
            // Keep one editor and validation path for both per-site and default settings.
            val sharedOptions = setOf(openFullscreen, fullscreen, statusBar, navigationBar,
                pullToRefresh, selection, scrollAxis, blockScreenshots, doubleTap)
            for (index in 0 until form.indexOfChild(advancedHeader)) {
                val view = form.getChildAt(index)
                if (view !in sharedOptions) view.visibility = View.GONE
            }
            headers.visibility = View.GONE
            advanced.getChildAt(advanced.indexOfChild(headers) - 1).visibility = View.GONE
            expandAdvanced(true)
        }
        fun advancedError(field: EditText, message: Int) {
            expandAdvanced(true)
            field.error = ctx.getString(message)
            field.requestFocus()
            field.post { field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true) }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (defaults) R.string.website_defaults else if (site == null) R.string.website_add else R.string.website_edit)
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(if (!defaults && site == null) ctx.getString(R.string.website_defaults) else null, null)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnShowListener {
            if (!defaults && site == null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editForm(ctx, defaults = true) {
                    val values = WebsiteStore.defaults(ctx)
                    openFullscreen.isChecked = values.openFullscreen
                    fullscreen.isChecked = values.fullscreen
                    doubleTap.isChecked = values.doubleTapToolbar
                    blockScreenshots.isChecked = values.blockScreenshots
                    statusBar.isChecked = values.hideStatusBar
                    navigationBar.isChecked = values.hideNavigationBar
                    pullToRefresh.isChecked = values.pullToRefresh
                    selection.isChecked = values.disableTextSelection
                    scrollAxis.isChecked = values.lockScrollAxis
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = WebsiteStore.normalizeUrl(url.text.toString())
                if (!defaults && address == null) {
                    url.error = ctx.getString(R.string.website_invalid_url)
                    return@setOnClickListener
                }
                if (!WebsiteCustomization.validBasicAuth(authUsername.text.toString(), authPassword.text.toString())) {
                    authUsername.error = ctx.getString(R.string.website_basic_auth_invalid)
                    authUsername.requestFocus()
                    return@setOnClickListener
                }
                val agent = userAgent.text.toString().trim()
                if (!WebsiteCustomization.validUserAgent(agent)) {
                    advancedError(userAgent, R.string.website_user_agent_invalid)
                    return@setOnClickListener
                }
                if (WebsiteCustomization.parseHeaders(headers.text.toString()) == null) {
                    advancedError(headers, R.string.website_headers_invalid)
                    return@setOnClickListener
                }
                if (WebsiteRequestBlocker.parse(blocked.text.toString()) == null) {
                    advancedError(blocked, R.string.website_blocked_requests_invalid)
                    return@setOnClickListener
                }
                if (defaults) {
                    WebsiteStore.saveDefaults(ctx, initial.copy(userAgent = agent,
                        doubleTapToolbar = doubleTap.isChecked, blockScreenshots = blockScreenshots.isChecked,
                        openFullscreen = openFullscreen.isChecked, fullscreen = fullscreen.isChecked,
                        hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                        pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                        lockScrollAxis = scrollAxis.isChecked,
                        script = script.text.toString(), style = style.text.toString(),
                        hiddenElements = hidden.text.toString(), blockedRequests = blocked.text.toString()))
                    onDefaultsSaved?.invoke()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val validatedAddress = requireNotNull(address)
                val label = name.text.toString().trim().ifEmpty { android.net.Uri.parse(validatedAddress).host.orEmpty() }
                val saved = (site ?: Website(name = label, url = validatedAddress)).copy(
                    name = label, url = validatedAddress, fullscreen = fullscreen.isChecked, doubleTapToolbar = doubleTap.isChecked,
                    openFullscreen = openFullscreen.isChecked, allowBackgroundRunning = backgroundRunning.isChecked,
                    blockScreenshots = blockScreenshots.isChecked,
                    microphonePermission = WebsitePermission.entries[microphone.selectedItemPosition],
                    cameraPermission = WebsitePermission.entries[camera.selectedItemPosition],
                    locationPermission = WebsitePermission.entries[location.selectedItemPosition],
                    hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                    pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                    lockScrollAxis = scrollAxis.isChecked, desktopSite = desktop.isChecked,
                    orientation = orientation.findViewById<RadioButton>(orientation.checkedRadioButtonId).tag as WebsiteOrientation,
                    userAgent = agent, headers = headers.text.toString(),
                    basicAuthUsername = authUsername.text.toString(), basicAuthPassword = authPassword.text.toString(),
                    script = script.text.toString(), style = style.text.toString(), hiddenElements = hidden.text.toString(),
                    blockedRequests = blocked.text.toString(),
                )
                val originChanged = site != null && !WebsiteCustomization.sameOrigin(site.url, saved.url)
                try {
                    WebsiteStore.save(ctx, if (originChanged) saved.copy(
                        microphonePermission = WebsitePermission.ASK, cameraPermission = WebsitePermission.ASK,
                        locationPermission = WebsitePermission.ASK) else saved)
                } catch (_: Secrets.KeyUnavailable) {
                    if (saved.basicAuthUsername.isNotEmpty() || saved.basicAuthPassword.isNotEmpty()) {
                        authPassword.error = ctx.getString(R.string.backup_locked)
                        authPassword.requestFocus()
                    } else advancedError(headers, R.string.backup_locked)
                    return@setOnClickListener
                }
                if (site != null && site.url != saved.url) WebsiteIcons.forget(ctx, site)
                updateShortcut(ctx, saved)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun menu(ctx: Context, site: Website) {
        val actions = arrayOf(ctx.getString(R.string.website_edit),
            ctx.getString(R.string.action_pin_shortcut), ctx.getString(R.string.action_delete))
        AlertDialog.Builder(ctx).setTitle(site.name).setItems(actions) { _, index ->
            when (index) {
                0 -> edit(ctx, site)
                1 -> pin(ctx, site)
                2 -> AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.website_delete_confirm, site.name))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        WebsiteStore.remove(ctx, site.id)
                        WebsiteIcons.forget(ctx, site)
                        ShortcutManagerCompat.disableShortcuts(ctx, listOf("website_${site.id}"),
                            ctx.getString(R.string.website_missing))
                    }.show()
            }
        }.show()
    }

    fun shortcut(ctx: Context, site: Website, icon: Bitmap? = WebsiteIcons.cached(site)): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, "website_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(if (icon != null) ShortcutIcons.of(ctx, icon) else ShortcutIcons.of(ctx, R.drawable.ic_website))
            .setIntent(WebsiteActivity.intent(ctx, site.id))
            .build()

    /** Use the cache on a fresh process too, so editing a label keeps its real icon. */
    fun updateShortcut(ctx: Context, site: Website) {
        val app = ctx.applicationContext
        WebsiteIcons.load(app, site) { icon ->
            val saved = WebsiteStore.all(app).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            try {
                ShortcutManagerCompat.updateShortcuts(app, listOf(shortcut(app, saved, icon)))
            } catch (e: Exception) {
                Log.w("WebsiteIcons", "Could not update website shortcut", e)
            }
        }
    }

    fun pin(ctx: Context, site: Website) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            Toast.makeText(ctx, R.string.pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        WebsiteIcons.load(ctx, site) { icon ->
            val saved = WebsiteStore.all(ctx).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut(ctx, saved, icon), null)
        }
    }
}
