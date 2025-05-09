package com.example.aiassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * جسر لمعالجة محتوى الويب والاستعلامات عبر الإنترنت
 * يستخدم Python لمعالجة المحتوى من خلال Trafilatura
 */
class WebProcessorBridge(private val context: Context) {
    companion object {
        private const val TAG = "WebProcessorBridge"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android SDK built for x86) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
        
        // قائمة بمحركات البحث المختلفة للتنويع
        private val SEARCH_ENGINES = listOf(
            "https://www.google.com/search?q=",
            "https://www.bing.com/search?q=",
            "https://search.yahoo.com/search?p="
        )
    }
    
    /**
     * الحصول على محتوى معالج من الويب بناءً على استعلام
     * @param query الاستعلام المراد البحث عنه
     * @return النص المستخرج من صفحات الويب ذات الصلة
     */
    suspend fun getProcessedWebContent(query: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting web content for query: $query")
            
            // البحث عن الروابط ذات الصلة
            val searchResults = performSearch(query)
            
            // استخراج المحتوى من الروابط
            val contentBuilder = StringBuilder()
            
            var processedCount = 0
            for (url in searchResults) {
                try {
                    val content = extractContentFromUrl(url)
                    if (content.isNotEmpty()) {
                        contentBuilder.append("=== المصدر: $url ===\n")
                        contentBuilder.append(content)
                        contentBuilder.append("\n\n")
                        
                        processedCount++
                        if (processedCount >= 3) break // نكتفي بثلاثة نتائج لتجنب الحجم الكبير
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting content from URL: $url", e)
                    // نتابع مع الرابط التالي
                }
            }
            
            if (contentBuilder.isEmpty()) {
                return@withContext "لم أتمكن من العثور على معلومات كافية حول \"$query\". حاول استخدام كلمات بحث مختلفة."
            }
            
            contentBuilder.insert(0, "معلومات حول \"$query\":\n\n")
            return@withContext contentBuilder.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in getProcessedWebContent", e)
            return@withContext "حدث خطأ أثناء البحث عن المعلومات: ${e.message}"
        }
    }
    
    /**
     * البحث عن روابط ذات صلة بالاستعلام
     * @param query الاستعلام المراد البحث عنه
     * @return قائمة بالروابط ذات الصلة
     */
    private suspend fun performSearch(query: String): List<String> = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        
        try {
            // استخدام أحد محركات البحث بشكل عشوائي للتنويع
            val searchEngine = SEARCH_ENGINES.random()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = searchEngine + encodedQuery
            
            val connection = URL(searchUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                
                // استخراج الروابط من صفحة نتائج البحث
                val linkPattern = "<a\\s+(?:[^>]*?\\s+)?href=([\"'])(https?://(?!(?:www\\.)?google\\.).*?)\\1"
                val matches = Regex(linkPattern).findAll(content)
                
                matches.forEach { matchResult ->
                    val url = matchResult.groups[2]?.value
                    if (url != null && !url.contains("google") && !url.contains("bing") && !url.contains("yahoo")) {
                        urls.add(url)
                    }
                }
            }
            
            // إذا لم نجد روابط كافية، نضيف بعض المواقع المعروفة
            if (urls.size < 3) {
                val knownSites = listOf(
                    "https://ar.wikipedia.org/wiki/" + URLEncoder.encode(query, "UTF-8"),
                    "https://www.bbc.com/arabic/search?q=" + URLEncoder.encode(query, "UTF-8"),
                    "https://www.aljazeera.net/search/" + URLEncoder.encode(query, "UTF-8")
                )
                urls.addAll(knownSites)
            }
            
            return@withContext urls.take(5) // نأخذ أول خمسة روابط فقط
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in performSearch", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * استخراج المحتوى من رابط معين
     * @param url الرابط المراد استخراج المحتوى منه
     * @return المحتوى المستخرج
     */
    private suspend fun extractContentFromUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            // استخدام Trafilatura عبر Python لاستخراج النص
            // في التطبيق الحقيقي، يمكن استخدام ProcessBuilder لتشغيل Python مع Trafilatura
            // للتبسيط، نستخدم طريقة أبسط هنا
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                
                // استخراج النص من HTML
                return@withContext extractTextFromHtml(content)
            }
            
            return@withContext ""
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in extractContentFromUrl", e)
            return@withContext ""
        }
    }
    
    /**
     * استخراج النص من محتوى HTML
     * @param html محتوى HTML
     * @return النص المستخرج
     */
    private fun extractTextFromHtml(html: String): String {
        try {
            // استخراج بسيط للنص من HTML
            // في التطبيق الحقيقي، يمكن استخدام مكتبات متخصصة أو Trafilatura
            
            // إزالة علامات HTML
            var text = html.replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            text = text.replace(Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            text = text.replace(Regex("<.*?>"), "")
            
            // استبدال الرموز الخاصة
            text = text.replace("&nbsp;", " ")
            text = text.replace("&amp;", "&")
            text = text.replace("&lt;", "<")
            text = text.replace("&gt;", ">")
            text = text.replace("&quot;", "\"")
            
            // إزالة الأسطر الفارغة المتكررة
            text = text.replace(Regex("\n\\s*\n"), "\n")
            
            // اختيار المقاطع الأطول (التي قد تكون المحتوى الرئيسي)
            val paragraphs = text.split("\n").filter { it.trim().isNotEmpty() }
            val significantParagraphs = paragraphs.filter { it.length > 100 }
            
            return if (significantParagraphs.isNotEmpty()) {
                significantParagraphs.take(10).joinToString("\n\n")
            } else {
                paragraphs.take(20).joinToString("\n\n")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in extractTextFromHtml", e)
            return ""
        }
    }
    
    /**
     * الحصول على معلومات الطقس لموقع معين
     * @param location الموقع المراد معرفة حالة الطقس فيه
     * @return معلومات الطقس
     */
    suspend fun getWeatherInfo(location: String): String = withContext(Dispatchers.IO) {
        try {
            // في التطبيق الحقيقي، يجب استخدام API رسمي للطقس
            // هذا مجرد محاكاة بسيطة
            
            val encodedLocation = URLEncoder.encode(location, "UTF-8")
            val weatherUrl = "https://www.google.com/search?q=weather+in+$encodedLocation"
            
            val connection = URL(weatherUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                
                // يمكن تحسين طريقة استخراج بيانات الطقس هنا
                val tempPattern = Regex("(?i)([0-9]+)\\s*°(C|F)?")
                val conditionPattern = Regex("(?i)(sunny|cloudy|rainy|clear|partly cloudy|overcast|thunderstorm|snow)")
                
                val tempMatch = tempPattern.find(content)
                val conditionMatch = conditionPattern.find(content)
                
                val temperature = tempMatch?.groups?.get(1)?.value ?: "غير معروف"
                val temperatureUnit = if (tempMatch?.groups?.get(2)?.value == "F") "فهرنهايت" else "مئوية"
                
                val condition = when (conditionMatch?.groups?.get(1)?.value?.toLowerCase()) {
                    "sunny" -> "مشمس"
                    "cloudy" -> "غائم"
                    "rainy" -> "ممطر"
                    "clear" -> "صافٍ"
                    "partly cloudy" -> "غائم جزئياً"
                    "overcast" -> "ملبد بالغيوم"
                    "thunderstorm" -> "عاصف ورعدي"
                    "snow" -> "ثلجي"
                    else -> "غير معروف"
                }
                
                return@withContext "حالة الطقس في $location: $condition، درجة الحرارة $temperature درجة $temperatureUnit"
            }
            
            return@withContext "لم أتمكن من الحصول على معلومات الطقس لـ $location. يرجى المحاولة مرة أخرى لاحقاً."
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in getWeatherInfo", e)
            return@withContext "حدث خطأ أثناء محاولة الحصول على معلومات الطقس: ${e.message}"
        }
    }
    
    /**
     * الحصول على عناوين الأخبار
     * @param category فئة الأخبار (اختياري)
     * @return عناوين الأخبار
     */
    suspend fun getNewsHeadlines(category: String = ""): String = withContext(Dispatchers.IO) {
        try {
            // في التطبيق الحقيقي، يجب استخدام API رسمي للأخبار
            // هذا مجرد محاكاة بسيطة
            
            var newsUrl = "https://www.bbc.com/arabic"
            
            if (category.isNotEmpty()) {
                // تصنيفات الأخبار الشائعة
                newsUrl = when (category.toLowerCase()) {
                    "سياسة", "politics" -> "https://www.bbc.com/arabic/topics/c2dwqd1zr92t"
                    "اقتصاد", "economy" -> "https://www.bbc.com/arabic/topics/cqywj3ej3wxt"
                    "رياضة", "sports" -> "https://www.bbc.com/arabic/topics/c2dwqd3k7g7t"
                    "علوم", "تكنولوجيا", "science", "technology" -> "https://www.bbc.com/arabic/topics/c2dwqdn0jpjt"
                    "صحة", "health" -> "https://www.bbc.com/arabic/topics/c2dwqd8l7vjt"
                    else -> "https://www.bbc.com/arabic"
                }
            }
            
            val connection = URL(newsUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                
                // استخراج عناوين الأخبار
                val headlinePattern = Regex("<h3[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
                val headlineMatches = headlinePattern.findAll(content)
                
                val headlines = headlineMatches.mapNotNull { match ->
                    val headline = match.groups[1]?.value ?: ""
                    // تنظيف العنوان من أي علامات HTML متبقية
                    val cleanHeadline = headline.replace(Regex("<.*?>"), "").trim()
                    if (cleanHeadline.isNotEmpty() && cleanHeadline.length > 10) cleanHeadline else null
                }.distinct().take(10).toList()
                
                if (headlines.isEmpty()) {
                    return@withContext "لم أتمكن من الحصول على عناوين الأخبار. يرجى المحاولة مرة أخرى لاحقاً."
                }
                
                val categoryName = if (category.isNotEmpty()) category else "العامة"
                val response = StringBuilder("أبرز عناوين الأخبار $categoryName ليوم ${getCurrentDate()}:\n\n")
                
                headlines.forEachIndexed { index, headline ->
                    response.append("${index + 1}. $headline\n")
                }
                
                return@withContext response.toString()
            }
            
            return@withContext "لم أتمكن من الحصول على عناوين الأخبار. يرجى المحاولة مرة أخرى لاحقاً."
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in getNewsHeadlines", e)
            return@withContext "حدث خطأ أثناء محاولة الحصول على عناوين الأخبار: ${e.message}"
        }
    }
    
    /**
     * الحصول على التاريخ الحالي بتنسيق مناسب
     * @return التاريخ الحالي
     */
    private fun getCurrentDate(): String {
        val today = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return today.format(formatter)
    }
}