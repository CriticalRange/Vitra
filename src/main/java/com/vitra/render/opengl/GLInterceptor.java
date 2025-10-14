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
import java.util.Collection;
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
    private static final AtomicBoolean frameStarted = new AtomicBoolean(false);

    // Resource tracking
    private static final Map<Integer, GLResource> resources = new HashMap<>();
    private static final AtomicLong nextResourceId = new AtomicLong(1);

    // State tracking
    private static GLState currentState = new GLState();

    // Clear color tracking (FIX: Add missing glClearColor state)
    private static float[] clearColor = {0.0f, 0.0f, 0.0f, 1.0f};

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
     * Reset frame state for new render cycle
     */
    public static void resetFrameState() {
        frameStarted.set(false);
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
     * Ensure DirectX frame is started before drawing
     */
    private static void ensureFrameStarted() {
        if (!frameStarted.get() && isActive()) {
            VitraNativeRenderer.beginFrame();
            frameStarted.set(true);
        }
    }

    /**
     * Interceptor for glDrawArrays
     */
    public static void glDrawArrays(int mode, int first, int count) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        // Ensure DirectX frame is started
        ensureFrameStarted();

        // Translate to DirectX draw call
        long vertexBuffer = getCurrentVertexBuffer();
        VitraNativeRenderer.draw(vertexBuffer, 0, 0, first, count, 1);

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

        // Ensure DirectX frame is started
        ensureFrameStarted();

        // Translate to DirectX indexed draw call
        long vertexBuffer = getCurrentVertexBuffer();
        long indexBuffer = getCurrentIndexBuffer();

        VitraNativeRenderer.draw(vertexBuffer, indexBuffer, 0, 0, count, 1);

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
     * Interceptor for glClearColor (FIX: Missing from original implementation)
     * Based on Mojang mappings - this is called before glClear to set the clear color
     */
    public static void glClearColor(float r, float g, float b, float a) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        // Track clear color state (FIX: This was missing!)
        clearColor[0] = r;
        clearColor[1] = g;
        clearColor[2] = b;
        clearColor[3] = a;

        LOGGER.debug("glClearColor: r={}, g={}, b={}, a={}", r, g, b, a);

        // Forward clear color to DirectX
        VitraNativeRenderer.setClearColor(r, g, b, a);
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

        LOGGER.debug("glClear: mask=0x{}", Integer.toHexString(mask));

        // Translate clear mask to DirectX clear using tracked clear color (FIX: Use actual clear color)
        if ((mask & GL11.GL_COLOR_BUFFER_BIT) != 0) {
            VitraNativeRenderer.clear(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            LOGGER.debug("  -> Clearing color buffer to: [{}, {}, {}, {}]",
                clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        }

        // Handle depth buffer clear
        if ((mask & GL11.GL_DEPTH_BUFFER_BIT) != 0) {
            VitraNativeRenderer.clearDepth(1.0f);
            LOGGER.debug("  -> Clearing depth buffer");
        }

        translatedCalls++;
    }

    // ============================================================================
    // MISSING UNIFORM INTERCEPTORS (FIX: Add missing shader uniform tracking)
    // ============================================================================

    /**
     * Interceptor for glUniform4f (FIX: Missing - causes ray artifacts)
     * Based on Mojang mappings - uniform tracking is essential for proper vertex transformation
     */
    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUniform4f: location={}, values=[{}, {}, {}, {}]", location, v0, v1, v2, v3);

        // Forward uniform to DirectX constant buffer
        VitraNativeRenderer.setUniform4f(location, v0, v1, v2, v3);
        translatedCalls++;
    }

    /**
     * Interceptor for glUniformMatrix4fv (FIX: Missing - causes transformation issues)
     * Matrix uniforms are critical for proper vertex positioning
     */
    public static void glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUniformMatrix4fv: location={}, count={}, transpose={}", location, count, transpose);

        // Forward matrix uniform to DirectX
        if (value != null && value.hasRemaining()) {
            float[] matrix = new float[Math.min(16 * count, value.remaining())];
            value.get(matrix);
            value.rewind();
            VitraNativeRenderer.setUniformMatrix4f(location, matrix, transpose);
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glUniform1i (FIX: Missing - causes texture sampling issues)
     * Sampler uniforms are essential for proper texture binding
     */
    public static void glUniform1i(int location, int v0) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUniform1i: location={}, value={}", location, v0);

        // Forward integer uniform to DirectX
        VitraNativeRenderer.setUniform1i(location, v0);
        translatedCalls++;
    }

    /**
     * Interceptor for glUniform1f (FIX: Missing - causes parameter issues)
     */
    public static void glUniform1f(int location, float v0) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUniform1f: location={}, value={}", location, v0);

        // Forward float uniform to DirectX
        VitraNativeRenderer.setUniform1f(location, v0);
        translatedCalls++;
    }

    /**
     * Interceptor for glUseProgram (FIX: Missing - causes shader program issues)
     * Program switching is essential for proper shader state management
     */
    public static void glUseProgram(int program) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUseProgram: program={}", program);

        // Forward program selection to DirectX
        VitraNativeRenderer.useProgram(program);
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
            frameStarted.set(false);

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

    // ============================================================================
    // NEW MINECRAFT 1.21.8 INTERCEPTION METHODS (CommandEncoder/RenderPass)
    // ============================================================================

    // State tracking for Minecraft 1.21.8 GpuBuffer system
    private static final Map<Integer, Object> vertexBufferSlots = new HashMap<>();
    private static Object boundIndexBuffer = null;
    private static int boundIndexType = 0;

    /**
     * Extract native DirectX 11 handle from GpuBuffer
     */
    private static long extractNativeHandle(Object gpuBuffer) {
        if (gpuBuffer == null) {
            return 0;
        }

        // Check if this is our DirectX11GpuBuffer implementation
        if (gpuBuffer instanceof com.vitra.render.dx11.DirectX11GpuBuffer) {
            com.vitra.render.dx11.DirectX11GpuBuffer dx11Buffer =
                (com.vitra.render.dx11.DirectX11GpuBuffer) gpuBuffer;
            return dx11Buffer.getNativeHandle();
        }

        // Fallback: try reflection to get native handle from any GpuBuffer subclass
        try {
            java.lang.reflect.Method getNativeHandleMethod =
                gpuBuffer.getClass().getMethod("getNativeHandle");
            Object result = getNativeHandleMethod.invoke(gpuBuffer);
            if (result instanceof Long) {
                return (Long) result;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract native handle from buffer: {}", gpuBuffer.getClass().getName());
        }

        return 0;
    }

    /**
     * Intercept draw call from CommandEncoder/RenderPass (Minecraft 1.21.8)
     */
    public static void onDrawCall(int baseVertex, int firstIndex, int count, int indexType, int instanceCount) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        ensureFrameStarted();

        LOGGER.debug("DrawCall: baseVertex={}, firstIndex={}, count={}, indexType={}, instances={}",
            baseVertex, firstIndex, count, indexType, instanceCount);

        // Get currently bound buffers from Minecraft 1.21.8 state
        long vertexBufferHandle = 0;
        long indexBufferHandle = 0;

        // Try to get vertex buffer from slot 0 (primary vertex buffer)
        Object vertexBuffer = vertexBufferSlots.get(0);
        if (vertexBuffer != null) {
            vertexBufferHandle = extractNativeHandle(vertexBuffer);
        }

        // Get index buffer if bound
        if (boundIndexBuffer != null) {
            indexBufferHandle = extractNativeHandle(boundIndexBuffer);
        }

        LOGGER.debug("  -> Vertex buffer handle: 0x{}, Index buffer handle: 0x{}",
            Long.toHexString(vertexBufferHandle), Long.toHexString(indexBufferHandle));

        // Forward to DirectX 11 with CORRECT parameters
        if (indexBufferHandle != 0) {
            // Indexed draw call - pass baseVertex, firstIndex, count, instanceCount
            VitraNativeRenderer.draw(vertexBufferHandle, indexBufferHandle, baseVertex, firstIndex, count, instanceCount);
        } else {
            // Non-indexed draw call - pass firstIndex as offset
            VitraNativeRenderer.draw(vertexBufferHandle, 0, 0, firstIndex, count, instanceCount);
        }

        translatedCalls++;
    }

    /**
     * Intercept batched draw calls from CommandEncoder/RenderPass
     */
    public static void onBatchedDrawCalls(Collection<?> drawObjects, Object indexBuffer, int indexType) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        ensureFrameStarted();

        int batchSize = drawObjects != null ? drawObjects.size() : 0;
        LOGGER.debug("BatchedDrawCalls: {} objects, indexType={}", batchSize, indexType);

        long indexBufferHandle = extractNativeHandle(indexBuffer);

        // Iterate through draw objects and forward each to DirectX 11
        if (drawObjects != null) {
            for (Object drawObj : drawObjects) {
                try {
                    // Use reflection to extract draw parameters from RenderPass$Draw
                    // Fields: vertexBuffer, baseVertex, firstIndex, indexCount, etc.
                    java.lang.reflect.Method vertexBufferMethod = drawObj.getClass().getMethod("vertexBuffer");
                    java.lang.reflect.Method baseVertexMethod = drawObj.getClass().getMethod("baseVertex");
                    java.lang.reflect.Method firstIndexMethod = drawObj.getClass().getMethod("firstIndex");
                    java.lang.reflect.Method indexCountMethod = drawObj.getClass().getMethod("indexCount");

                    Object vertexBuffer = vertexBufferMethod.invoke(drawObj);
                    int baseVertex = (Integer) baseVertexMethod.invoke(drawObj);
                    int firstIndex = (Integer) firstIndexMethod.invoke(drawObj);
                    int indexCount = (Integer) indexCountMethod.invoke(drawObj);

                    long vertexBufferHandle = extractNativeHandle(vertexBuffer);

                    LOGGER.debug("  -> Batched draw: vb=0x{}, ib=0x{}, baseVertex={}, firstIndex={}, count={}",
                        Long.toHexString(vertexBufferHandle), Long.toHexString(indexBufferHandle),
                        baseVertex, firstIndex, indexCount);

                    // Forward this draw call to DirectX 11 with CORRECT parameters
                    VitraNativeRenderer.draw(vertexBufferHandle, indexBufferHandle, baseVertex, firstIndex, indexCount, 1);

                } catch (Exception e) {
                    LOGGER.error("Error extracting draw parameters from batched draw object", e);
                }
            }
        }

        translatedCalls++;
    }

    /**
     * Intercept draw call from RenderPass.draw (non-indexed)
     */
    public static void onDrawArrays(int offset, int count) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        ensureFrameStarted();

        LOGGER.debug("DrawArrays: offset={}, count={}", offset, count);

        // Get vertex buffer from slot 0
        Object vertexBuffer = vertexBufferSlots.get(0);
        long vertexBufferHandle = extractNativeHandle(vertexBuffer);

        LOGGER.debug("  -> Vertex buffer handle: 0x{}", Long.toHexString(vertexBufferHandle));

        // Non-indexed draw - pass offset as firstIndex
        VitraNativeRenderer.draw(vertexBufferHandle, 0, 0, offset, count, 1);

        translatedCalls++;
    }

    /**
     * Intercept draw call from RenderPass.drawIndexed
     */
    public static void onDrawIndexed(int baseVertex, int firstIndex, int count, int instanceCount) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        ensureFrameStarted();

        LOGGER.debug("DrawIndexed: baseVertex={}, firstIndex={}, count={}, instances={}",
            baseVertex, firstIndex, count, instanceCount);

        // Get currently bound buffers
        Object vertexBuffer = vertexBufferSlots.get(0);
        long vertexBufferHandle = extractNativeHandle(vertexBuffer);
        long indexBufferHandle = extractNativeHandle(boundIndexBuffer);

        LOGGER.debug("  -> Vertex buffer handle: 0x{}, Index buffer handle: 0x{}",
            Long.toHexString(vertexBufferHandle), Long.toHexString(indexBufferHandle));

        // Indexed draw with CORRECT parameters
        VitraNativeRenderer.draw(vertexBufferHandle, indexBufferHandle, baseVertex, firstIndex, count, instanceCount);

        translatedCalls++;
    }

    /**
     * Intercept vertex buffer binding from RenderPass.setVertexBuffer
     */
    public static void onBindVertexBuffer(int slot, Object gpuBuffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        long handle = extractNativeHandle(gpuBuffer);
        LOGGER.debug("BindVertexBuffer: slot={}, buffer={}, handle=0x{}",
            slot, gpuBuffer != null ? gpuBuffer.getClass().getSimpleName() : "null",
            Long.toHexString(handle));

        // Track the bound buffer
        if (gpuBuffer != null) {
            vertexBufferSlots.put(slot, gpuBuffer);
        } else {
            vertexBufferSlots.remove(slot);
        }

        // Note: We don't need to explicitly bind in DirectX 11 since draw() will use the handle directly
        // DirectX 11 uses PSO (Pipeline State Objects) that bundle vertex buffers with draw calls

        translatedCalls++;
    }

    /**
     * Intercept index buffer binding from RenderPass.setIndexBuffer
     */
    public static void onBindIndexBuffer(Object gpuBuffer, int indexType) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        long handle = extractNativeHandle(gpuBuffer);
        LOGGER.debug("BindIndexBuffer: buffer={}, type={}, handle=0x{}",
            gpuBuffer != null ? gpuBuffer.getClass().getSimpleName() : "null",
            indexType, Long.toHexString(handle));

        // Track the bound index buffer and type
        boundIndexBuffer = gpuBuffer;
        boundIndexType = indexType;

        // Note: We don't need to explicitly bind in DirectX 11 since draw() will use the handle directly
        // DirectX 11 IASetIndexBuffer is called internally by the native draw() method

        translatedCalls++;
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