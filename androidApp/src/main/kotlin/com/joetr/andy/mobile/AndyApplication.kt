package com.joetr.andy.mobile

import android.app.Application
import com.joetr.andy.mobile.di.AndyMobileGraph
import com.joetr.andy.mobile.di.openAndyMobileGraph

class AndyApplication : Application() {
    lateinit var graph: AndyMobileGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = openAndyMobileGraph(this)
    }
}

val Application.mobileGraph: AndyMobileGraph
    get() = (this as AndyApplication).graph
