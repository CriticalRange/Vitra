package com.vitra.debug;

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
 * Supports DirectX 11 Debug Layer, InfoQueue, minidump generation, and RenderDoc integration
 */
public class VitraDebugUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraDebugUtils.class);

    // Debug state
    private static final AtomicBoolean debugInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean renderDocAvailable = new AtomicBoolean(false);
    private static final AtomicBoolean crashHandlerInstalled = new AtomicBoolean(false);

    // Debug message queue for threading
    private static final ConcurrentLinkedQueue<DebugMessage> debugMessageQueue = new ConcurrentLinkedQueue<>();

    // Debug logging
    private static FileWriter debugLogFile;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // Debug configuration
    private static boolean debugEnabled = false;
    private static boolean verboseLogging = false;
    private static boolean renderDocIntegration = true;
    private static boolean minidumpGeneration = true;

    // Native methods for debug functionality
    private static native boolean nativeInitializeDebugLayer();
    private static native void nativeShutdownDebugLayer();
    private static native boolean nativeInstallCrashHandler(String logPath);
    private static native void nativeUninstallCrashHandler();
    private static native boolean nativeInitializeRenderDoc();
    private static native void nativeTriggerDebugCapture();
    private static native String nativeGetDebugMessages();
    private static native void nativeClearDebugMessages();
    private static native void nativeSetDebugMessageCallback();

    /**
     * Initialize debugging system with configuration
     */
    public static synchronized void initializeDebug(boolean enabled, boolean verbose, boolean renderDoc, boolean minidumps) {
        if (debugInitialized.get()) {
            LOGGER.warn("Debug system already initialized");
            return;
        }

        debugEnabled = enabled;
        verboseLogging = verbose;
        renderDocIntegration = renderDoc;
        minidumpGeneration = minidumps;

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
            logInfo("Configuration: enabled=" + enabled + ", verbose=" + verbose +
                    ", renderDoc=" + renderDoc + ", minidumps=" + minidumps);

            // Initialize native debug layer
            if (nativeInitializeDebugLayer()) {
                logInfo("✓ Native debug layer initialized successfully");
            } else {
                logError("✗ Failed to initialize native debug layer");
            }

            // Install crash handler for minidump generation
            if (minidumpGeneration) {
                String crashLogPath = debugDir.resolve("crashes").toString();
                Files.createDirectories(Paths.get(crashLogPath));

                if (nativeInstallCrashHandler(crashLogPath)) {
                    crashHandlerInstalled.set(true);
                    logInfo("✓ Crash handler installed for minidump generation: " + crashLogPath);
                } else {
                    logError("✗ Failed to install crash handler");
                }
            }

            // Initialize RenderDoc integration
            if (renderDocIntegration) {
                if (nativeInitializeRenderDoc()) {
                    renderDocAvailable.set(true);
                    logInfo("✓ RenderDoc integration initialized");
                } else {
                    logWarn("⚠ RenderDoc not available - ensure RenderDoc is installed");
                }
            }

            // Set up debug message callback for piping to Java
            nativeSetDebugMessageCallback();

            logInfo("=== Debug System Initialization Complete ===");
            debugInitialized.set(true);

        } catch (IOException e) {
            LOGGER.error("Failed to initialize debug system", e);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Debug native library not available - debug features limited: " + e.getMessage());
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

            // Cleanup native components
            nativeShutdownDebugLayer();

            if (crashHandlerInstalled.get()) {
                nativeUninstallCrashHandler();
                logInfo("✓ Crash handler uninstalled");
            }

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
     * Trigger a frame capture for debugging
     */
    public static boolean triggerDebugCapture() {
        if (!debugEnabled || !renderDocAvailable.get()) {
            return false;
        }

        try {
            logInfo("🎥 Triggering debug frame capture...");
            nativeTriggerDebugCapture();
            logInfo("✓ Debug capture triggered");
            return true;
        } catch (Exception e) {
            logError("✗ Failed to trigger debug capture: " + e.getMessage());
            return false;
        }
    }

    /**
     * Process queued debug messages from native code
     */
    public static void processDebugMessages() {
        if (!debugEnabled) {
            return;
        }

        try {
            // Get messages from native code
            String nativeMessages = nativeGetDebugMessages();
            if (nativeMessages != null && !nativeMessages.isEmpty()) {
                String[] messages = nativeMessages.split("\n");
                for (String message : messages) {
                    if (!message.trim().isEmpty()) {
                        queueDebugMessage(message.trim());
                    }
                }
            }

            // Process queued messages
            while (!debugMessageQueue.isEmpty()) {
                DebugMessage message = debugMessageQueue.poll();
                if (message != null) {
                    logDebugMessage(message);
                }
            }

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
     * Check if RenderDoc is available
     */
    public static boolean isRenderDocAvailable() {
        return renderDocAvailable.get();
    }

    /**
     * Check if crash handler is installed
     */
    public static boolean isCrashHandlerInstalled() {
        return crashHandlerInstalled.get();
    }

    /**
     * Get debug statistics
     */
    public static String getDebugStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== Vitra Debug System Status ===\n");
        stats.append("Debug Enabled: ").append(debugEnabled).append("\n");
        stats.append("Verbose Logging: ").append(verboseLogging).append("\n");
        stats.append("RenderDoc Integration: ").append(renderDocIntegration).append("\n");
        stats.append("Minidump Generation: ").append(minidumpGeneration).append("\n");
        stats.append("Debug Initialized: ").append(debugInitialized.get()).append("\n");
        stats.append("RenderDoc Available: ").append(renderDocAvailable.get()).append("\n");
        stats.append("Crash Handler Installed: ").append(crashHandlerInstalled.get()).append("\n");
        stats.append("Queued Messages: ").append(debugMessageQueue.size()).append("\n");
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

    // Static initializer for debug native library
    static {
        try {
            System.loadLibrary("vitra-debug");
            LOGGER.info("Loaded vitra-debug native library");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("vitra-debug native library not available - some debug features will be limited");
        }
    }
}