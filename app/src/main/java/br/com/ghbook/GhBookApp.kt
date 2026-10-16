package br.com.ghbook

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import br.com.ghbook.audio.AudioGenerationManager
import br.com.ghbook.audio.AudioStore
import br.com.ghbook.data.BookRepository
import br.com.ghbook.data.SettingsRepository
import br.com.ghbook.importer.ImportManager
import br.com.ghbook.playback.PlaybackHub
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Dependências do aplicativo (injeção manual, sem frameworks). */
object AppGraph {
    lateinit var context: Context
        private set
    lateinit var books: BookRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var audio: AudioStore
        private set
    lateinit var playback: PlaybackHub
        private set
    lateinit var importer: ImportManager
        private set
    lateinit var generator: AudioGenerationManager
        private set

    fun init(app: Context) {
        if (::context.isInitialized) return
        context = app
        books = BookRepository(app)
        settings = SettingsRepository(app)
        audio = AudioStore(books)
        playback = PlaybackHub(app)
        importer = ImportManager(app, books)
        generator = AudioGenerationManager(app)
    }
}

class GhBookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        createChannels()
        AppGraph.init(this)
        AppGraph.importer.resumeInterrupted()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Processamento de livros e áudio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progresso da importação de PDFs e da geração de áudio offline"
            },
        )
    }

    companion object {
        const val CHANNEL_WORK = "work"
    }
}
