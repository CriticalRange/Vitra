package com.vitra.render.opengl;

import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.debug.VitraDebugUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenGL call interceptor that translates OpenGL calls to DirectX rendering
 */
public class GLInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(GLInterceptor.class);

    // System state
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean active = new AtomicBoolean(false);

    // Resource tracking
    private static final Map<Integer, GLResource> resources = new HashMap<>();
    private static final AtomicLong nextResourceId = new AtomicLong(1);

    // State tracking
    private static GLState currentState = new GLState();

    // Performance metrics
    private static long interceptedCalls = 0;
    private static long translatedCalls = 0;

    /**
     * Initialize the OpenGL interceptor system
     */
    public static synchronized void initialize() {
        if (initialized.get()) {
            return;
        }

        LOGGER.info("Initializing OpenGL interceptor system (Mixin-based)");

        try {
            // Set up mixin-based interception tracking
            setupLWJGLInterception();

            active.set(true);
            initialized.set(true);

            LOGGER.info("OpenGL interceptor initialized successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize OpenGL interceptor", e);
        }
    }

    /**
     * Enable or disable OpenGL interception
     */
    public static void setActive(boolean enabled) {
        if (initialized.get()) {
            active.set(enabled);
            LOGGER.info("OpenGL interception {}", enabled ? "enabled" : "disabled");
        }
    }

    /**
     * Check if interception is active
     */
    public static boolean isActive() {
        return initialized.get() && active.get();
    }

    /**
     * Interceptor for glGenTextures
     */
    public static void glGenTextures(IntBuffer textures) {
        if (!isActive()) {
            return; // Let original call proceed
        }

        interceptedCalls++;

        // Generate texture IDs through our system
        for (int i = 0; i < textures.remaining(); i++) {
            long resourceId = nextResourceId.getAndIncrement();
            textures.put(i, (int) resourceId);

            // Track the resource
            GLResource resource = new GLResource(resourceId, GLResource.Type.TEXTURE);
            resources.put((int) resourceId, resource);

            // Create corresponding DirectX resource
            VitraNativeRenderer.createTexture(null, 1, 1, 0); // Placeholder
        }

        textures.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glBindTexture
     */
    public static void glBindTexture(int target, int texture) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.setBoundTexture(target, texture);

        if (texture != 0) {
            GLResource resource = resources.get(texture);
            if (resource != null) {
                // Bind corresponding DirectX resource
                VitraNativeRenderer.bindTexture(resource.getDirectXHandle(), 0);
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glTexImage2D
     */
    public static void glTexImage2D(int target, int level, int internalformat,
                                   int width, int height, int border,
                                   int format, int type, ByteBuffer pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        int boundTexture = currentState.getBoundTexture(GL11.GL_TEXTURE_2D);
        if (boundTexture != 0) {
            GLResource resource = resources.get(boundTexture);
            if (resource != null) {
                // Create DirectX texture with the specified data
                if (pixels != null) {
                    VitraNativeRenderer.createTexture(pixels.array(), width, height, format);
                }
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glGenBuffers
     */
    public static void glGenBuffers(IntBuffer buffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        for (int i = 0; i < buffers.remaining(); i++) {
            long resourceId = nextResourceId.getAndIncrement();
            buffers.put(i, (int) resourceId);

            GLResource resource = new GLResource(resourceId, GLResource.Type.BUFFER);
            resources.put((int) resourceId, resource);
        }

        buffers.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glBindBuffer
     */
    public static void glBindBuffer(int target, int buffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.setBoundBuffer(target, buffer);

        if (buffer != 0) {
            GLResource resource = resources.get(buffer);
            if (resource != null) {
                // Handle DirectX buffer binding
                // Implementation depends on buffer type (vertex, index, etc.)
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glBufferData
     */
    public static void glBufferData(int target, long size, ByteBuffer data, int usage) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        int boundBuffer = currentState.getBoundBuffer(target);
        if (boundBuffer != 0) {
            GLResource resource = resources.get(boundBuffer);
            if (resource != null) {
                // Create DirectX buffer with the data
                if (data != null && data.hasArray()) {
                    VitraNativeRenderer.createVertexBuffer(data.array(), (int) size, 0);
                }
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glDrawArrays
     */
    public static void glDrawArrays(int mode, int first, int count) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        // Translate to DirectX draw call
        long vertexBuffer = getCurrentVertexBuffer();
        VitraNativeRenderer.draw(vertexBuffer, 0, count, 0);

        translatedCalls++;
    }

    /**
     * Interceptor for glDrawElements
     */
    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        // Translate to DirectX indexed draw call
        long vertexBuffer = getCurrentVertexBuffer();
        long indexBuffer = getCurrentIndexBuffer();

        VitraNativeRenderer.draw(vertexBuffer, indexBuffer, 0, count);

        translatedCalls++;
    }

    /**
     * Interceptor for glEnable/glDisable
     */
    public static void glEnable(int cap) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.enableCapability(cap);
        // Translate DirectX render state
        translatedCalls++;
    }

    public static void glDisable(int cap) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.disableCapability(cap);
        // Translate DirectX render state
        translatedCalls++;
    }

    /**
     * Interceptor for glViewport
     */
    public static void glViewport(int x, int y, int width, int height) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        VitraNativeRenderer.setViewport(x, y, width, height);
        currentState.setViewport(x, y, width, height);

        translatedCalls++;
    }

    /**
     * Interceptor for glClear
     */
    public static void glClear(int mask) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        // Translate clear mask to DirectX clear
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0) {
            VitraNativeRenderer.clear(0.0f, 0.0f, 0.0f, 1.0f);
        }

        translatedCalls++;
    }

    // Helper methods
    private static long getCurrentVertexBuffer() {
        int bufferId = currentState.getBoundBuffer(GL15.GL_ARRAY_BUFFER);
        GLResource resource = resources.get(bufferId);
        return resource != null ? resource.getDirectXHandle() : 0;
    }

    private static long getCurrentIndexBuffer() {
        int bufferId = currentState.getBoundBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER);
        GLResource resource = resources.get(bufferId);
        return resource != null ? resource.getDirectXHandle() : 0;
    }

    /**
     * Set up LWJGL function interception
     */
    private static void setupLWJGLInterception() {
        try {
            // This would require more advanced LWJGL interception
            // For now, we rely on explicit calls from mixins
            LOGGER.info("LWJGL interception setup completed");
        } catch (Exception e) {
            LOGGER.warn("Failed to setup LWJGL interception", e);
        }
    }

    /**
     * Get performance statistics
     */
    public static String getStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== OpenGL Interceptor Stats ===\n");
        stats.append("Initialized: ").append(initialized.get()).append("\n");
        stats.append("Active: ").append(active.get()).append("\n");
        stats.append("Intercepted Calls: ").append(interceptedCalls).append("\n");
        stats.append("Translated Calls: ").append(translatedCalls).append("\n");
        stats.append("Resources Tracked: ").append(resources.size()).append("\n");
        stats.append("Translation Efficiency: ");
        if (interceptedCalls > 0) {
            stats.append(String.format("%.2f%%", (translatedCalls * 100.0 / interceptedCalls)));
        } else {
            stats.append("N/A");
        }
        stats.append("\n");
        return stats.toString();
    }

    /**
     * Clean up resources
     */
    public static synchronized void shutdown() {
        if (initialized.get()) {
            LOGGER.info("Shutting down OpenGL interceptor");

            active.set(false);

            // Clean up tracked resources
            for (GLResource resource : resources.values()) {
                VitraNativeRenderer.destroyResource(resource.getDirectXHandle());
            }
            resources.clear();

            initialized.set(false);
            LOGGER.info("OpenGL interceptor shutdown complete");
        }
    }

    /**
     * Resource tracking class
     */
    private static class GLResource {
        enum Type {
            TEXTURE, BUFFER, SHADER, PROGRAM, FRAMEBUFFER
        }

        private final long glId;
        private final Type type;
        private final long directXHandle;
        private final long creationTime;

        GLResource(long glId, Type type) {
            this.glId = glId;
            this.type = type;
            this.directXHandle = VitraNativeRenderer.createVertexBuffer(null, 0, 0); // Placeholder
            this.creationTime = System.currentTimeMillis();
        }

        long getDirectXHandle() {
            return directXHandle;
        }

        Type getType() {
            return type;
        }
    }

    /**
     * OpenGL state tracking
     */
    private static class GLState {
        private final Map<Integer, Integer> boundTextures = new HashMap<>();
        private final Map<Integer, Integer> boundBuffers = new HashMap<>();
        private final Map<Integer, Boolean> capabilities = new HashMap<>();
        private int[] viewport = new int[4];

        void setBoundTexture(int target, int texture) {
            boundTextures.put(target, texture);
        }

        int getBoundTexture(int target) {
            return boundTextures.getOrDefault(target, 0);
        }

        void setBoundBuffer(int target, int buffer) {
            boundBuffers.put(target, buffer);
        }

        int getBoundBuffer(int target) {
            return boundBuffers.getOrDefault(target, 0);
        }

        void enableCapability(int cap) {
            capabilities.put(cap, true);
        }

        void disableCapability(int cap) {
            capabilities.put(cap, false);
        }

        boolean isCapabilityEnabled(int cap) {
            return capabilities.getOrDefault(cap, false);
        }

        void setViewport(int x, int y, int width, int height) {
            viewport[0] = x;
            viewport[1] = y;
            viewport[2] = width;
            viewport[3] = height;
        }

        int[] getViewport() {
            return viewport.clone();
        }
    }
}