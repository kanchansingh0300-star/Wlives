package com.example.livewall

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val reqPick = 1
    private val intervals = intArrayOf(5, 10, 30, 60, 300)
    private val labels = arrayOf("5 seconds", "10 seconds", "30 seconds", "1 minute", "5 minutes")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Store.prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            root.setPadding(dp(20), dp(20) + insets.systemWindowInsetTop,
                dp(20), dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "LiveWall"; textSize = 28f })
        root.addView(Button(this).apply {
            text = "Add photos / videos"; setOnClickListener { pick() }
        })
        root.addView(Button(this).apply {
            text = "Set as live wallpaper"; setOnClickListener { applyWallpaper() }
        })
        root.addView(Switch(this).apply {
            text = "Pause when Battery Saver is on"
            isChecked = prefs.getBoolean(Store.KEY_PAUSE, true)
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Store.KEY_PAUSE, on).apply()
            }
        })
        root.addView(TextView(this).apply { text = "Change photo every" })
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(intervals.indexOf(prefs.getInt(Store.KEY_INTERVAL, 10)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (prefs.getInt(Store.KEY_INTERVAL, 10) != intervals[pos])
                        prefs.edit().putInt(Store.KEY_INTERVAL, intervals[pos]).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        status = TextView(this).apply { setPadding(0, dp(16), 0, dp(8)) }
        root.addView(status)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val files = Store.list(this)
        status.text = if (files.isEmpty()) "No media yet. Tap “Add photos / videos”."
        else "${files.size} item(s), played in this order"
        files.forEachIndexed { n, f ->
            val video = Store.isVideo(f)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
            }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            }
            val label = TextView(this).apply {
                text = (if (video) "Video " else "Photo ") + (n + 1)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(12) }
            }
            val rm = Button(this).apply {
                text = "Remove"
                setOnClickListener { Store.remove(this@MainActivity, f); refresh() }
            }
            row.addView(iv); row.addView(label); row.addView(rm)
            listBox.addView(row)
            io.execute {
                val bmp = try {
                    if (video) ThumbnailUtils.createVideoThumbnail(f, Size(192, 192), null)
                    else ThumbnailUtils.createImageThumbnail(f, Size(192, 192), null)
                } catch (e: Exception) { null }
                if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
            }
        }
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, reqPick)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPick || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        else data.data?.let { uris += it }
        status.text = "Adding ${uris.size} file(s)…"
        io.execute {
            val ok = uris.count { Store.add(this, it) }
            runOnUiThread {
                Toast.makeText(this, "Added $ok of ${uris.size}", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun applyWallpaper() {
        val comp = ComponentName(this, LiveWallpaperService::class.java)
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
