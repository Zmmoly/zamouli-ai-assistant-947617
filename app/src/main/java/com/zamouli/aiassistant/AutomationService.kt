package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility service for UI automation
 * This service allows simulating touches, gestures, and text input
 */
class AutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationService"
        
        // Flag for service connection status
        private val isServiceConnected = AtomicBoolean(false)
        
        // Pending operations (used for communication with the service)
        private val pendingGestureOperations = ConcurrentHashMap<String, GestureOperation>()
        private val pendingClickOperations = ConcurrentHashMap<String, ClickOperation>()
        private val pendingInputOperations = ConcurrentHashMap<String, InputOperation>()
        
        // Check if the service is connected
        fun isServiceConnected(): Boolean {
            return isServiceConnected.get()
        }
        
        // Add a pending gesture operation (tap or swipe)
        fun addPendingGestureOperation(
            id: String,
            type: GestureType,
            x1: Float,
            y1: Float,
            x2: Float = x1,
            y2: Float = y1
        ) {
            pendingGestureOperations[id] = GestureOperation(id, type, x1, y1, x2, y2)
        }
        
        // Add a pending click operation (click on a specific element)
        fun addPendingClickOperation(
            id: String,
            packageName: String,
            viewId: String,
            text: String = ""
        ) {
            pendingClickOperations[id] = ClickOperation(id, packageName, viewId, text)
        }
        
        // Add a pending input operation (type text in a specific element)
        fun addPendingInputOperation(
            id: String,
            packageName: String,
            viewId: String,
            text: String
        ) {
            pendingInputOperations[id] = InputOperation(id, packageName, viewId, text)
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected.set(true)
        Log.d(TAG, "Automation service connected")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected.set(false)
        Log.d(TAG, "Automation service destroyed")
    }
    
    override fun onInterrupt() {
        // Handle interruption of accessibility service
        Log.d(TAG, "Automation service interrupted")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Process any pending operations
            processPendingOperations()
        }
    }
    
    /**
     * Process any pending operations
     */
    private fun processPendingOperations() {
        // Process click operations
        processClickOperations()
        
        // Process input operations
        processInputOperations()
        
        // Process gesture operations
        processGestureOperations()
    }
    
    /**
     * Process pending click operations
     */
    private fun processClickOperations() {
        if (pendingClickOperations.isEmpty()) return
        
        val iterator = pendingClickOperations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val operation = entry.value
            
            try {
                var success = false
                
                // Get the root node
                val rootNode = rootInActiveWindow ?: continue
                
                // If we're looking for a specific package, verify we're in that package
                if (operation.packageName != "*" && rootNode.packageName != operation.packageName) {
                    continue
                }
                
                // Try to find the node by ID first
                if (operation.viewId.isNotEmpty()) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(operation.viewId)
                    if (nodes.isNotEmpty()) {
                        for (node in nodes) {
                            if (performClick(node)) {
                                success = true
                                break
                            }
                        }
                    }
                }
                
                // If we couldn't find by ID and we have text, try by text
                if (!success && operation.text.isNotEmpty()) {
                    val nodes = rootNode.findAccessibilityNodeInfosByText(operation.text)
                    if (nodes.isNotEmpty()) {
                        for (node in nodes) {
                            if (performClick(node)) {
                                success = true
                                break
                            }
                        }
                    }
                }
                
                // If we successfully clicked, remove this operation
                if (success) {
                    iterator.remove()
                }
                
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing click operation: ${e.message}")
            }
        }
    }
    
    /**
     * Process pending input operations
     */
    private fun processInputOperations() {
        if (pendingInputOperations.isEmpty()) return
        
        val iterator = pendingInputOperations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val operation = entry.value
            
            try {
                var success = false
                
                // Get the root node
                val rootNode = rootInActiveWindow ?: continue
                
                // If we're looking for a specific package, verify we're in that package
                if (operation.packageName != "*" && rootNode.packageName != operation.packageName) {
                    continue
                }
                
                // Try to find the node by ID first
                if (operation.viewId.isNotEmpty()) {
                    val nodes = rootNode.findAccessibilityNodeInfosByViewId(operation.viewId)
                    if (nodes.isNotEmpty()) {
                        for (node in nodes) {
                            if (setTextInNode(node, operation.text)) {
                                success = true
                                break
                            }
                        }
                    }
                }
                
                // If we couldn't find by ID, try to find any editable field
                if (!success) {
                    val editableNodes = findEditableNodes(rootNode)
                    for (node in editableNodes) {
                        if (setTextInNode(node, operation.text)) {
                            success = true
                            break
                        }
                    }
                }
                
                // If we successfully input, remove this operation
                if (success) {
                    iterator.remove()
                }
                
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing input operation: ${e.message}")
            }
        }
    }
    
    /**
     * Process pending gesture operations
     */
    private fun processGestureOperations() {
        if (pendingGestureOperations.isEmpty()) return
        
        val iterator = pendingGestureOperations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val operation = entry.value
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val gestureBuilder = GestureDescription.Builder()
                    val path = Path()
                    
                    // Set the start point
                    path.moveTo(operation.startX, operation.startY)
                    
                    if (operation.type == GestureType.SWIPE) {
                        // For swipe, add the end point
                        path.lineTo(operation.endX, operation.endY)
                        
                        // Create a stroke with the path
                        val strokeDescription = GestureDescription.StrokeDescription(
                            path, 0, 300 // Duration of the gesture in milliseconds
                        )
                        
                        // Add the stroke to the gesture
                        gestureBuilder.addStroke(strokeDescription)
                    } else {
                        // For tap, just add a stroke at the tap location
                        val strokeDescription = GestureDescription.StrokeDescription(
                            path, 0, 100 // Duration of the tap in milliseconds
                        )
                        
                        // Add the stroke to the gesture
                        gestureBuilder.addStroke(strokeDescription)
                    }
                    
                    // Dispatch the gesture
                    dispatchGesture(
                        gestureBuilder.build(),
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) {
                                super.onCompleted(gestureDescription)
                                // Gesture completed successfully
                                iterator.remove()
                            }
                            
                            override fun onCancelled(gestureDescription: GestureDescription) {
                                super.onCancelled(gestureDescription)
                                // Gesture was cancelled
                                Log.d(TAG, "Gesture was cancelled")
                            }
                        },
                        null // No handler, use default
                    )
                } else {
                    // For older Android versions, we can't use gestures
                    // Just remove the operation
                    Log.d(TAG, "Gesture not supported on this Android version")
                    iterator.remove()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing gesture operation: ${e.message}")
            }
        }
    }
    
    /**
     * Perform a click on a node
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // Try to find a clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        return result
                    }
                    val temp = parent.parent
                    parent.recycle()
                    parent = temp
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click: ${e.message}")
            false
        }
    }
    
    /**
     * Set text in an editable node
     */
    private fun setTextInNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (node.isEditable) {
                // For editable nodes, we can set the text
                val arguments = Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text: ${e.message}")
            false
        }
    }
    
    /**
     * Find all editable nodes in the hierarchy
     */
    private fun findEditableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodesRecursive(root, editableNodes)
        return editableNodes
    }
    
    /**
     * Recursively find all editable nodes
     */
    private fun findEditableNodesRecursive(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isEditable) {
            result.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNodesRecursive(child, result)
        }
    }
    
    /**
     * Launch an app by package name
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val packageManager = packageManager
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                Log.e(TAG, "No launch intent found for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: ${e.message}")
            false
        }
    }
    
    /**
     * Get info about a UI element at specific coordinates
     */
    fun getElementInfoAtCoordinates(x: Float, y: Float): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        try {
            val rootNode = rootInActiveWindow ?: return info
            
            // Find nodes at the given coordinates
            val nodes = findNodesAtCoordinates(rootNode, x.toInt(), y.toInt())
            
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                
                // Get node info
                info["packageName"] = node.packageName?.toString() ?: ""
                info["className"] = node.className?.toString() ?: ""
                info["text"] = node.text?.toString() ?: ""
                info["contentDescription"] = node.contentDescription?.toString() ?: ""
                info["viewId"] = node.viewIdResourceName ?: ""
                info["clickable"] = node.isClickable.toString()
                info["longClickable"] = node.isLongClickable.toString()
                info["editable"] = node.isEditable.toString()
                
                // Get bounds
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                info["bounds"] = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting element info: ${e.message}")
        }
        
        return info
    }
    
    /**
     * Find nodes that contain the given coordinates
     */
    private fun findNodesAtCoordinates(
        rootNode: AccessibilityNodeInfo,
        x: Int,
        y: Int
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesAtCoordinatesRecursive(rootNode, x, y, result)
        // Sort nodes by area (smallest first)
        return result.sortedBy { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            (bounds.width() * bounds.height())
        }
    }
    
    /**
     * Recursively find nodes that contain the given coordinates
     */
    private fun findNodesAtCoordinatesRecursive(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        if (bounds.contains(x, y)) {
            result.add(node)
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findNodesAtCoordinatesRecursive(child, x, y, result)
            }
        }
    }
    
    /**
     * Get the UI hierarchy as a string (for debugging)
     */
    fun getUIHierarchyAsString(): String {
        val rootNode = rootInActiveWindow ?: return "No active window"
        val builder = StringBuilder()
        getUIHierarchyRecursive(rootNode, builder, 0)
        rootNode.recycle()
        return builder.toString()
    }
    
    /**
     * Recursively build the UI hierarchy string
     */
    private fun getUIHierarchyRecursive(
        node: AccessibilityNodeInfo,
        builder: StringBuilder,
        depth: Int
    ) {
        val indent = "  ".repeat(depth)
        builder.append("$indent${node.className}")
        
        if (node.text != null) {
            builder.append(" (\"${node.text}\")")
        }
        
        if (node.contentDescription != null) {
            builder.append(" [${node.contentDescription}]")
        }
        
        if (node.viewIdResourceName != null) {
            builder.append(" - ID: ${node.viewIdResourceName}")
        }
        
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        builder.append(" Bounds: $bounds")
        
        builder.append(" Clickable: ${node.isClickable}")
        builder.append(" Editable: ${node.isEditable}")
        builder.append("\n")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            getUIHierarchyRecursive(child, builder, depth + 1)
        }
    }
}

/**
 * Types of gestures
 */
enum class GestureType {
    TAP,
    SWIPE
}

/**
 * Data class for gesture operations
 */
data class GestureOperation(
    val id: String,
    val type: GestureType,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

/**
 * Data class for click operations
 */
data class ClickOperation(
    val id: String,
    val packageName: String,
    val viewId: String,
    val text: String
)

/**
 * Data class for input operations
 */
data class InputOperation(
    val id: String,
    val packageName: String,
    val viewId: String,
    val text: String
)