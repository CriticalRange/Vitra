package com.vitra.render.bgfx;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug tools for GLFW, OpenGL, and system-level graphics debugging.
 * These tools complement BGFX's internal debugging to capture ALL rendering errors.
 */
public class DebugTools {
    private static final Logger LOGGER = LoggerFactory.getLogger("DebugTools");
    private static final Logger GLFW_LOGGER = LoggerFactory.getLogger("GLFW");
    private static final Logger GL_LOGGER = LoggerFactory.getLogger("OpenGL");

    private static GLFWErrorCallback glfwErrorCallback = null;
    private static GLDebugMessageCallback glDebugCallback = null;

    /**
     * Initialize GLFW error callback to capture window creation and context errors.
     * Call this BEFORE glfwInit() or window creation.
     */
    public static void initializeGlfwErrorCallback() {
        if (glfwErrorCallback != null) {
            LOGGER.warn("GLFW error callback already initialized");
            return;
        }

        LOGGER.info("Installing GLFW error callback...");

        // Create and install GLFW error callback
        glfwErrorCallback = GLFWErrorCallback.create((error, description) -> {
            String errorMsg = GLFWErrorCallback.getDescription(description);
            String errorName = decodeGlfwError(error);

            GLFW_LOGGER.error("╔════════════════════════════════════════════════════════════╗");
            GLFW_LOGGER.error("║           GLFW ERROR DETECTED                              ║");
            GLFW_LOGGER.error("╠════════════════════════════════════════════════════════════╣");
            GLFW_LOGGER.error("║ Error Code: 0x{} ({})", Integer.toHexString(error), errorName);
            GLFW_LOGGER.error("║ Message:    {}", errorMsg);
            GLFW_LOGGER.error("╚════════════════════════════════════════════════════════════╝");

            // Also print to stderr for critical errors
            System.err.printf("[GLFW-ERROR] 0x%X (%s): %s%n", error, errorName, errorMsg);
        });

        GLFW.glfwSetErrorCallback(glfwErrorCallback);
        LOGGER.info("GLFW error callback installed successfully");
    }

    /**
     * Initialize OpenGL debug context and message callback.
     * Call this AFTER creating an OpenGL context (after window creation).
     *
     * NOTE: This requires OpenGL 4.3+ or GL_KHR_debug extension.
     * Since we're using BGFX with DirectX 11, this may not be used directly,
     * but can be helpful if GL context is still active during initialization.
     */
    public static void initializeOpenGLDebugCallback() {
        try {
            // Check if we have an OpenGL context at all
            GL.getCapabilities();
        } catch (IllegalStateException e) {
            LOGGER.debug("No OpenGL context available, skipping GL debug callback");
            return;
        }

        if (glDebugCallback != null) {
            LOGGER.warn("OpenGL debug callback already initialized");
            return;
        }

        // Check if debug output is supported
        if (!GL.getCapabilities().GL_KHR_debug && !GL.getCapabilities().OpenGL43) {
            LOGGER.warn("OpenGL debug output not supported (requires GL 4.3+ or GL_KHR_debug)");
            return;
        }

        LOGGER.info("Installing OpenGL debug message callback...");

        glDebugCallback = GLDebugMessageCallback.create((source, type, id, severity, length, message, userParam) -> {
            String msg = GLDebugMessageCallback.getMessage(length, message);
            String sourceStr = decodeGLDebugSource(source);
            String typeStr = decodeGLDebugType(type);
            String severityStr = decodeGLDebugSeverity(severity);

            // Log based on severity
            switch (severity) {
                case GL43.GL_DEBUG_SEVERITY_HIGH:
                    GL_LOGGER.error("[GL-{}] {} - {}: {}", severityStr, sourceStr, typeStr, msg);
                    System.err.printf("[GL-ERROR] %s - %s: %s%n", sourceStr, typeStr, msg);
                    break;
                case GL43.GL_DEBUG_SEVERITY_MEDIUM:
                    GL_LOGGER.warn("[GL-{}] {} - {}: {}", severityStr, sourceStr, typeStr, msg);
                    break;
                case GL43.GL_DEBUG_SEVERITY_LOW:
                    GL_LOGGER.info("[GL-{}] {} - {}: {}", severityStr, sourceStr, typeStr, msg);
                    break;
                case GL43.GL_DEBUG_SEVERITY_NOTIFICATION:
                    GL_LOGGER.debug("[GL-{}] {} - {}: {}", severityStr, sourceStr, typeStr, msg);
                    break;
            }
        });

        GL43.glDebugMessageCallback(glDebugCallback, MemoryUtil.NULL);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS); // Synchronous for easier debugging

        LOGGER.info("OpenGL debug message callback installed successfully");
    }

    /**
     * Clean up debug callbacks.
     * Call this during shutdown.
     */
    public static void shutdown() {
        LOGGER.info("Cleaning up debug tools...");

        if (glfwErrorCallback != null) {
            glfwErrorCallback.free();
            glfwErrorCallback = null;
            LOGGER.debug("GLFW error callback freed");
        }

        if (glDebugCallback != null) {
            glDebugCallback.free();
            glDebugCallback = null;
            LOGGER.debug("OpenGL debug callback freed");
        }

        LOGGER.info("Debug tools cleanup complete");
    }

    /**
     * Decode GLFW error codes to human-readable names.
     */
    private static String decodeGlfwError(int error) {
        switch (error) {
            case GLFW.GLFW_NOT_INITIALIZED:
                return "NOT_INITIALIZED";
            case GLFW.GLFW_NO_CURRENT_CONTEXT:
                return "NO_CURRENT_CONTEXT";
            case GLFW.GLFW_INVALID_ENUM:
                return "INVALID_ENUM";
            case GLFW.GLFW_INVALID_VALUE:
                return "INVALID_VALUE";
            case GLFW.GLFW_OUT_OF_MEMORY:
                return "OUT_OF_MEMORY";
            case GLFW.GLFW_API_UNAVAILABLE:
                return "API_UNAVAILABLE";
            case GLFW.GLFW_VERSION_UNAVAILABLE:
                return "VERSION_UNAVAILABLE";
            case GLFW.GLFW_PLATFORM_ERROR:
                return "PLATFORM_ERROR";
            case GLFW.GLFW_FORMAT_UNAVAILABLE:
                return "FORMAT_UNAVAILABLE";
            default:
                return "UNKNOWN_ERROR";
        }
    }

    /**
     * Decode OpenGL debug message source.
     */
    private static String decodeGLDebugSource(int source) {
        switch (source) {
            case GL43.GL_DEBUG_SOURCE_API:
                return "API";
            case GL43.GL_DEBUG_SOURCE_WINDOW_SYSTEM:
                return "WINDOW_SYSTEM";
            case GL43.GL_DEBUG_SOURCE_SHADER_COMPILER:
                return "SHADER_COMPILER";
            case GL43.GL_DEBUG_SOURCE_THIRD_PARTY:
                return "THIRD_PARTY";
            case GL43.GL_DEBUG_SOURCE_APPLICATION:
                return "APPLICATION";
            case GL43.GL_DEBUG_SOURCE_OTHER:
                return "OTHER";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Decode OpenGL debug message type.
     */
    private static String decodeGLDebugType(int type) {
        switch (type) {
            case GL43.GL_DEBUG_TYPE_ERROR:
                return "ERROR";
            case GL43.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR:
                return "DEPRECATED_BEHAVIOR";
            case GL43.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR:
                return "UNDEFINED_BEHAVIOR";
            case GL43.GL_DEBUG_TYPE_PORTABILITY:
                return "PORTABILITY";
            case GL43.GL_DEBUG_TYPE_PERFORMANCE:
                return "PERFORMANCE";
            case GL43.GL_DEBUG_TYPE_MARKER:
                return "MARKER";
            case GL43.GL_DEBUG_TYPE_PUSH_GROUP:
                return "PUSH_GROUP";
            case GL43.GL_DEBUG_TYPE_POP_GROUP:
                return "POP_GROUP";
            case GL43.GL_DEBUG_TYPE_OTHER:
                return "OTHER";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Decode OpenGL debug message severity.
     */
    private static String decodeGLDebugSeverity(int severity) {
        switch (severity) {
            case GL43.GL_DEBUG_SEVERITY_HIGH:
                return "HIGH";
            case GL43.GL_DEBUG_SEVERITY_MEDIUM:
                return "MEDIUM";
            case GL43.GL_DEBUG_SEVERITY_LOW:
                return "LOW";
            case GL43.GL_DEBUG_SEVERITY_NOTIFICATION:
                return "NOTIFICATION";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Print system graphics information for debugging.
     * Useful for diagnosing driver/hardware issues.
     */
    public static void printSystemInfo() {
        LOGGER.info("═══════════════════════════════════════════════════════════");
        LOGGER.info("System Graphics Information:");
        LOGGER.info("═══════════════════════════════════════════════════════════");
        LOGGER.info("OS:          {}", System.getProperty("os.name"));
        LOGGER.info("OS Version:  {}", System.getProperty("os.version"));
        LOGGER.info("OS Arch:     {}", System.getProperty("os.arch"));
        LOGGER.info("Java:        {}", System.getProperty("java.version"));
        LOGGER.info("Java Vendor: {}", System.getProperty("java.vendor"));

        try {
            // Try to get OpenGL info if context is available
            GL.getCapabilities();
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String version = GL11.glGetString(GL11.GL_VERSION);

            LOGGER.info("GL Vendor:   {}", vendor);
            LOGGER.info("GL Renderer: {}", renderer);
            LOGGER.info("GL Version:  {}", version);
        } catch (Exception e) {
            LOGGER.info("GL Info:     Not available (no OpenGL context)");
        }

        LOGGER.info("═══════════════════════════════════════════════════════════");
    }
}
