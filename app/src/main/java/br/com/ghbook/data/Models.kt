package br.com.ghbook.data

import kotlinx.serialization.Serializable

@Serializable
enum class BookStatus { PROCESSING, READY, FAILED }

/** Metadados de um livro importado (books/<id>/meta.json). */
@Serializable
data class BookMeta(
    val id: String,
    val title: String,
    val author: String = "",
    val fileName: String,
    val fileSize: Long = 0,
    val sha256: String = "",
    val pageCount: Int = 0,
    val chapterCount: Int = 0,
    val totalChars: Long = 0,
    val ocrPages: List<Int> = emptyList(),
    val unreadablePages: List<Int> = emptyList(),
    val status: BookStatus = BookStatus.PROCESSING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
)

@Serializable
enum class ParagraphKind { TEXT, HEADING }

@Serializable
data class Paragraph(
    val text: String,
    /** Página de origem (1-based). */
    val page: Int,
    val kind: ParagraphKind = ParagraphKind.TEXT,
)

@Serializable
data class Chapter(
    val index: Int,
    val title: String,
    val paragraphs: List<Paragraph>,
)

/** Entrada do sumário (books/<id>/toc.json) — evita carregar todos os capítulos na memória. */
@Serializable
data class TocEntry(
    val index: Int,
    val title: String,
    val startPage: Int,
    val endPage: Int,
    val paragraphCount: Int,
    val charCount: Long,
)

@Serializable
data class ReadingProgress(
    val chapter: Int = 0,
    val paragraph: Int = 0,
    /** Posição dentro do arquivo de áudio gerado do capítulo, quando a última fonte foi áudio gerado. */
    val audioPositionMs: Long = 0,
    val lastMode: String = PlaybackMode.TTS.name,
    val updatedAt: Long = 0,
)

enum class PlaybackMode { TTS, AUDIO }

/** Estado do áudio gerado de um capítulo (books/<id>/audio/manifest.json). */
@Serializable
data class ChapterAudio(
    val chapter: Int,
    val file: String,
    val durationMs: Long,
    val bytes: Long,
    /** Instante (ms) em que cada parágrafo começa no arquivo — usado para sincronizar texto e áudio. */
    val paragraphStartMs: List<Long>,
    val voiceName: String = "",
)

@Serializable
data class AudioManifest(
    val chapters: List<ChapterAudio> = emptyList(),
    val voiceName: String = "",
    val lastError: String? = null,
    val updatedAt: Long = 0,
) {
    fun forChapter(index: Int) = chapters.firstOrNull { it.chapter == index }
    val totalBytes get() = chapters.sumOf { it.bytes }
}
