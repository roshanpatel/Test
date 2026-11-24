package app.jolt

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Opens a link from an email or message without opening a browser. There's no address bar
 * and no search: just the page, links on the same site, and other sites after asking. Feeds,
 * search and betting sites are refused, and the viewer closes after [LINK_VIEW_MS].
 */
class LinkActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openedAt = SystemClock.elapsedRealtime()
    private lateinit var web: WebView
    private lateinit var siteLabel: TextView
    private lateinit var timeLabel: TextView

    private val timer = object : Runnable {
        override fun run() {
            val left = LINK_VIEW_MS - (SystemClock.elapsedRealtime() - openedAt)
            if (left <= 0) {
                Toast.makeText(this@LinkActivity, "Time's up for this link.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            timeLabel.text = formatMmSs(left)
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        if (url == null || !isWebLink(url)) {
            finish()
            return
        }

        siteLabel = label("", 15f, bold = true).apply { setSingleLine() }
        timeLabel = label("", 15f, Palette.ACCENT, bold = true)
        val close = label("✕", 22f, Palette.TEXT, bold = true).apply {
            setPadding(dp(16), dp(4), dp(4), dp(4))
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.CARD)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(siteLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(timeLabel)
            addView(close)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false) // pop-up windows open in this view
            webViewClient = Client()
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                download(downloadUrl, userAgent, contentDisposition, mimeType)
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Palette.BACKGROUND)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        open(url)
        handler.post(timer)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf(::isWebLink)?.let(::open)
    }

    /** Loads [url] unless it's refused, in which case says why. */
    private fun open(url: String) {
        val refusal = refusalFor(url)
        if (refusal != null) {
            showRefusal(refusal)
            return
        }
        siteLabel.text = hostOf(url).orEmpty()
        web.loadUrl(url)
    }

    private fun showRefusal(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Not opened")
            .setMessage(reason)
            .setPositiveButton("OK") { _, _ -> if (web.url == null) finish() }
            .setOnCancelListener { if (web.url == null) finish() }
            .show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (!isWebLink(target)) {
                openOutside(request.url)
                return true
            }
            refusalFor(target)?.let {
                showRefusal(it)
                return true
            }
            val here = hostOf(view.url.orEmpty())
            val there = hostOf(target)
            // Redirects (such as an email's click tracking) go straight through. A tap on a
            // link to another site asks first.
            if (here != null && there != null && !sameSite(here, there) && request.hasGesture() && !request.isRedirect) {
                AlertDialog.Builder(this@LinkActivity)
                    .setTitle("Open ${siteOf(there)}?")
                    .setMessage("This link goes to a different site.")
                    .setPositiveButton("Open") { _, _ -> open(target) }
                    .setNegativeButton("Stay here", null)
                    .show()
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            siteLabel.text = hostOf(url).orEmpty()
        }
    }

    /** Hands mailto:, tel: and similar links to the right app. */
    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves a file (a PDF, say) to Downloads. */
    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        android.webkit.CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timer)
        if (::web.isInitialized) {
            web.visibility = View.GONE
            web.destroy()
        }
        super.onDestroy()
    }
}
