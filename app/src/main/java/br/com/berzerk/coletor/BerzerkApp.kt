package br.com.berzerk.coletor

import android.app.Application
import br.com.berzerk.coletor.core.Graph

class BerzerkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
