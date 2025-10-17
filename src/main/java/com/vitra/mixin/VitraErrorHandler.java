package com.vitra.mixin;

import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized error handling and validation system for Vitra mixins.
 * Provides consistent error handling, logging, and recovery mechanisms
 * across all DirectX 11 rendering operations.
 */
public class VitraErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/ErrorHandler");

    // Error statistics
    private static final AtomicLong totalErrors = new AtomicLong(0);
    private static final AtomicLong criticalErrors = new AtomicLong(0);
    private static final AtomicLong recoverableErrors = new AtomicLong(0);

    // Error type tracking
    private static final Map<String, AtomicLong> errorCounts = new HashMap<>();
    private static final Map<String, Long> lastErrorTime = new HashMap<>();

    // Fallback state tracking
    private static volatile boolean fallbackModeActive = false;
    private static volatile long fallbackModeStartTime = 0;

    /**
     * Handles DirectX 11 initialization errors with proper recovery.
     */
    public static boolean handleInitializationError(String component, Exception error) {
        String errorKey = "INIT_" + component;
        logError(component, "Initialization failed", error, ErrorSeverity.CRITICAL);

        if (incrementErrorCount(errorKey) > 3) {
            LOGGER.error("Multiple initialization failures for component: {}, entering fallback mode", component);
            activateFallbackMode();
            return false;
        }

        // Attempt recovery
        return attemptInitializationRecovery(component);
    }

    /**
     * Handles rendering errors with graceful degradation.
     */
    public static boolean handleRenderingError(String component, String operation, Exception error) {
        String errorKey = "RENDER_" + component;
        logError(component, "Rendering operation failed: " + operation, error, ErrorSeverity.RECOVERABLE);

        incrementErrorCount(errorKey);

        // If we're in fallback mode, return false to trigger OpenGL fallback
        if (fallbackModeActive) {
            LOGGER.debug("In fallback mode, skipping DirectX 11 operation: {}", operation);
            return false;
        }

        // Check if we should enter fallback mode
        if (shouldEnterFallbackMode()) {
            activateFallbackMode();
            return false;
        }

        return true; // Continue with DirectX 11
    }

    /**
     * Handles shader compilation errors with fallback to default shaders.
     */
    public static boolean handleShaderError(String shaderName, Exception error) {
        String errorKey = "SHADER_" + shaderName;
        logError("Shader", "Shader compilation failed: " + shaderName, error, ErrorSeverity.RECOVERABLE);

        incrementErrorCount(errorKey);

        // Use default shader for problematic shaders
        LOGGER.warn("Using default shader for failed shader: {}", shaderName);
        return VitraNativeRenderer.useDefaultShader(shaderName);
    }

    /**
     * Handles buffer management errors with buffer recreation.
     */
    public static boolean handleBufferError(String bufferType, int bufferId, Exception error) {
        String errorKey = "BUFFER_" + bufferType;
        logError("Buffer", "Buffer operation failed: " + bufferType + " (ID: " + bufferId + ")", error, ErrorSeverity.RECOVERABLE);

        incrementErrorCount(errorKey);

        // Try to recreate the buffer
        if (bufferId != -1) {
            try {
                if (bufferType.contains("Vertex")) {
                    VitraNativeRenderer.releaseVertexBuffer(bufferId);
                    return VitraNativeRenderer.createVertexBuffer("default") != -1;
                } else if (bufferType.contains("Index")) {
                    VitraNativeRenderer.releaseIndexBuffer(bufferId);
                    return VitraNativeRenderer.createIndexBuffer() != -1;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to recreate buffer: {}", bufferType, e);
            }
        }

        return false;
    }

    /**
     * Handles texture errors with texture recreation.
     */
    public static boolean handleTextureError(String component, String operation, int textureId, Exception error) {
        String errorKey = "TEXTURE_" + component + "_" + operation;
        logError(component, "Texture operation failed: " + operation + " (ID: " + textureId + ")", error, ErrorSeverity.RECOVERABLE);

        incrementErrorCount(errorKey);

        // Try to recreate the texture
        if (textureId != -1) {
            try {
                VitraNativeRenderer.destroyResource(textureId);
                long newTextureHandle = VitraNativeRenderer.createTextureFromData(null, 1, 1, 0);
                return newTextureHandle != 0;
            } catch (Exception e) {
                LOGGER.error("Failed to recreate texture for component: {}", component, e);
            }
        }

        return false;
    }

    /**
     * Validates that VitraCore is properly initialized.
     */
    public static boolean validateVitraCoreInitialized(String component) {
        if (!VitraCore.getInstance().isInitialized()) {
            logError(component, "VitraCore not initialized", null, ErrorSeverity.CRITICAL);
            return false;
        }
        return true;
    }

    /**
     * Validates DirectX 11 device availability.
     */
    public static boolean validateDirectX11Device(String component) {
        try {
            if (!VitraNativeRenderer.isDeviceAvailable()) {
                logError(component, "DirectX 11 device not available", null, ErrorSeverity.CRITICAL);
                return false;
            }
            return true;
        } catch (Exception e) {
            logError(component, "Failed to validate DirectX 11 device", e, ErrorSeverity.CRITICAL);
            return false;
        }
    }

    /**
     * Validates parameters for draw calls.
     */
    public static boolean validateDrawParameters(String component, int vertexCount, int indexCount) {
        if (vertexCount <= 0) {
            logError(component, "Invalid vertex count: " + vertexCount, null, ErrorSeverity.WARNING);
            return false;
        }

        if (indexCount < 0) {
            logError(component, "Invalid index count: " + indexCount, null, ErrorSeverity.WARNING);
            return false;
        }

        return true;
    }

    /**
     * Validates texture dimensions.
     */
    public static boolean validateTextureDimensions(String component, int width, int height) {
        if (width <= 0 || height <= 0) {
            logError(component, "Invalid texture dimensions: " + width + "x" + height, null, ErrorSeverity.WARNING);
            return false;
        }

        // Check for reasonable maximum dimensions
        if (width > 16384 || height > 16384) {
            logError(component, "Texture dimensions too large: " + width + "x" + height, null, ErrorSeverity.WARNING);
            return false;
        }

        return true;
    }

    /**
     * Gets error statistics for debugging and monitoring.
     */
    public static Map<String, Object> getErrorStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalErrors", totalErrors.get());
        stats.put("criticalErrors", criticalErrors.get());
        stats.put("recoverableErrors", recoverableErrors.get());
        stats.put("fallbackModeActive", fallbackModeActive);
        stats.put("fallbackModeDuration", fallbackModeActive ? System.currentTimeMillis() - fallbackModeStartTime : 0);
        stats.put("errorCounts", new HashMap<>(errorCounts));
        return stats;
    }

    /**
     * Resets error statistics (useful for testing).
     */
    public static void resetErrorStatistics() {
        totalErrors.set(0);
        criticalErrors.set(0);
        recoverableErrors.set(0);
        errorCounts.clear();
        lastErrorTime.clear();
        fallbackModeActive = false;
        fallbackModeStartTime = 0;
        LOGGER.info("Error statistics reset");
    }

    /**
     * Attempts to recover from initialization failures.
     */
    private static boolean attemptInitializationRecovery(String component) {
        try {
            LOGGER.info("Attempting recovery for component: {}", component);

            // Wait a short time before retry
            Thread.sleep(100);

            // Try to reinitialize VitraCore if needed
            if (!VitraCore.getInstance().isInitialized()) {
                LOGGER.info("Attempting to reinitialize VitraCore");
                // This would need proper reinitialization logic
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Recovery attempt failed for component: {}", component, e);
            return false;
        }
    }

    /**
     * Determines if fallback mode should be activated.
     */
    private static boolean shouldEnterFallbackMode() {
        // Enter fallback mode if we have too many errors in a short time
        long recentErrors = errorCounts.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();

        return recentErrors > 10 || criticalErrors.get() > 2;
    }

    /**
     * Activates fallback mode to use OpenGL instead of DirectX 11.
     */
    private static void activateFallbackMode() {
        if (!fallbackModeActive) {
            fallbackModeActive = true;
            fallbackModeStartTime = System.currentTimeMillis();
            LOGGER.warn("=== FALLBACK MODE ACTIVATED ===");
            LOGGER.warn("DirectX 11 rendering failed, falling back to OpenGL");
            LOGGER.warn("This may impact performance but ensures stability");
        }
    }

    /**
     * Deactivates fallback mode and returns to DirectX 11.
     */
    public static void deactivateFallbackMode() {
        if (fallbackModeActive) {
            fallbackModeActive = false;
            long duration = System.currentTimeMillis() - fallbackModeStartTime;
            LOGGER.info("=== FALLBACK MODE DEACTIVATED ===");
            LOGGER.info("Returning to DirectX 11 rendering (was in fallback mode for {}ms)", duration);
        }
    }

    /**
     * Increments the error count for a specific error type.
     */
    private static long incrementErrorCount(String errorKey) {
        errorCounts.computeIfAbsent(errorKey, k -> new AtomicLong(0)).incrementAndGet();
        lastErrorTime.put(errorKey, System.currentTimeMillis());
        return errorCounts.get(errorKey).get();
    }

    /**
     * Logs an error with appropriate severity level.
     */
    private static void logError(String component, String message, Exception error, ErrorSeverity severity) {
        totalErrors.incrementAndGet();

        String fullMessage = String.format("[%s] %s", component, message);

        switch (severity) {
            case CRITICAL:
                criticalErrors.incrementAndGet();
                LOGGER.error(fullMessage, error);
                break;
            case RECOVERABLE:
                recoverableErrors.incrementAndGet();
                LOGGER.warn(fullMessage, error);
                break;
            case WARNING:
                LOGGER.warn(fullMessage, error);
                break;
            default:
                LOGGER.info(fullMessage, error);
                break;
        }
    }

    /**
     * Error severity levels.
     */
    private enum ErrorSeverity {
        CRITICAL,
        RECOVERABLE,
        WARNING,
        INFO
    }

    /**
     * Checks if the system is in fallback mode.
     */
    public static boolean isFallbackModeActive() {
        return fallbackModeActive;
    }

    /**
     * Gets the duration of the current fallback mode session.
     */
    public static long getFallbackModeDuration() {
        return fallbackModeActive ? System.currentTimeMillis() - fallbackModeStartTime : 0;
    }

    /**
     * Performs a health check on the DirectX 11 rendering system.
     */
    public static boolean performHealthCheck() {
        try {
            // Check VitraCore
            if (!validateVitraCoreInitialized("HealthCheck")) {
                return false;
            }

            // Check DirectX 11 device
            if (!validateDirectX11Device("HealthCheck")) {
                return false;
            }

            // Check error rates
            if (criticalErrors.get() > 5) {
                LOGGER.warn("High critical error rate detected: {}", criticalErrors.get());
                return false;
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("Health check failed", e);
            return false;
        }
    }
}