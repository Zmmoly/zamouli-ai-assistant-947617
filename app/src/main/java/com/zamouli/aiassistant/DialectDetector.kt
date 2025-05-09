package com.intelliai.assistant

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * كاشف اللهجات العربية
 * يستخدم نموذج TensorFlow Lite محلي (مجاني بالكامل) ويدعم عدة لهجات عربية
 * بما في ذلك اللهجة السودانية والمصرية والخليجية والشامية والمغربية
 */
class ArabicDialectDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "ArabicDialectDetector"
        private const val MODEL_FILE = "arabic_dialect_model.tflite"
        private const val MAX_TEXT_LENGTH = 256
        private const val NUM_DIALECTS = 5
    }
    
    private var interpreter: Interpreter? = null
    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val dialects = listOf("msaاللغة العربية الفصحى", "egyptianمصرية", "gulfخليجية", "levantineشامية", "maghrebiمغربية")
    private val dialectMap = mapOf(
        "msaاللغة العربية الفصحى" to "ar-MSA",
        "egyptianمصرية" to "ar-EG",
        "gulfخليجية" to "ar-GULF",
        "levantineشامية" to "ar-LEV",
        "maghrebiمغربية" to "ar-MAG",
        "sudaneseسودانية" to "ar-SD"
    )
    private val charMap = mutableMapOf<Char, Int>()
    
    init {
        // إنشاء خريطة الأحرف للترميز (حرف -> رقم)
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!?'-\"()[]{}:;/\\#+~*=^_@&%$<>|`أبتثجحخدذرزسشصضطظعغفقكلمنهويءإآةىئؤ".forEachIndexed { index, char ->
            charMap[char] = index + 1
        }
    }
    
    /**
     * تهيئة الكاشف وتحميل النموذج
     * 
     * @return true إذا تمت التهيئة بنجاح
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (interpreter != null) {
                return@withContext true
            }
            
            // تحميل النموذج من ملفات الأصول
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في تهيئة كاشف اللهجات: ${e.message}", e)
            false
        }
    }
    
    /**
     * اكتشاف ما إذا كان النص باللغة العربية
     * 
     * @param text النص المراد فحصه
     * @return true إذا كان النص باللغة العربية
     */
    suspend fun isArabicText(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // استخدام ML Kit للتعرف على اللغة
            val languageCode = languageIdentifier.identifyLanguage(text).await()
            return@withContext languageCode == "ar" || languageCode.startsWith("ar-")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في اكتشاف اللغة: ${e.message}", e)
            
            // طريقة احتياطية: التحقق من وجود أحرف عربية
            val arabicPattern = Regex("[\u0600-\u06FF]+")
            return@withContext arabicPattern.containsMatchIn(text)
        }
    }
    
    /**
     * اكتشاف اللهجة العربية للنص
     * 
     * @param text النص المراد تحليله
     * @return نتيجة اكتشاف اللهجة
     */
    suspend fun detectDialect(text: String): DialectDetectionResult = withContext(Dispatchers.Default) {
        // التحقق من أن النص عربي
        val isArabic = isArabicText(text)
        if (!isArabic) {
            return@withContext DialectDetectionResult(
                primaryDialect = "unknown",
                dialectCode = "unknown",
                confidences = dialects.associateWith { 0.0f },
                confidence = 0.0f
            )
        }
        
        // مع النصوص القصيرة جدًا، من الصعب تحديد اللهجة بدقة
        if (text.trim().length < 5) {
            return@withContext DialectDetectionResult(
                primaryDialect = "ar-MSA",
                dialectCode = "ar-MSA",
                confidences = dialects.associateWith { if (it == "msaاللغة العربية الفصحى") 0.6f else 0.1f },
                confidence = 0.6f
            )
        }
        
        // تهيئة الكاشف إذا لم يكن مهيأ بالفعل
        if (interpreter == null) {
            val initialized = initialize()
            if (!initialized) {
                return@withContext DialectDetectionResult(
                    primaryDialect = "error",
                    dialectCode = "error",
                    confidences = emptyMap(),
                    confidence = 0.0f
                )
            }
        }
        
        try {
            // تحضير البيانات للإدخال
            val inputBuffer = encodeText(text)
            
            // تهيئة مصفوفة الإخراج
            val outputBuffer = Array(1) { FloatArray(NUM_DIALECTS) }
            
            // تنفيذ النموذج
            interpreter?.run(inputBuffer, outputBuffer)
            
            // معالجة نتائج التصنيف
            val scores = outputBuffer[0]
            
            // البحث عن أعلى درجة
            var maxIndex = 0
            var maxScore = scores[0]
            
            for (i in 1 until NUM_DIALECTS) {
                if (scores[i] > maxScore) {
                    maxScore = scores[i]
                    maxIndex = i
                }
            }
            
            // ربط الدرجات بأسماء اللهجات
            val confidenceMap = dialects.mapIndexed { index, dialect ->
                dialect to scores.getOrElse(index) { 0.0f }
            }.toMap()
            
            // تحديد اللهجة الرئيسية
            val primaryDialect = dialects.getOrElse(maxIndex) { "unknown" }
            val dialectCode = dialectMap[primaryDialect] ?: "ar-unknown"
            
            DialectDetectionResult(
                primaryDialect = primaryDialect,
                dialectCode = dialectCode,
                confidences = confidenceMap,
                confidence = maxScore
            )
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في اكتشاف اللهجة: ${e.message}", e)
            
            // إعادة نتيجة افتراضية في حالة الخطأ
            DialectDetectionResult(
                primaryDialect = "error",
                dialectCode = "error",
                confidences = emptyMap(),
                confidence = 0.0f
            )
        }
    }
    
    /**
     * ترميز النص إلى مصفوفة أرقام للإدخال في النموذج
     * 
     * @param text النص المراد ترميزه
     * @return مخزن مؤقت يحتوي على النص المرمز
     */
    private fun encodeText(text: String): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MAX_TEXT_LENGTH * 4) // 4 بايت لكل رقم عائم
        buffer.order(ByteOrder.nativeOrder())
        
        // قص النص إذا كان طويلًا جدًا
        val processedText = text.take(MAX_TEXT_LENGTH)
        
        // ملء المخزن المؤقت بالقيم المرمزة
        for (char in processedText) {
            val value = charMap[char] ?: 0
            buffer.putFloat(value.toFloat())
        }
        
        // ملء باقي المخزن المؤقت بأصفار
        for (i in processedText.length until MAX_TEXT_LENGTH) {
            buffer.putFloat(0.0f)
        }
        
        // إعادة ضبط موضع القراءة
        buffer.rewind()
        
        return buffer
    }
    
    /**
     * إغلاق المفسر والمحرر
     */
    fun close() {
        try {
            interpreter?.close()
            interpreter = null
            languageIdentifier.close()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في إغلاق كاشف اللهجات: ${e.message}", e)
        }
    }
}

/**
 * نتيجة اكتشاف اللهجة العربية
 */
data class DialectDetectionResult(
    val primaryDialect: String,  // اللهجة الرئيسية المكتشفة
    val dialectCode: String,  // رمز اللهجة (مثل ar-EG، ar-GULF)
    val confidences: Map<String, Float>,  // درجات الثقة لكل لهجة
    val confidence: Float  // درجة الثقة في اللهجة الرئيسية
)