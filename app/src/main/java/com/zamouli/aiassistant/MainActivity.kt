package com.example.aiassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var aiProcessor: AIProcessor
    private val messageList = mutableListOf<MessageItem>()
    
    // Speech components
    private var speechRecognizer: SpeechRecognizer? = null
    private var isSpeechEnabled = false

    // Permission request code
    private val INTERNET_PERMISSION_REQUEST_CODE = 1001
    
    // For screen capture
    private val SCREENSHOT_REQUEST_CODE = 2001
    
    // For speech recognition
    private val SPEECH_PERMISSION_REQUEST_CODE = 1002
    
    // UI Automator instance
    private lateinit var uiAutomator: UIAutomator
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check and request permissions
        checkAndRequestPermissions()

        // Initialize AI processor
        aiProcessor = AIProcessor(applicationContext)
        
        // Initialize UI Automator
        uiAutomator = UIAutomator(applicationContext)

        // Set up UI components
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        recyclerView = findViewById(R.id.recyclerView)

        // Set up RecyclerView with adapter
        chatAdapter = ChatAdapter(messageList)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Audio controls for voice analysis
        val micButton = findViewById<ImageButton>(R.id.micButton)
        
        // Set up mic button for voice tone analysis
        micButton.setOnClickListener {
            startSpeechRecognition()
        }
        
        // Handle long press on mic button for voice tone analysis
        micButton.setOnLongClickListener {
            Toast.makeText(
                this,
                "جاري تحليل نبرة صوتك...",
                Toast.LENGTH_SHORT
            ).show()
            
            // Send voice analysis command
            addMessage("حلل نبرة صوتي", true)
            processUserMessage("حلل نبرة صوتي")
            true
        }
        
        // Add welcome message
        addMessage("مرحباً بك في مساعد الذكاء الاصطناعي المتطور! الآن يمكنني التحكم الكامل في هاتفك بما في ذلك فتح التطبيقات، إجراء المكالمات، إرسال الرسائل، وتحليل نبرة صوتك. كيف يمكنني مساعدتك اليوم؟", false)

        // Set up send button click listener
        sendButton.setOnClickListener {
            sendMessage()
        }

        // Set up keyboard send action
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                return@setOnEditorActionListener true
            }
            false
        }
        
        // Check if advanced permissions are needed
        checkAdvancedPermissions()
    }
    
    /**
     * Check for advanced automation permissions
     */
    private fun checkAdvancedPermissions() {
        // Check if accessibility service is enabled
        if (!uiAutomator.isAutomationServiceConnected()) {
            // Show dialog to enable accessibility service
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_accessibility_service))
                .setMessage(getString(R.string.enable_accessibility_prompt))
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open accessibility settings
                    uiAutomator.openAccessibilitySettings()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check if notification listener service is enabled
        if (!NotificationListenerService.isServiceConnected()) {
            // Show dialog to enable notification listener service
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.no_notification_access))
                .setMessage(getString(R.string.enable_notification_prompt))
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open notification settings
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check for overlay permission
        if (!Settings.canDrawOverlays(this)) {
            // Show dialog to enable overlay permission
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required))
                .setMessage("يحتاج التطبيق إلى إذن العرض فوق التطبيقات الأخرى للتحكم الكامل في الهاتف")
                .setPositiveButton(getString(R.string.settings)) { _, _ ->
                    // Open overlay settings
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
        
        // Check for screen capture permission
        requestScreenCapturePermission()
    }
    
    /**
     * Request screen capture permission
     */
    private fun requestScreenCapturePermission() {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(intent, SCREENSHOT_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting screen capture: ${e.message}")
        }
    }
    
    /**
     * Handle activity result (for screen capture permission)
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == SCREENSHOT_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Store screen capture intent for later use
                ScreenCaptureService.screenCaptureIntent = data
                Log.d("MainActivity", "Screen capture permission granted")
            } else {
                Log.e("MainActivity", "Screen capture permission denied")
                Toast.makeText(
                    this,
                    "لم تتم الموافقة على إذن التقاط الشاشة. بعض الوظائف قد لا تعمل.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Check and request necessary permissions
     */
    /**
     * بدء التعرف على الكلام
     */
    private fun startSpeechRecognition() {
        try {
            // التحقق من وجود إذن الميكروفون
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // طلب إذن الميكروفون
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
                return
            }
            
            // بدء التعرف على الكلام
            speechRecognizer = SpeechRecognizer(this, object : SpeechRecognizer.SpeechRecognizerCallback {
                override fun onResult(text: String) {
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            messageInput.setText(text)
                        }
                    }
                }
                
                override fun onError(errorMessage: String) {
                    Log.e("MainActivity", "Speech recognition error: $errorMessage")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "حدث خطأ في التعرف على الكلام: $errorMessage",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
            
            speechRecognizer?.startListening()
            
            Toast.makeText(
                this,
                "تحدث الآن...",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting speech recognition", e)
            Toast.makeText(
                this,
                "فشل بدء التعرف على الكلام",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = ArrayList<String>()
        
        // Check for internet permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.INTERNET)
        }
        
        // Check for network state permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_NETWORK_STATE)
        }
        
        // Check for phone call permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        
        // Check for SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.SEND_SMS)
        }
        
        // Check for contacts permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }
        
        // Check for calendar permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALENDAR)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_CALENDAR)
        }
        
        // Check for storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        // Check for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        
        // Check for location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Request permissions if needed
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                INTERNET_PERMISSION_REQUEST_CODE
            )
        }
    }
    
    /**
     * Handle permission request results
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            INTERNET_PERMISSION_REQUEST_CODE -> {
                // Check if all permissions were granted
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // All permissions granted, continue with app initialization
                    Toast.makeText(
                        this,
                        "تم منح جميع الأذونات المطلوبة",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Permissions denied, show dialog explaining why permissions are needed
                    showPermissionDeniedDialog()
                }
                return
            }
        }
    }
    
    /**
     * Show dialog explaining why permissions are needed
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("أذونات مطلوبة")
            .setMessage("يحتاج التطبيق إلى مجموعة من الأذونات للتحكم الكامل في الهاتف مثل إجراء المكالمات، والرسائل النصية، والوصول إلى جهات الاتصال، والتقويم، والكاميرا، والموقع، وإعدادات النظام. يرجى منح الأذونات المطلوبة من إعدادات التطبيق.")
            .setPositiveButton("الإعدادات") { _, _ ->
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "لن تعمل وظائف التطبيق بشكل كامل بدون الأذونات المطلوبة",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isNotEmpty()) {
            // Add user message to the chat
            addMessage(message, true)
            
            // Clear input field
            messageInput.setText("")
            
            // Process message with AI
            processMessageWithAI(message)
        }
    }

    private fun processMessageWithAI(message: String) {
        // Use coroutine to process message in background
        
        // Initialize automation components if not already done
        initializeAutomationComponents()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = aiProcessor.processText(message)
                
                withContext(Dispatchers.Main) {
                    // Add AI response to the chat
                    addMessage(response, false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "خطأ في معالجة الرسالة: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun addMessage(message: String, isUser: Boolean) {
        messageList.add(MessageItem(message, isUser))
        chatAdapter.notifyItemInserted(messageList.size - 1)
        recyclerView.smoothScrollToPosition(messageList.size - 1)
    }

    /**
     * Initialize the UI Automator and other automation components
     */
    private fun initializeAutomationComponents() {
        // Check if the automation service and notification listener service are running
        if (!AutomationService.isServiceConnected()) {
            Toast.makeText(
                this,
                "خدمة الأتمتة غير مفعلة. سيتم تفعيلها لتوفير تحكم كامل في الهاتف",
                Toast.LENGTH_LONG
            ).show()
        }
        
        if (!NotificationListenerService.isServiceConnected()) {
            Toast.makeText(
                this,
                "خدمة الإشعارات غير مفعلة. سيتم تفعيلها للوصول إلى الإشعارات",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // Check if screen capture permission has been granted
        if (ScreenCaptureService.screenCaptureIntent == null) {
            Toast.makeText(
                this,
                "إذن التقاط الشاشة غير ممنوح. سيتم طلبه للتفاعل مع التطبيقات الأخرى",
                Toast.LENGTH_LONG
            ).show()
        }
        
        // Initialize speech components if they aren't already
        initializeSpeechComponents()
    }
    
    /**
     * Initialize speech recognition components
     */
    private fun initializeSpeechComponents() {
        try {
            // Check for required permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // Request the permission
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
            } else {
                // Permission already granted, enable speech
                isSpeechEnabled = true
            }
            
            Log.d("MainActivity", "Speech components initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing speech components: ${e.message}")
            isSpeechEnabled = false
        }
    }
    
    /**
     * Handle speech recognition result
     */
    private fun handleSpeechResult(text: String) {
        if (text.isNotEmpty()) {
            // Add the recognized text to the message input
            messageInput.setText(text)
            
            // Send the message
            sendMessage()
        }
    }
    
    /**
     * Start speech recognition
     */
    private fun startSpeechRecognition() {
        try {
            // التحقق من وجود إذن الميكروفون
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                
                // طلب إذن الميكروفون
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    SPEECH_PERMISSION_REQUEST_CODE
                )
                return
            }
            
            // بدء التعرف على الكلام
            speechRecognizer = SpeechRecognizer(this, object : SpeechRecognizer.SpeechRecognizerCallback {
                override fun onResult(text: String) {
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            messageInput.setText(text)
                        }
                    }
                }
                
                override fun onError(errorMessage: String) {
                    Log.e("MainActivity", "Speech recognition error: $errorMessage")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "حدث خطأ في التعرف على الكلام: $errorMessage",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
            
            speechRecognizer?.startListening()
            
            Toast.makeText(
                this,
                "تحدث الآن...",
                Toast.LENGTH_SHORT
            ).show()
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting speech recognition", e)
            Toast.makeText(
                this,
                "فشل بدء التعرف على الكلام",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * Speak the given text using text-to-speech
     */
    private fun speakText(text: String) {
        // TODO: يمكن إضافة وظيفة تحويل النص إلى كلام هنا في المستقبل
        Toast.makeText(
            this,
            "تفعيل الكلام: $text",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        aiProcessor.close()
        
        // Clean up speech resources
        speechRecognizer?.release()
    }
}
