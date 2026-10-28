package app.jolt

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast

class AppPickerActivity : Activity() {
    private class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var prefs: Prefs
    private lateinit var selected: MutableSet<String>
    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private var query = ""
    private val adapter = Adapter()
    private val browsers by lazy { AutoWatch.browsers(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.blockRemainingMs(AndroidClock(this)) > 0) {
            Toast.makeText(this, "Locked until the block ends.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        selected = prefs.watched.toMutableSet()

        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = rounded(Palette.CARD, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    applyFilter()
                }
            })
        }

        val list = ListView(this).apply {
            adapter = this@AppPickerActivity.adapter
            divider = null
            setOnItemClickListener { _, _, position, _ -> toggle(shown[position].pkg) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Palette.BACKGROUND)
            setPadding(dp(16), dp(20), dp(16), 0)
            addView(label("Choose apps to watch", 24f, bold = true))
            addView(
                label("Time in any of these counts toward one shared session, and PANIC blocks them all.", 14f, Palette.MUTED),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) },
            )
            addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        })

        Thread { loadApps() }.start()
    }

    private fun loadApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedWith(compareBy<AppEntry>({ it.pkg !in selected }, { it.label.lowercase() }))
        runOnUiThread {
            all = apps
            applyFilter()
        }
    }

    private fun applyFilter() {
        shown = if (query.isEmpty()) all else all.filter { it.label.contains(query, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        val clock = AndroidClock(this)
        if (prefs.blockRemainingMs(clock) > 0) {
            finish()
            return
        }
        if (pkg in selected && !prefs.settledLock(clock).isOpen(clock)) {
            Toast.makeText(this, "Settings are locked: you can add apps but not remove them.", Toast.LENGTH_SHORT).show()
            return
        }
        if (pkg in selected && prefs.autoWatch && pkg in browsers) {
            Toast.makeText(this, "Browsers are watched automatically. Turn that off on the main screen first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!selected.remove(pkg)) selected.add(pkg)
        prefs.watched = selected
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row()
            row.bind(shown[position])
            return row
        }
    }

    private inner class Row : LinearLayout(this@AppPickerActivity) {
        private val icon = ImageView(context)
        private val name = label("", 16f)
        private val check = CheckBox(context).apply {
            isClickable = false
            isFocusable = false
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, dp(64))
            setPadding(dp(4), 0, dp(4), 0)
            addView(icon, LayoutParams(dp(40), dp(40)))
            addView(name, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            addView(check)
        }

        fun bind(entry: AppEntry) {
            icon.setImageDrawable(entry.icon)
            name.text = entry.label
            check.isChecked = entry.pkg in selected
        }
    }
}
