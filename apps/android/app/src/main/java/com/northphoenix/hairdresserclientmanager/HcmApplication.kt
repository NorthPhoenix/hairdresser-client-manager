package com.northphoenix.hairdresserclientmanager

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.northphoenix.hairdresserclientmanager.core.AppContainer

class HcmApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    // Share Images arrive as SVG markup.
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
}
