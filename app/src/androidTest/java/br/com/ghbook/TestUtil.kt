package br.com.ghbook

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import br.com.ghbook.data.BookStatus
import br.com.ghbook.importer.ImportResult
import kotlinx.coroutines.runBlocking
import java.io.File

object TestUtil {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val app: Context get() = instrumentation.targetContext

    /** Copia um arquivo de samples/ (assets do teste) para o cache do app. */
    fun sample(name: String): File {
        val out = File(app.cacheDir, "test-$name")
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(150)
        }
        return condition()
    }

    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Importa um PDF de teste pelo mesmo fluxo da interface e espera terminar. Retorna o id. */
    fun importAndWait(name: String): String {
        AppGraph.init(app)
        // Remove importações anteriores do mesmo arquivo para o teste ser repetível.
        val file = sample(name)
        val result = runBlocking { AppGraph.importer.import(Uri.fromFile(file)) }
        val id = when (result) {
            is ImportResult.Started -> result.bookId
            is ImportResult.AlreadyImported -> result.bookId
            is ImportResult.Error -> throw AssertionError("Falha na importação: ${result.message}")
        }
        check(waitFor(120_000) { AppGraph.books.book(id)?.status != BookStatus.PROCESSING }) { "Importação não terminou" }
        return id
    }
}
