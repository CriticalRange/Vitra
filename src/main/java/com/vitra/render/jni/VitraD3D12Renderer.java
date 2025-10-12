package com.vitra.render.jni;

import com.vitra.config.VitraConfig;
import com.vitra.config.RendererType;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.debug.VitraDebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 12 Ultimate renderer interface via JNI with advanced debugging support
 * Supports Ray Tracing, Variable Rate Shading, Mesh Shaders, and other DirectX 12 Ultimate features
 *
 * Enhanced with:
 * - DirectX 12 Debug Layer and GPU-Based Validation
 * - PIX integration for performance profiling
 * - Advanced shader debugging and validation
 * - Safe JNI exception handling
 */
public class VitraD3D12Renderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitraD3D12Renderer.class);

    // Debug state
    private static boolean debugInitialized = false;

    // Device and context handles
    private static long nativeDevice = 0L;
    private static long nativeCommandQueue = 0L;
    private static long nativeSwapChain = 0L;
    private static long nativeDescriptorHeap = 0L;

    // Ray tracing state
    private static boolean rayTracingSupported = false;
    private static long rtScene = 0L;
    private static long rtPipeline = 0L;

    // Variable Rate Shading state
    private static boolean vrsSupported = false;
    private static int vrsTileSize = 8;

    // Mesh shader state
    private static boolean meshShadersSupported = false;

    
    // Native methods
    public static native boolean nativeInitializeDirectX12(long windowHandle, int width, int height, boolean debugMode);

    // Shutdown DirectX 12 Ultimate resources
    public static native void nativeShutdown();

    // Debug-specific native methods
    public static native boolean nativeInitializeDebugLayer();
    public static native void nativeSetDebugMessageSeverity(int severity);
    public static native String nativeGetDebugMessages();
    public static native void nativeBreakOnDebugError(boolean enabled);
    public static native boolean nativeValidatePipelineState(long pipeline);
    public static native boolean nativeBeginPIXCapture();
    public static native void nativeEndPIXCapture();

    // Frame management
    public static native void nativeBeginFrame();
    public static native void nativeEndFrame();
    public static native void nativePresent();

    // Resource management
    public static native void nativeResize(int width, int height);
    public static native void nativeClear(float r, float g, float b, float a);

    // DirectX 12 Ultimate feature queries
    public static native boolean nativeQueryRayTracingSupport();
    public static native boolean nativeQueryVariableRateShadingSupport();
    public static native boolean nativeQueryMeshShaderSupport();

    // Ray Tracing functionality
    public static native boolean nativeEnableRayTracing(int quality);
    public static native void nativeDisableRayTracing();
    public static native void nativeBuildAccelerationStructures();
    public static native void nativeUpdateRayTracingScene();

    // Variable Rate Shading functionality
    public static native boolean nativeEnableVariableRateShading(int tileSize);
    public static native void nativeDisableVariableRateShading();
    public static native void nativeSetVariableRateShadingMap(long vrsMap);

    // Mesh Shader functionality
    public static native boolean nativeEnableMeshShaders();
    public static native void nativeDisableMeshShaders();
    public static native void nativeDispatchMeshShaders(int groupCountX, int groupCountY, int groupCountZ);

    // Performance monitoring
    public static native float nativeGetGpuUtilization();
    public static native long nativeGetFrameTime();
    public static native int nativeGetDrawCallsPerFrame();
    public static native void nativeResetPerformanceCounters();

    // Debug utilities
    public static native void nativeSetDebugMode(boolean enabled);
    public static native void nativeEnableGpuValidation();
    public static native void nativeCaptureFrame(String filename);

    // State queries
    public static boolean isInitialized() {
        return nativeDevice != 0L && nativeCommandQueue != 0L && nativeSwapChain != 0L;
    }

    public static boolean isRayTracingSupported() {
        return rayTracingSupported;
    }

    public static boolean isVariableRateShadingSupported() {
        return vrsSupported;
    }

    public static boolean isMeshShadersSupported() {
        return meshShadersSupported;
    }

    // Public wrapper methods (for interface compatibility)
    public static boolean initializeDirectX12(long windowHandle, int width, int height, boolean debugMode) {
        return nativeInitializeDirectX12(windowHandle, width, height, debugMode);
    }

    public static void shutdown() {
        nativeShutdown();
    }

    public static void beginFrame() {
        nativeBeginFrame();
    }

    public static void endFrame() {
        nativeEndFrame();
    }

    public static void present() {
        nativePresent();
    }

    public static void resize(int width, int height) {
        nativeResize(width, height);
    }

    public static void clear(float r, float g, float b, float a) {
        nativeClear(r, g, b, a);
    }

    public static boolean queryRayTracingSupport() {
        return nativeQueryRayTracingSupport();
    }

    public static boolean queryVariableRateShadingSupport() {
        return nativeQueryVariableRateShadingSupport();
    }

    public static boolean queryMeshShaderSupport() {
        return nativeQueryMeshShaderSupport();
    }

    public static boolean enableRayTracing(int quality) {
        return nativeEnableRayTracing(quality);
    }

    public static void disableRayTracing() {
        nativeDisableRayTracing();
    }

    public static boolean enableVariableRateShading(int tileSize) {
        return nativeEnableVariableRateShading(tileSize);
    }

    public static void disableVariableRateShading() {
        nativeDisableVariableRateShading();
    }

    public static float getGpuUtilization() {
        return nativeGetGpuUtilization();
    }

    public static long getFrameTime() {
        return nativeGetFrameTime();
    }

    public static int getDrawCallsPerFrame() {
        return nativeGetDrawCallsPerFrame();
    }

    public static void resetPerformanceCounters() {
        nativeResetPerformanceCounters();
    }

    public static void setDebugMode(boolean enabled) {
        nativeSetDebugMode(enabled);
    }

    public static void captureFrame(String filename) {
        try {
            nativeCaptureFrame(filename);
            if (debugInitialized) {
                LOGGER.info("Frame captured to: {}", filename);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to capture frame: {}", e.getMessage());
            if (debugInitialized) {
                VitraDebugUtils.queueDebugMessage("CAPTURE_ERROR: " + e.getMessage());
            }
        }
    }

    // ==================== DEBUG INTEGRATION METHODS ====================

    /**
     * Process debug messages from DirectX 12 debug layer
     */
    public static void processDebugMessages() {
        if (!debugInitialized) {
            return;
        }

        try {
            String messages = nativeGetDebugMessages();
            if (messages != null && !messages.isEmpty()) {
                String[] messageArray = messages.split("\n");
                for (String message : messageArray) {
                    if (!message.trim().isEmpty()) {
                        VitraDebugUtils.queueDebugMessage("[D3D12] " + message.trim());
                    }
                }
            }

            // Process the queued messages
            VitraDebugUtils.processDebugMessages();

        } catch (Exception e) {
            LOGGER.error("Error processing DirectX 12 debug messages", e);
        }
    }

    /**
     * Begin PIX capture for performance profiling
     */
    public static boolean beginPIXCapture() {
        if (!debugInitialized) {
            LOGGER.warn("PIX capture requires debug mode to be enabled");
            return false;
        }

        try {
            LOGGER.info("Starting PIX capture...");
            boolean success = nativeBeginPIXCapture();
            if (success) {
                LOGGER.info("✓ PIX capture started");
            } else {
                LOGGER.error("✗ Failed to start PIX capture");
            }
            return success;
        } catch (Exception e) {
            LOGGER.error("Error starting PIX capture", e);
            VitraDebugUtils.queueDebugMessage("PIX_ERROR: " + e.getMessage());
            return false;
        }
    }

    /**
     * End PIX capture
     */
    public static void endPIXCapture() {
        if (!debugInitialized) {
            return;
        }

        try {
            nativeEndPIXCapture();
            LOGGER.info("✓ PIX capture ended");
        } catch (Exception e) {
            LOGGER.error("Error ending PIX capture", e);
            VitraDebugUtils.queueDebugMessage("PIX_ERROR: " + e.getMessage());
        }
    }

    /**
     * Validate a pipeline state object
     */
    public static boolean validatePipelineState(long pipeline) {
        if (!debugInitialized || pipeline == 0) {
            return false;
        }

        try {
            boolean isValid = nativeValidatePipelineState(pipeline);
            if (!isValid) {
                LOGGER.warn("Pipeline state validation failed for handle: 0x{}", Long.toHexString(pipeline));
                VitraDebugUtils.queueDebugMessage("VALIDATION_WARNING: Invalid pipeline state: 0x" + Long.toHexString(pipeline));
            }
            return isValid;
        } catch (Exception e) {
            LOGGER.error("Error validating pipeline state", e);
            VitraDebugUtils.queueDebugMessage("VALIDATION_ERROR: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enable or disable breaking on debug errors (useful for debugging)
     */
    public static void setBreakOnError(boolean enabled) {
        if (!debugInitialized) {
            return;
        }

        try {
            nativeBreakOnDebugError(enabled);
            LOGGER.info("Break on debug error: {}", enabled ? "ENABLED" : "DISABLED");
        } catch (Exception e) {
            LOGGER.error("Error setting break on debug error", e);
        }
    }

    /**
     * Get DirectX 12 debug statistics
     */
    public static String getDebugStats() {
        if (!debugInitialized) {
            return "DirectX 12 Debug: Disabled";
        }

        StringBuilder stats = new StringBuilder();
        stats.append("=== DirectX 12 Debug Stats ===\n");
        stats.append("Debug Initialized: ").append(debugInitialized).append("\n");
        stats.append("Ray Tracing Supported: ").append(rayTracingSupported).append("\n");
        stats.append("VRS Supported: ").append(vrsSupported).append("\n");
        stats.append("Mesh Shaders Supported: ").append(meshShadersSupported).append("\n");

        if (VitraDebugUtils.isDebugInitialized()) {
            stats.append(VitraDebugUtils.getDebugStats());
        }

        return stats.toString();
    }

    // Configuration-driven initialization with debug support
    public static boolean initializeWithConfig(long windowHandle, VitraConfig config) {
        if (isInitialized()) {
            LOGGER.warn("DirectX 12 Ultimate renderer already initialized");
            return true;
        }

        boolean debugMode = config.isDebugMode();
        boolean verboseMode = config.isVerboseLogging();
        int width = 1920; // Could be configurable
        int height = 1080;

        LOGGER.info("Initializing DirectX 12 Ultimate with debug={}, verbose={}", debugMode, verboseMode);

        try {
            // Initialize debug system first
            if (debugMode && !debugInitialized) {
                VitraDebugUtils.initializeDebug(debugMode, verboseMode, true, true);
                debugInitialized = VitraDebugUtils.isDebugInitialized();
                if (debugInitialized) {
                    LOGGER.info("✓ DirectX 12 debug system initialized");
                } else {
                    LOGGER.warn("⚠ DirectX 12 debug system initialization failed, continuing without debug");
                }
            }

            // Initialize DirectX 12
            boolean success = initializeDirectX12(windowHandle, width, height, debugMode);
            if (!success) {
                LOGGER.error("✗ Failed to initialize DirectX 12 Ultimate");
                return false;
            }

            LOGGER.info("✓ DirectX 12 Ultimate initialized successfully");

            // Initialize debug layer for DirectX 12
            if (debugMode && debugInitialized) {
                if (nativeInitializeDebugLayer()) {
                    LOGGER.info("✓ DirectX 12 Debug Layer initialized");

                    // Set debug message severity
                    if (verboseMode) {
                        nativeSetDebugMessageSeverity(0); // All messages
                        LOGGER.info("Debug severity set to verbose");
                    } else {
                        nativeSetDebugMessageSeverity(2); // Errors and warnings only
                        LOGGER.info("Debug severity set to errors/warnings only");
                    }
                } else {
                    LOGGER.warn("⚠ DirectX 12 Debug Layer initialization failed");
                }
            }

            // Query Ultimate features support
            rayTracingSupported = queryRayTracingSupport();
            vrsSupported = queryVariableRateShadingSupport();
            meshShadersSupported = queryMeshShaderSupport();

            LOGGER.info("DirectX 12 Ultimate features supported - Ray Tracing: {}, VRS: {}, Mesh Shaders: {}",
                rayTracingSupported, vrsSupported, meshShadersSupported);

            // Configure enabled features based on config
            if (rayTracingSupported && config.isRayTracingEnabled()) {
                if (nativeEnableRayTracing(config.getRayTracingQuality())) {
                    LOGGER.info("✓ Ray Tracing enabled with quality {}", config.getRayTracingQuality());
                } else {
                    LOGGER.error("✗ Failed to enable Ray Tracing");
                }
            }

            if (vrsSupported && config.isVariableRateShadingEnabled()) {
                if (nativeEnableVariableRateShading((int)config.getVrsTileSize())) {
                    LOGGER.info("✓ Variable Rate Shading enabled with tile size {}", config.getVrsTileSize());
                } else {
                    LOGGER.error("✗ Failed to enable Variable Rate Shading");
                }
            }

            if (meshShadersSupported && config.isMeshShadersEnabled()) {
                if (nativeEnableMeshShaders()) {
                    LOGGER.info("✓ Mesh Shaders enabled");
                } else {
                    LOGGER.error("✗ Failed to enable Mesh Shaders");
                }
            }

            return true;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("DirectX 12 native library not available: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected error during DirectX 12 Ultimate initialization", e);
            if (debugInitialized) {
                VitraDebugUtils.queueDebugMessage("D3D12_INIT_ERROR: " + e.getMessage());
            }
            return false;
        }
    }

    // Utility method to check DirectX 12 Ultimate availability
    public static boolean isDirectX12UltimateAvailable() {
        try {
            // This would query the system for DirectX 12 Ultimate support
            // For now, we'll check Windows version and assume Ultimate is available on Windows 10 2004+
            String osVersion = System.getProperty("os.version");
            String osName = System.getProperty("os.name").toLowerCase();

            if (!osName.contains("windows")) {
                return false;
            }

            // Simple version check - in reality, this would need more sophisticated detection
            return osVersion.compareTo("10.0") >= 0;
        } catch (Exception e) {
            LOGGER.warn("Failed to check DirectX 12 Ultimate availability", e);
            return false;
        }
    }

    // Static initializer for loading native library
    static {
        try {
            System.loadLibrary("vitra-d3d12");
            LOGGER.info("Loaded vitra-d3d12 native library");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Failed to load vitra-d3d12 native library", e);
        }
    }
}