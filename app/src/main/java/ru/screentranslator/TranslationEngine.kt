package ru.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** Переведённый кусок текста и его место на экране. */
data class TranslatedBlock(val bounds: Rect, val lineCount: Int, val text: String)

/**
 * Распознавание текста и перевод полностью на телефоне (Google ML Kit).
 * Бесплатно, без ключей API и подписок. Интернет нужен только один раз — скачать модели.
 */
class TranslationEngine {

    // Китайская модель распознаёт и иероглифы, и латиницу (английский).
    private val recognizerLazy = lazy<TextRecognizer> {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val recognizer by recognizerLazy

    private val zhToRu: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    private val enToRu: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    suspend fun modelsDownloaded(): Boolean {
        val models = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
        val langs = models.map { it.language }.toSet()
        return TranslateLanguage.CHINESE in langs && TranslateLanguage.RUSSIAN in langs
    }

    suspend fun downloadModels() {
        val conditions = DownloadConditions.Builder().build() // любая сеть, не только Wi-Fi
        zhToRu.downloadModelIfNeeded(conditions).await()
        enToRu.downloadModelIfNeeded(conditions).await()
    }

    suspend fun translateScreen(bitmap: Bitmap): List<TranslatedBlock> = coroutineScope {
        val ocr = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        ocr.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val source = joinLines(block.lines.map { it.text })
            val translator = pickTranslator(source) ?: return@mapNotNull null
            async {
                val translated = runCatching { translator.translate(source).await() }.getOrNull()
                if (translated.isNullOrBlank()) null
                else TranslatedBlock(Rect(box), block.lines.size, translated)
            }
        }.awaitAll().filterNotNull()
    }

    /** Есть иероглифы → китайский; есть латиница → английский; иначе (русский, цифры) — не переводим. */
    private fun pickTranslator(text: String): Translator? {
        var han = 0
        var latin = 0
        var cyrillic = 0
        text.codePoints().forEach { cp ->
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> if (Character.isLetter(cp)) latin++
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                else -> {}
            }
        }
        return when {
            han > 0 -> zhToRu
            latin >= 2 && latin > cyrillic -> enToRu
            else -> null
        }
    }

    /** Склеивает строки абзаца: китайский — без пробелов, английский — через пробел. */
    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val last = sb[sb.length - 1]
            when {
                last == '-' && line[0].isLowerCase() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                isCjk(last) || isCjk(line[0]) -> sb.append(line)
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN ||
            c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

    fun close() {
        if (recognizerLazy.isInitialized()) recognizer.close()
        zhToRu.close()
        enToRu.close()
    }
}
