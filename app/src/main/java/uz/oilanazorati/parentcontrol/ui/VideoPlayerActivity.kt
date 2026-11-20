package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/** Video yo'riqnomani to'liq ekranda (oqim orqali) ijro etadi. */
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        videoView = VideoView(this)
        root.addView(videoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        val progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            maxLines = 2
            setPadding(dp(16), dp(14), dp(80), 0)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))
        root.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END))
        setContentView(root)

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener {
            progress.visibility = View.GONE
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            Toast.makeText(this, "Videoni yuklab bo'lmadi — internetni tekshiring", Toast.LENGTH_LONG).show()
            true
        }
        if (url.isBlank()) { finish(); return }
        videoView.setVideoURI(Uri.parse(url))
    }

    override fun onPause() {
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
        super.onPause()
    }

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
    }
}
