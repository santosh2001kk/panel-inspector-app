package com.example.testerapigoogle

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

data class GuideSlide(
    val scene: GuideIllustrationView.Scene,
    val isDo: Boolean,
    val title: String,
    val description: String
)

class PhotoGuideActivity : AppCompatActivity() {

    private val slides = listOf(
        GuideSlide(
            GuideIllustrationView.Scene.FULL_FRAME, true,
            "Full Panel in Frame",
            "Step back until all four edges of the panel are visible including the narrow VBB compartment on the side. If any edge is cut off, the AI will miss the VBB."
        ),
        GuideSlide(
            GuideIllustrationView.Scene.TOO_CLOSE, false,
            "Don't Shoot Too Close",
            "If the panel is cropped at the edges, the AI cannot detect the full layout. Always show the entire panel including its frame."
        ),
        GuideSlide(
            GuideIllustrationView.Scene.GOOD_LIGHT, true,
            "Use Good Lighting",
            "Even ambient light works best — overhead fluorescent lighting is ideal. Make sure label text and breaker details are clearly visible."
        ),
        GuideSlide(
            GuideIllustrationView.Scene.DARK, false,
            "Avoid Dark Conditions",
            "Dark or backlit images significantly reduce detection accuracy. Turn on room lights or use your phone torch before scanning."
        ),
        GuideSlide(
            GuideIllustrationView.Scene.STRAIGHT, true,
            "Hold Phone Straight On",
            "Keep your phone parallel to the panel face — perfectly perpendicular. A straight shot gives the most accurate bounding box detection."
        ),
        GuideSlide(
            GuideIllustrationView.Scene.GLARE, false,
            "Avoid Glare & Reflections",
            "Glass-fronted panels cause reflections that hide details. Shift slightly sideways until the glare disappears, then capture."
        )
    )

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: View
    private lateinit var dotsLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_guide)

        viewPager   = findViewById(R.id.viewPager)
        btnNext     = findViewById(R.id.btnNext)
        btnSkip     = findViewById(R.id.btnSkip)
        dotsLayout  = findViewById(R.id.dotsLayout)


        viewPager.adapter = GuideAdapter(slides)
        buildDots(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buildDots(position)
                val isLast = position == slides.lastIndex
                btnNext.text = if (isLast) "Got it — Start Scanning" else "Next"
                btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
            }
        })

        btnNext.setOnClickListener {
            val next = viewPager.currentItem + 1
            if (next < slides.size) viewPager.currentItem = next
            else finish()
        }

        btnSkip.setOnClickListener { finish() }
    }

    override fun finish() {
        // Mark guide as seen so it doesn't show again automatically
        getSharedPreferences("google_api_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("photo_guide_seen", true).apply()
        super.finish()
    }

    private fun buildDots(selected: Int) {
        dotsLayout.removeAllViews()
        val dp = resources.displayMetrics.density
        slides.forEachIndexed { i, _ ->
            val dot = View(this)
            val size  = if (i == selected) (10 * dp).toInt() else (8 * dp).toInt()
            val lp    = LinearLayout.LayoutParams(size, size).also { it.setMargins((5 * dp).toInt(), 0, (5 * dp).toInt(), 0) }
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == selected) Color.parseColor("#3DCD58") else Color.parseColor("#BDBDBD"))
            }
            dot.background = shape
            dot.layoutParams = lp
            dotsLayout.addView(dot)
        }
    }
}

// ── Adapter ───────────────────────────────────────────────────────────

class GuideAdapter(private val slides: List<GuideSlide>) :
    RecyclerView.Adapter<GuideAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge       : TextView               = view.findViewById(R.id.tvBadge)
        val illustration: GuideIllustrationView  = view.findViewById(R.id.illustrationView)
        val title       : TextView               = view.findViewById(R.id.tvTitle)
        val description : TextView               = view.findViewById(R.id.tvDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.photo_guide_page, parent, false))

    override fun getItemCount() = slides.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slide = slides[position]

        // Badge
        val green = Color.parseColor("#3DCD58")
        val red   = Color.parseColor("#E53935")
        val badgeColor = if (slide.isDo) green else red
        val shape = GradientDrawable().apply {
            setColor(badgeColor)
            cornerRadius = 20 * holder.itemView.resources.displayMetrics.density
        }
        holder.badge.background = shape
        holder.badge.text = if (slide.isDo) "✓  DO THIS" else "✕  DON'T DO THIS"

        holder.illustration.scene = slide.scene
        holder.title.text = slide.title
        holder.description.text = slide.description
    }
}
