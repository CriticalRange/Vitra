package com.vitra.debug;

import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Advanced debugging utilities for Vitra renderers
 * Uses VitraNativeRenderer for DirectX 11 Debug Layer access via ID3D11InfoQueue
 */
public class VitraDebugUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraDebugUtils.class);

    // Debug state
    private static final AtomicBoolean debugInitialized = new AtomicBoolean(false);

    // Debug message queue for threading
    private static final ConcurrentLinkedQueue<DebugMessage> debugMessageQueue = new ConcurrentLinkedQueue<>();

    // Debug logging
    private static FileWriter debugLogFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // Debug configuration
    private static boolean debugEnabled = false;
    private static boolean verboseLogging = false;

    /**
     * Initialize debugging system with configuration
     * Note: DirectX debug layer is initialized in VitraD3D11Renderer.initializeDirectX()
     * This method only sets up Java-side logging and message processing
     */
    public static synchronized void initializeDebug(boolean enabled, boolean verbose) {
        if (debugInitialized.get()) {
            LOGGER.warn("Debug system already initialized");
            return;
        }

        debugEnabled = enabled;
        verboseLogging = verbose;

        if (!debugEnabled) {
            LOGGER.info("Debug system disabled by configuration");
            debugInitialized.set(true);
            return;
        }

        try {
            // Create debug logs directory
            Path debugDir = Paths.get("debug", "logs");
            Files.createDirectories(debugDir);

            // Initialize debug log file with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            Path logFile = debugDir.resolve("vitra_debug_" + timestamp + ".log");
            debugLogFile = new FileWriter(logFile.toFile(), true);

            logInfo("=== Vitra Debug System Initializing ===");
            logInfo("Configuration: enabled=" + enabled + ", verbose=" + verbose);
            logInfo("Note: DirectX debug layer initialized via VitraD3D11Renderer.initializeDirectX()");

            // Query device information
            try {
                String deviceInfo = VitraD3D11Renderer.nativeGetDeviceInfo();
                if (deviceInfo != null && !deviceInfo.isEmpty()) {
                    logInfo("Device Info: " + deviceInfo);
                }
            } catch (UnsatisfiedLinkError e) {
                logWarn("Could not retrieve device info - native methods not available");
            }

            logInfo("=== Debug System Initialization Complete ===");
            debugInitialized.set(true);

        } catch (IOException e) {
            LOGGER.error("Failed to initialize debug system", e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error during debug initialization", e);
        }
    }

    /**
     * Shutdown debug system safely
     */
    public static synchronized void shutdownDebug() {
        if (!debugInitialized.get()) {
            return;
        }

        try {
            logInfo("=== Shutting Down Debug System ===");

            // Process remaining debug messages
            processDebugMessages();

            // Close debug log file
            if (debugLogFile != null) {
                debugLogFile.write("=== Debug System Shutdown at " + new Date() + " ===\n");
                debugLogFile.close();
                debugLogFile = null;
            }

            debugInitialized.set(false);
            logInfo("✓ Debug system shutdown complete");

        } catch (IOException e) {
            LOGGER.error("Error closing debug log file", e);
        } catch (Exception e) {
            LOGGER.error("Error during debug shutdown", e);
        }
    }

    /**
     * Process queued debug messages from native code
     * Uses VitraNativeRenderer to access DirectX 11 ID3D11InfoQueue
     */
    public static void processDebugMessages() {
        if (!debugEnabled) {
            return;
        }

        try {
            // CRITICAL: Call native processDebugMessages to write dx11_native_*.log
            // This processes ID3D11InfoQueue messages and writes them to native log file
            VitraD3D11Renderer.nativeProcessDebugMessages();

            // Also get messages from native DirectX debug layer for Java-side logging
            String nativeMessages = VitraD3D11Renderer.nativeGetDebugMessages();
            if (nativeMessages != null && !nativeMessages.isEmpty()) {
                String[] messages = nativeMessages.split("\n");
                for (String message : messages) {
                    if (!message.trim().isEmpty()) {
                        queueDebugMessage(message.trim());
                    }
                }

                // Clear native message queue after retrieval
                VitraD3D11Renderer.nativeClearDebugMessages();
            }

            // Process queued messages
            while (!debugMessageQueue.isEmpty()) {
                DebugMessage message = debugMessageQueue.poll();
                if (message != null) {
                    logDebugMessage(message);
                }
            }

        } catch (UnsatisfiedLinkError e) {
            // Native methods not available - silently skip
        } catch (Exception e) {
            LOGGER.error("Error processing debug messages", e);
        }
    }

    /**
     * Queue a debug message for processing
     */
    public static void queueDebugMessage(String message) {
        debugMessageQueue.offer(new DebugMessage(message, System.currentTimeMillis()));
    }

    /**
     * Log a debug message to file and console
     */
    private static void logDebugMessage(DebugMessage message) {
        try {
            String formattedMessage = String.format("[%s] %s",
                dateFormat.format(new Date(message.timestamp)),
                message.message);

            // Write to debug log file
            if (debugLogFile != null) {
                debugLogFile.write(formattedMessage + "\n");
                debugLogFile.flush();
            }

            // Also log to SLF4J if verbose or important
            if (verboseLogging || message.message.contains("ERROR") || message.message.contains("WARNING")) {
                if (message.message.contains("ERROR")) {
                    LOGGER.error("[NATIVE] " + message.message);
                } else if (message.message.contains("WARNING")) {
                    LOGGER.warn("[NATIVE] " + message.message);
                } else {
                    LOGGER.info("[NATIVE] " + message.message);
                }
            }

        } catch (IOException e) {
            LOGGER.error("Failed to write debug message to log", e);
        }
    }

    /**
     * Log info message
     */
    private static void logInfo(String message) {
        LOGGER.info(message);
        if (debugLogFile != null) {
            try {
                debugLogFile.write("[" + dateFormat.format(new Date()) + "] [INFO] " + message + "\n");
                debugLogFile.flush();
            } catch (IOException e) {
                LOGGER.error("Failed to write to debug log", e);
            }
        }
    }

    /**
     * Log warning message
     */
    private static void logWarn(String message) {
        LOGGER.warn(message);
        if (debugLogFile != null) {
            try {
                debugLogFile.write("[" + dateFormat.format(new Date()) + "] [WARN] " + message + "\n");
                debugLogFile.flush();
            } catch (IOException e) {
                LOGGER.error("Failed to write to debug log", e);
            }
        }
    }

    /**
     * Log error message
     */
    private static void logError(String message) {
        LOGGER.error(message);
        if (debugLogFile != null) {
            try {
                debugLogFile.write("[" + dateFormat.format(new Date()) + "] [ERROR] " + message + "\n");
                debugLogFile.flush();
            } catch (IOException e) {
                LOGGER.error("Failed to write to debug log", e);
            }
        }
    }

    /**
     * Check if debug system is initialized
     */
    public static boolean isDebugInitialized() {
        return debugInitialized.get();
    }

    /**
     * Get debug statistics
     */
    public static String getDebugStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== Vitra Debug System Status ===\n");
        stats.append("Debug Enabled: ").append(debugEnabled).append("\n");
        stats.append("Verbose Logging: ").append(verboseLogging).append("\n");
        stats.append("Debug Initialized: ").append(debugInitialized.get()).append("\n");
        stats.append("Queued Messages: ").append(debugMessageQueue.size()).append("\n");

        // Query native device info if available
        try {
            String deviceInfo = VitraD3D11Renderer.nativeGetDeviceInfo();
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                stats.append("Device Info: ").append(deviceInfo).append("\n");
            }
        } catch (UnsatisfiedLinkError e) {
            stats.append("Native Debug Methods: Not Available\n");
        }

        return stats.toString();
    }

    /**
     * Simple debug message container
     */
    private static class DebugMessage {
        final String message;
        final long timestamp;

        DebugMessage(String message, long timestamp) {
            this.message = message;
            this.timestamp = timestamp;
        }
    }
}