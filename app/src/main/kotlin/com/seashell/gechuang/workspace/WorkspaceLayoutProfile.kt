package com.seashell.gechuang.workspace

import com.seashell.gechuang.display.SlotGeometry
import com.seashell.gechuang.display.WORKSPACE_SLOT_COUNT

/** One recorded layout shared by panels, main-task fitting and all preview displays. */
data class WorkspaceLayoutProfile(
    val geometry: WorkspaceGeometry,
    val densityDpi: Int,
    val gapPixels: Int,
) {
    data class Slot(val width: Int, val height: Int, val top: Int)
    data class Source(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    data class PreviewTransform(val scaleX: Float, val scaleY: Float, val x: Float, val y: Float)

    init {
        require(densityDpi > 0 && gapPixels > 0)
        require(geometry.sidebarWidth > gapPixels)
    }

    private val leftGeometry = geometry.copy(sidebarSide = SidebarSide.LEFT)
    private val rightGeometry = geometry.copy(sidebarSide = SidebarSide.RIGHT)
    // Side cells must use the exact same logical size and density as the main
    // cell. A different density makes Android recreate the activity on every
    // promotion (Douyin even restarts its SplashActivity), which is the real
    // source of the ~0.9 s blank gap. Rendering resolution is kept full-size
    // here; preview textures shrink it for display.
    val canvas = SlotGeometry.forRecordedDisplay(
        geometry.availableWidth,
        geometry.availableHeight,
        densityDpi,
    )
    // Apps lay out directly at the recorded usable resolution. Fit that complete
    // buffer into a slot; do not crop navigation pixels off an already laid-out app.
    val source = Source(0, 0, canvas.width, canvas.height)
    val slots: List<Slot> = run {
        val height = (geometry.sidebarLogicalHeight - gapPixels * (WORKSPACE_SLOT_COUNT - 1))
            .coerceAtLeast(WORKSPACE_SLOT_COUNT)
        val base = height / WORKSPACE_SLOT_COUNT
        val remainder = height % WORKSPACE_SLOT_COUNT
        List(WORKSPACE_SLOT_COUNT) { index -> Slot(geometry.sidebarWidth - gapPixels,
            base + if (index < remainder) 1 else 0,
            index * (base + gapPixels) + minOf(index, remainder)) }
    }

    fun geometry(side: SidebarSide): WorkspaceGeometry = if (side == SidebarSide.LEFT) leftGeometry else rightGeometry

    fun rotatedTo(rotation: Int): WorkspaceLayoutProfile =
        if (rotation == geometry.displayRotation) this else copy(geometry = geometry.rotatedTo(rotation))

    /** Undo TextureView's default stretching and fit the complete recorded canvas.
     * Slot bounds have the same aspect ratio apart from integer-pixel rounding. */
    fun previewTransform(width: Int, height: Int): PreviewTransform {
        require(width > 0 && height > 0)
        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        return PreviewTransform(canvas.width * scale / width, canvas.height * scale / height,
            (width - source.width * scale) / 2f - source.left * scale,
            (height - source.height * scale) / 2f - source.top * scale)
    }

    fun encode(): String = listOf("7", geometry.screenWidth, geometry.screenHeight,
        geometry.topHeight, geometry.sidebarWidth, densityDpi, gapPixels,
        geometry.navigationInsets.left, geometry.navigationInsets.top,
        geometry.navigationInsets.right, geometry.navigationInsets.bottom,
        geometry.contentWidth, geometry.contentHeight, geometry.contentScale,
        canvas.width, canvas.height,
        source.left, source.top, source.right, source.bottom,
        *slots.flatMap { listOf(it.width, it.height, it.top) }.toTypedArray(),
        geometry.displayRotation).joinToString(",")

    companion object {
        private const val SLOT_RENDER_SCALE = 0.5f

        fun create(width: Int, height: Int, densityDpi: Int, insets: WorkspaceNavigationInsets,
            displayRotation: Int = 0) =
            WorkspaceLayoutProfile(WorkspaceGeometry.forDisplay(width, height, density = densityDpi / 160f,
                navigationInsets = insets, displayRotation = displayRotation),
                densityDpi, (densityDpi / 160f).toInt().coerceAtLeast(1))

        fun decode(encoded: String?): WorkspaceLayoutProfile? = runCatching {
            val p = encoded?.split(',') ?: return@runCatching null
            val version = p.firstOrNull() ?: return@runCatching null
            if (version !in setOf("2", "3", "4", "5", "6", "7") ||
                p.size != (if (version in setOf("5", "6", "7")) 30 else 29)) return@runCatching null
            val profile = WorkspaceLayoutProfile(WorkspaceGeometry(p[1].toInt(), p[2].toInt(),
                p[3].toInt(), p[4].toInt(), SidebarSide.RIGHT,
                WorkspaceNavigationInsets(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
                displayRotation = if (version in setOf("5", "6", "7")) p[29].toInt() else 0),
                p[5].toInt(), p[6].toInt())
            val expected = profile.encode().split(',').toMutableList()
            expected[0] = p[0]
            if (version !in setOf("5", "6", "7")) expected.removeAt(29)
            if (version != "7") {
                // Older profiles recorded the full- or half-resolution preview canvas.
                // It is derived, so accept it and migrate to the current canvas.
                for (index in 14..19) expected[index] = p[index]
            }
            if (expected.joinToString(",") != encoded) return@runCatching null
            if (version in setOf("4", "5", "6", "7")) profile else {
                // Recalculate the old slot proportions once using the already
                // recorded display and navigation mode; never resample metrics.
                create(profile.geometry.screenWidth, profile.geometry.screenHeight,
                    profile.densityDpi, profile.geometry.navigationInsets)
            }
        }.getOrNull()

    }
}
