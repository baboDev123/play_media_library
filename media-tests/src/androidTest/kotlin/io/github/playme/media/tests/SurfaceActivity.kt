package io.github.playme.media.tests

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView

/** A screen-filling SurfaceView, for looking at what the decoders draw. */
class SurfaceActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }
}
