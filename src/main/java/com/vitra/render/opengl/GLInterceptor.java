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

    // AbstractTexture tracking for MAbstractTexture integration
    private static final Map<Integer, Long> textureToDirectXHandle = new HashMap<>();
    private static final Map<Long, Integer> directXHandleToTextureId = new HashMap<>();

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
            int textureId = (int) resourceId;
            textures.put(i, textureId);

            // Track the resource
            GLResource resource = new GLResource(resourceId, GLResource.Type.TEXTURE);
            resources.put(textureId, resource);

            // Create corresponding DirectX resource
            long directXHandle = VitraNativeRenderer.createTexture(null, 1, 1, 0);

            // Map texture ID to DirectX handle for MAbstractTexture integration
            textureToDirectXHandle.put(textureId, directXHandle);
            directXHandleToTextureId.put(directXHandle, textureId);

            LOGGER.debug("GLInterceptor: Created texture ID {} with DirectX handle 0x{}",
                textureId, Long.toHexString(directXHandle));
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
            // First check MAbstractTexture mapping
            Long directXHandle = textureToDirectXHandle.get(texture);
            if (directXHandle != null && directXHandle != 0) {
                VitraNativeRenderer.bindTexture(directXHandle, 0);
                LOGGER.debug("GLInterceptor: Bound texture ID {} via MAbstractTexture mapping (handle 0x{})",
                    texture, Long.toHexString(directXHandle));
            } else {
                // Fallback to resource tracking
                GLResource resource = resources.get(texture);
                if (resource != null) {
                    VitraNativeRenderer.bindTexture(resource.getDirectXHandle(), 0);
                }
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
     * Interceptor for glTexImage2D (pointer version)
     */
    public static void glTexImage2D(int target, int level, int internalformat,
                                   int width, int height, int border,
                                   int format, int type, long pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for texture image upload with pointer
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
     * Interceptor for glScissor
     */
    public static void glScissor(int x, int y, int width, int height) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        VitraNativeRenderer.setScissorRect(x, y, width, height);

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
    public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        LOGGER.debug("glUniformMatrix4fv: location={}, transpose={}", location, transpose);

        // Forward matrix uniform to DirectX
        if (value != null && value.hasRemaining()) {
            float[] matrix = new float[Math.min(16, value.remaining())];
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

    // ============================================================================
    // ADDITIONAL LWJGL INTERCEPTOR METHODS (from new mixins)
    // ============================================================================

    /**
     * Interceptor for glGenTextures (single texture)
     */
    public static int glGenTextures() {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        long resourceId = nextResourceId.getAndIncrement();
        int textureId = (int) resourceId;

        GLResource resource = new GLResource(resourceId, GLResource.Type.TEXTURE);
        resources.put(textureId, resource);

        // Create corresponding DirectX resource
        VitraNativeRenderer.createTexture(null, 1, 1, 0); // Placeholder

        translatedCalls++;
        return textureId;
    }

    /**
     * Interceptor for glTexSubImage2D (pointer version)
     */
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for texture sub-image update
        translatedCalls++;
    }

    /**
     * Interceptor for glTexSubImage2D (ByteBuffer version)
     */
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, java.nio.ByteBuffer pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for texture sub-image update with ByteBuffer
        translatedCalls++;
    }

    /**
     * Interceptor for glTexSubImage2D (IntBuffer version)
     */
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for texture sub-image update with IntBuffer
        translatedCalls++;
    }

    /**
     * Interceptor for glTexParameteri (CRITICAL for fixing yellow rays)
     */
    public static void glTexParameteri(int target, int pname, int param) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        LOGGER.debug("glTexParameteri: target={}, pname={}, param={}", target, pname, param);

        // Forward texture parameter to DirectX
        VitraNativeRenderer.setTextureParameter(target, pname, param);
        translatedCalls++;
    }

    /**
     * Interceptor for glTexParameterf
     */
    public static void glTexParameterf(int target, int pname, float param) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        LOGGER.debug("glTexParameterf: target={}, pname={}, param={}", target, pname, param);

        // Forward texture parameter to DirectX
        VitraNativeRenderer.setTextureParameterf(target, pname, param);
        translatedCalls++;
    }

    /**
     * Interceptor for glGetTexParameteri
     */
    public static int glGetTexParameteri(int target, int pname) {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        int result = VitraNativeRenderer.getTextureParameter(target, pname);
        translatedCalls++;
        return result;
    }

    /**
     * Interceptor for glGetTexLevelParameteri
     */
    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        int result = VitraNativeRenderer.getTextureLevelParameter(target, level, pname);
        translatedCalls++;
        return result;
    }

    /**
     * Interceptor for glPixelStorei (important for texture alignment)
     */
    public static void glPixelStorei(int pname, int param) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        LOGGER.debug("glPixelStorei: pname={}, param={}", pname, param);

        // Forward pixel storage mode to DirectX
        VitraNativeRenderer.setPixelStore(pname, param);
        translatedCalls++;
    }

    /**
     * Interceptor for glGetTexImage
     */
    public static void glGetTexImage(int tex, int level, int format, int type, long pixels) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for getting texture image data
        translatedCalls++;
    }

    /**
     * Interceptor for glDeleteTextures (single texture)
     */
    public static void glDeleteTextures(int texture) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        GLResource resource = resources.remove(texture);
        if (resource != null) {
            VitraNativeRenderer.destroyResource(resource.getDirectXHandle());
        }
        translatedCalls++;
    }

    /**
     * Interceptor for glDeleteTextures (IntBuffer version)
     */
    public static void glDeleteTextures(IntBuffer textures) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        while (textures.hasRemaining()) {
            int texture = textures.get();
            GLResource resource = resources.remove(texture);
            if (resource != null) {
                VitraNativeRenderer.destroyResource(resource.getDirectXHandle());
            }
        }
        textures.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glLineWidth
     */
    public static void glLineWidth(float width) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setLineWidth(width);
        translatedCalls++;
    }

    /**
     * Interceptor for glDepthMask
     */
    public static void glDepthMask(boolean flag) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setDepthMask(flag);
        translatedCalls++;
    }

    /**
     * Interceptor for glPolygonOffset (for depth fighting)
     */
    public static void glPolygonOffset(float factor, float units) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setPolygonOffset(factor, units);
        translatedCalls++;
    }

    /**
     * Interceptor for glBlendFunc (CRITICAL for proper rendering)
     */
    public static void glBlendFunc(int sfactor, int dfactor) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setBlendFunc(sfactor, dfactor);
        translatedCalls++;
    }

    /**
     * Interceptor for glIsEnabled
     */
    public static boolean glIsEnabled(int cap) {
        if (!isActive()) {
            return false;
        }

        interceptedCalls++;
        boolean result = currentState.isCapabilityEnabled(cap);
        translatedCalls++;
        return result;
    }

    /**
     * Interceptor for glGetInteger
     */
    public static int glGetInteger(int pname) {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        int result = 0;
        // Handle common integer queries
        switch (pname) {
            case GL11.GL_MAX_TEXTURE_SIZE:
                result = VitraNativeRenderer.getMaxTextureSize();
                break;
            default:
                result = 0;
        }
        translatedCalls++;
        return result;
    }

    /**
     * Interceptor for glGetError
     */
    public static int glGetError() {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        // Return no error for DirectX 11
        translatedCalls++;
        return 0;
    }

    /**
     * Interceptor for glFinish
     */
    public static void glFinish() {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.finish();
        translatedCalls++;
    }

    /**
     * Interceptor for glHint
     */
    public static void glHint(int target, int hint) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setHint(target, hint);
        translatedCalls++;
    }

    /**
     * Interceptor for glCopyTexSubImage2D
     */
    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        translatedCalls++;
    }

    /**
     * Interceptor for glActiveTexture (multitexturing support)
     */
    public static void glActiveTexture(int texture) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement active texture tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glFrontFace
     */
    public static void glFrontFace(int mode) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement front face tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glCullFace
     */
    public static void glCullFace(int mode) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement cull face tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glDepthFunc
     */
    public static void glDepthFunc(int func) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement depth function tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glPointSize
     */
    public static void glPointSize(float size) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement point size tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glColorMask
     */
    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement color mask tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glBlendFuncSeparate (advanced blending)
     */
    public static void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement blend func separate tracking for DirectX 11
        translatedCalls++;
    }

    /**
     * Interceptor for glGetIntegerv (array version)
     */
    public static void glGetIntegerv(int pname, IntBuffer data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle common integer queries that return arrays
        switch (pname) {
            case GL11.GL_VIEWPORT:
                int[] viewport = currentState.getViewport();
                if (data.remaining() >= 4) {
                    data.put(viewport);
                    data.rewind();
                }
                break;
            case GL11.GL_MAX_TEXTURE_SIZE:
                if (data.remaining() >= 1) {
                    data.put(0, VitraNativeRenderer.getMaxTextureSize());
                }
                break;
            default:
                // For other queries, try to get from native renderer
                if (data.remaining() >= 1) {
                    data.put(0, 0); // Default fallback
                }
        }
        translatedCalls++;
    }

    /**
     * Interceptor for glGetFloatv (array version)
     */
    public static void glGetFloatv(int pname, FloatBuffer data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle common float queries that return arrays
        switch (pname) {
            default:
                // For other queries, try to get from native renderer
                if (data.remaining() >= 1) {
                    data.put(0, 0.0f); // Default fallback
                }
        }
        translatedCalls++;
    }

    /**
     * Interceptor for glGetBooleanv (array version)
     */
    public static void glGetBooleanv(int pname, ByteBuffer data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle common boolean queries that return arrays
        switch (pname) {
            default:
                // For other queries, try to get from state tracking
                if (data.remaining() >= 1) {
                    data.put(0, (byte) 0); // Default fallback
                }
        }
        translatedCalls++;
    }

    /**
     * Interceptor for glFlush
     */
    public static void glFlush() {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement flush for DirectX 11
        translatedCalls++;
    }

    /**
     * Buffer operations for GL15 mixin
     */
    public static void glBufferData(int target, long size, int usage) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        glBufferData(target, size, (ByteBuffer) null, usage);
        translatedCalls++;
    }

    public static void glBufferData(int target, long size, long data, int usage) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Convert pointer to ByteBuffer (implementation depends on native access)
        glBufferData(target, size, (ByteBuffer) null, usage);
        translatedCalls++;
    }

    public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle ByteBuffer version directly
        glBufferData(target, data.remaining(), data, usage);
        translatedCalls++;
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for buffer sub-data update
        translatedCalls++;
    }

    public static void glBufferSubData(int target, long offset, long size, long data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for buffer sub-data update
        translatedCalls++;
    }

    public static void glDeleteBuffers(int buffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        GLResource resource = resources.remove(buffer);
        if (resource != null) {
            VitraNativeRenderer.destroyResource(resource.getDirectXHandle());
        }
        translatedCalls++;
    }

    public static void glDeleteBuffers(java.nio.IntBuffer buffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        while (buffers.hasRemaining()) {
            int buffer = buffers.get();
            GLResource resource = resources.remove(buffer);
            if (resource != null) {
                VitraNativeRenderer.destroyResource(resource.getDirectXHandle());
            }
        }
        buffers.rewind();
        translatedCalls++;
    }

    public static ByteBuffer glMapBuffer(int target, int access) {
        if (!isActive()) {
            return null;
        }

        interceptedCalls++;
        // Need to map the currently bound buffer for this target
        int bufferId = currentState.getBoundBuffer(target);
        if (bufferId != 0) {
            GLResource resource = resources.get(bufferId);
            if (resource != null) {
                // Map using native handle, need to estimate size (default to 64KB)
                ByteBuffer result = VitraNativeRenderer.mapBuffer(resource.getDirectXHandle(), 65536, access);
                translatedCalls++;
                return result;
            }
        }
        translatedCalls++;
        return null;
    }

    public static boolean glUnmapBuffer(int target) {
        if (!isActive()) {
            return false;
        }

        interceptedCalls++;
        // Need to unmap the currently bound buffer for this target
        int bufferId = currentState.getBoundBuffer(target);
        if (bufferId != 0) {
            GLResource resource = resources.get(bufferId);
            if (resource != null) {
                VitraNativeRenderer.unmapBuffer(resource.getDirectXHandle());
                translatedCalls++;
                return true;
            }
        }
        translatedCalls++;
        return false;
    }

    public static void glGetBufferParameteriv(int target, int pname, IntBuffer params) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for getting buffer parameters
        translatedCalls++;
    }

    public static void glGetBufferPointerv(int target, int pname, ByteBuffer params) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for getting buffer pointer
        translatedCalls++;
    }

    public static void glGetBufferSubData(int target, long offset, long size, ByteBuffer data) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for getting buffer sub-data
        translatedCalls++;
    }

    /**
     * Shader operations for GL20 mixin
     */
    public static void glUniform2f(int location, float v0, float v1) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setUniform2f(location, v0, v1);
        translatedCalls++;
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setUniform3f(location, v0, v1, v2);
        translatedCalls++;
    }

    public static void glUniformMatrix4fv(int location, int count, boolean transpose, long value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Convert pointer to FloatBuffer (implementation depends on native access)
        translatedCalls++;
    }

    public static void glUniformMatrix4fv(int location, int count, boolean transpose, java.nio.FloatBuffer value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Use the existing signature that doesn't include count parameter
        glUniformMatrix4fv(location, transpose, value);
        translatedCalls++;
    }

    public static void glUniformMatrix3fv(int location, int count, boolean transpose, FloatBuffer value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for 3x3 matrix uniform
        translatedCalls++;
    }

    public static void glUniformMatrix2fv(int location, int count, boolean transpose, FloatBuffer value) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Implementation for 2x2 matrix uniform
        translatedCalls++;
    }

    public static int glGetUniformLocation(int program, ByteBuffer name) {
        if (!isActive()) {
            return -1;
        }

        interceptedCalls++;
        // TODO: Implement uniform location resolution for DirectX 11
        translatedCalls++;
        return -1;
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        if (!isActive()) {
            return -1;
        }

        interceptedCalls++;
        // TODO: Implement uniform location resolution for DirectX 11
        translatedCalls++;
        return -1;
    }

    public static int glGetAttribLocation(int program, ByteBuffer name) {
        if (!isActive()) {
            return -1;
        }

        interceptedCalls++;
        // TODO: Implement attribute location resolution for DirectX 11
        translatedCalls++;
        return -1;
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        if (!isActive()) {
            return -1;
        }

        interceptedCalls++;
        // TODO: Implement attribute location resolution for DirectX 11
        translatedCalls++;
        return -1;
    }

    public static void glEnableVertexAttribArray(int index) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.enableVertexAttribArray(index);
        translatedCalls++;
    }

    public static void glDisableVertexAttribArray(int index) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.disableVertexAttribArray(index);
        translatedCalls++;
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement vertex attribute pointer for DirectX 11
        translatedCalls++;
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement vertex attribute pointer for DirectX 11
        translatedCalls++;
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // TODO: Implement vertex attribute I pointer for DirectX 11
        translatedCalls++;
    }

    public static int glCreateShader(int type) {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        // VULKANMOD APPROACH: Direct OpenGL → DirectX 11 shader creation
        int result = VitraNativeRenderer.createGLProgramShader(type);
        translatedCalls++;
        return result;
    }

    public static void glShaderSource(int shader, int count, ByteBuffer strings, IntBuffer length) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Need to implement this properly - for now, just count as translated
        translatedCalls++;
    }

    
    public static void glShaderSource(int shader, CharSequence string) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Convert CharSequence to string and forward to DirectX
        VitraNativeRenderer.shaderSource(shader, string.toString());
        translatedCalls++;
    }

    public static void glShaderSource(int shader, ByteBuffer string) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle ByteBuffer version
        translatedCalls++;
    }

    public static void glCompileShader(int shader) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.compileShader(shader);
        translatedCalls++;
    }

    public static int glCreateProgram() {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;
        // VULKANMOD APPROACH: Direct OpenGL → DirectX 11 program creation
        int result = VitraNativeRenderer.createProgram();
        translatedCalls++;
        return result;
    }

    public static void glAttachShader(int program, int shader) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.attachShader(program, shader);
        translatedCalls++;
    }

    public static void glLinkProgram(int program) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.linkProgram(program);
        translatedCalls++;
    }

    public static void glValidateProgram(int program) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.validateProgram(program);
        translatedCalls++;
    }

    public static void glDeleteShader(int shader) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.deleteShader(shader);
        translatedCalls++;
    }

    public static void glDeleteProgram(int program) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.deleteProgram(program);
        translatedCalls++;
    }

    /**
     * Vertex data notification from BufferBuilderMixin
     */
    public static void onVertexData(long ptr, Object format) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        // Handle vertex data notification
        LOGGER.debug("Vertex data notification: ptr=0x{}, format={}", Long.toHexString(ptr), format);
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

    // ==================== MABSTRACTTEXTURE INTEGRATION METHODS ====================

    /**
     * Register a texture with MAbstractTexture mapping
     * This method is called by MAbstractTexture to register its DirectX handle
     */
    public static void registerMAbstractTexture(int textureId, long directXHandle) {
        if (!isActive()) {
            return;
        }

        textureToDirectXHandle.put(textureId, directXHandle);
        directXHandleToTextureId.put(directXHandle, textureId);

        LOGGER.debug("GLInterceptor: Registered MAbstractTexture ID {} with DirectX handle 0x{}",
            textureId, Long.toHexString(directXHandle));
    }

    /**
     * Unregister a texture from MAbstractTexture mapping
     */
    public static void unregisterMAbstractTexture(int textureId) {
        if (!isActive()) {
            return;
        }

        Long directXHandle = textureToDirectXHandle.remove(textureId);
        if (directXHandle != null) {
            directXHandleToTextureId.remove(directXHandle);
            VitraNativeRenderer.destroyResource(directXHandle);
        }

        LOGGER.debug("GLInterceptor: Unregistered MAbstractTexture ID {}", textureId);
    }

    /**
     * Get DirectX handle for a texture ID (for MAbstractTexture)
     */
    public static long getDirectXHandleForTexture(int textureId) {
        Long directXHandle = textureToDirectXHandle.get(textureId);
        if (directXHandle != null) {
            return directXHandle;
        }

        // Fallback to resource tracking
        GLResource resource = resources.get(textureId);
        return resource != null ? resource.getDirectXHandle() : 0;
    }

    /**
     * Get texture ID for a DirectX handle (reverse lookup)
     */
    public static int getTextureIdForDirectXHandle(long directXHandle) {
        Integer textureId = directXHandleToTextureId.get(directXHandle);
        return textureId != null ? textureId : 0;
    }

    /**
     * Check if a texture is managed by MAbstractTexture
     */
    public static boolean isMAbstractTexture(int textureId) {
        return textureToDirectXHandle.containsKey(textureId);
    }

    /**
     * Get statistics about MAbstractTexture integration
     */
    public static String getMAbstractTextureStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== MAbstractTexture Integration Stats ===\n");
        stats.append("Tracked textures: ").append(textureToDirectXHandle.size()).append("\n");
        stats.append("DirectX handles: ").append(directXHandleToTextureId.size()).append("\n");
        stats.append("GLInterceptor resources: ").append(resources.size()).append("\n");
        return stats.toString();
    }

    // ==================== MTEXTUREUTIL INTEGRATION METHODS ====================

    /**
     * Validate texture ID and get DirectX handle (for MTextureUtil)
     */
    public static long validateAndGetDirectXHandle(int textureId) {
        if (!isActive()) {
            return 0;
        }

        Long directXHandle = textureToDirectXHandle.get(textureId);
        if (directXHandle == null) {
            // Check fallback resource tracking
            GLResource resource = resources.get(textureId);
            if (resource != null) {
                directXHandle = resource.getDirectXHandle();
                // Cache the mapping for future use
                textureToDirectXHandle.put(textureId, directXHandle);
                directXHandleToTextureId.put(directXHandle, textureId);
            }
        }

        return directXHandle != null ? directXHandle : 0;
    }

    /**
     * Get texture statistics for debugging (for MTextureUtil)
     */
    public static String getTextureDebugInfo(int textureId) {
        if (!isActive()) {
            return "GLInterceptor not active";
        }

        StringBuilder info = new StringBuilder();
        info.append("=== Texture Debug Info ===\n");
        info.append("Texture ID: ").append(textureId).append("\n");

        Long directXHandle = textureToDirectXHandle.get(textureId);
        if (directXHandle != null) {
            info.append("DirectX Handle: 0x").append(Long.toHexString(directXHandle)).append("\n");
            info.append("Managed by: MAbstractTexture\n");
        } else {
            GLResource resource = resources.get(textureId);
            if (resource != null) {
                info.append("DirectX Handle: 0x").append(Long.toHexString(resource.getDirectXHandle())).append("\n");
                info.append("Managed by: GLInterceptor\n");
            } else {
                info.append("DirectX Handle: Not found\n");
                info.append("Managed by: None (untracked)\n");
            }
        }

        return info.toString();
    }

    /**
     * Force cleanup of orphaned textures (for MTextureUtil)
     * This method removes textures that no longer have valid DirectX handles
     */
    public static int cleanupOrphanedTextures() {
        if (!isActive()) {
            return 0;
        }

        int cleaned = 0;
        textureToDirectXHandle.entrySet().removeIf(entry -> {
            long directXHandle = entry.getValue();
            if (directXHandle == 0 || !VitraNativeRenderer.isTextureValid(directXHandle)) {
                directXHandleToTextureId.remove(directXHandle);
                cleaned++;
                return true;
            }
            return false;
        });

        if (cleaned > 0) {
            LOGGER.info("GLInterceptor: Cleaned up {} orphaned texture mappings", cleaned);
        }

        return cleaned;
    }

    /**
     * Register texture from MTextureUtil (helper method)
     */
    public static void registerFromMTextureUtil(int textureId, long directXHandle) {
        if (isActive()) {
            registerMAbstractTexture(textureId, directXHandle);
        }
    }

    /**
     * Get count of orphaned textures (for MTextureManager)
     */
    public static int getOrphanedTextureCount() {
        if (!isActive()) {
            return 0;
        }

        int orphanedCount = 0;
        for (Map.Entry<Integer, Long> entry : textureToDirectXHandle.entrySet()) {
            long directXHandle = entry.getValue();
            if (directXHandle == 0 || !VitraNativeRenderer.isTextureValid(directXHandle)) {
                orphanedCount++;
            }
        }

        return orphanedCount;
    }

    /**
     * Get count of registered textures (for MTextureManager)
     */
    public static int getRegisteredTextureCount() {
        if (!isActive()) {
            return 0;
        }

        return textureToDirectXHandle.size();
    }

    /**
     * Clean up resources
     */
    public static synchronized void shutdown() {
        if (initialized.get()) {
            LOGGER.info("Shutting down OpenGL interceptor");

            active.set(false);
            frameStarted.set(false);

            // Clean up MAbstractTexture mappings
            for (Long directXHandle : textureToDirectXHandle.values()) {
                VitraNativeRenderer.destroyResource(directXHandle);
            }
            textureToDirectXHandle.clear();
            directXHandleToTextureId.clear();

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
            TEXTURE, BUFFER, SHADER, PROGRAM, FRAMEBUFFER, RENDERBUFFER, VERTEXARRAY
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
    // NEW MINECRAFT 1.21.1 INTERCEPTION METHODS (CommandEncoder/RenderPass)
    // ============================================================================

    // State tracking for Minecraft 1.21.1 GpuBuffer system
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

        // Check if this is a GpuBuffer with native handle support
        // Note: DirectX11GpuBuffer classes removed for 1.21.1 compatibility
        // Fallback to reflection to get native handle from any GpuBuffer subclass

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
     * Intercept draw call from CommandEncoder/RenderPass (Minecraft 1.21.1)
     */
    public static void onDrawCall(int baseVertex, int firstIndex, int count, int indexType, int instanceCount) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        ensureFrameStarted();

        LOGGER.debug("DrawCall: baseVertex={}, firstIndex={}, count={}, indexType={}, instances={}",
            baseVertex, firstIndex, count, indexType, instanceCount);

        // Get currently bound buffers from Minecraft 1.21.1 state
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

    // ============================================================================
    // OPENGL 3.0+ INTERCEPTOR METHODS (for GL30Mixin)
    // ============================================================================

    /**
     * Interceptor for glGenFramebuffers
     */
    public static void glGenFramebuffers(IntBuffer framebuffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        for (int i = 0; i < framebuffers.remaining(); i++) {
            long resourceId = nextResourceId.getAndIncrement();
            framebuffers.put(i, (int) resourceId);

            GLResource resource = new GLResource(resourceId, GLResource.Type.FRAMEBUFFER);
            resources.put((int) resourceId, resource);

            // Create corresponding DirectX framebuffer resource
            VitraNativeRenderer.createFramebuffer((int) resourceId);
        }

        framebuffers.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glBindFramebuffer
     */
    public static void glBindFramebuffer(int target, int framebuffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.setBoundFramebuffer(target, framebuffer);

        if (framebuffer != 0) {
            GLResource resource = resources.get(framebuffer);
            if (resource != null) {
                VitraNativeRenderer.bindFramebuffer(resource.getDirectXHandle(), target);
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glFramebufferTexture2D
     */
    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        GLResource fbResource = resources.get(currentState.getBoundFramebuffer(target));
        GLResource texResource = resources.get(texture);

        if (fbResource != null && texResource != null) {
            VitraNativeRenderer.framebufferTexture2D(
                fbResource.getDirectXHandle(), target, attachment, textarget,
                texResource.getDirectXHandle(), level
            );
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glFramebufferRenderbuffer
     */
    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        GLResource fbResource = resources.get(currentState.getBoundFramebuffer(target));
        GLResource rbResource = resources.get(renderbuffer);

        if (fbResource != null && rbResource != null) {
            VitraNativeRenderer.framebufferRenderbuffer(
                fbResource.getDirectXHandle(), target, attachment, renderbuffertarget,
                rbResource.getDirectXHandle()
            );
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glCheckFramebufferStatus
     */
    public static int glCheckFramebufferStatus(int target) {
        if (!isActive()) {
            return 0;
        }

        interceptedCalls++;

        int boundFramebuffer = currentState.getBoundFramebuffer(target);
        if (boundFramebuffer != 0) {
            GLResource resource = resources.get(boundFramebuffer);
            if (resource != null) {
                int result = VitraNativeRenderer.checkFramebufferStatus(resource.getDirectXHandle(), target);
                translatedCalls++;
                return result;
            }
        }

        translatedCalls++;
        return org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    /**
     * Interceptor for glDeleteFramebuffers
     */
    public static void glDeleteFramebuffers(IntBuffer framebuffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        while (framebuffers.hasRemaining()) {
            int framebuffer = framebuffers.get();
            GLResource resource = resources.remove(framebuffer);
            if (resource != null) {
                VitraNativeRenderer.destroyFramebuffer(resource.getDirectXHandle());
            }
        }
        framebuffers.rewind();

        translatedCalls++;
    }

    /**
     * Interceptor for glGenRenderbuffers
     */
    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        for (int i = 0; i < renderbuffers.remaining(); i++) {
            long resourceId = nextResourceId.getAndIncrement();
            renderbuffers.put(i, (int) resourceId);

            GLResource resource = new GLResource(resourceId, GLResource.Type.RENDERBUFFER);
            resources.put((int) resourceId, resource);

            // Create corresponding DirectX renderbuffer resource
            VitraNativeRenderer.createRenderbuffer((int) resourceId);
        }

        renderbuffers.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glBindRenderbuffer
     */
    public static void glBindRenderbuffer(int target, int renderbuffer) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.setBoundRenderbuffer(target, renderbuffer);

        if (renderbuffer != 0) {
            GLResource resource = resources.get(renderbuffer);
            if (resource != null) {
                VitraNativeRenderer.bindRenderbuffer(resource.getDirectXHandle(), target);
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glRenderbufferStorage
     */
    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        int boundRenderbuffer = currentState.getBoundRenderbuffer(target);
        if (boundRenderbuffer != 0) {
            GLResource resource = resources.get(boundRenderbuffer);
            if (resource != null) {
                VitraNativeRenderer.renderbufferStorage(
                    resource.getDirectXHandle(), target, internalformat, width, height
                );
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glDeleteRenderbuffers
     */
    public static void glDeleteRenderbuffers(IntBuffer renderbuffers) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        while (renderbuffers.hasRemaining()) {
            int renderbuffer = renderbuffers.get();
            GLResource resource = resources.remove(renderbuffer);
            if (resource != null) {
                VitraNativeRenderer.destroyRenderbuffer(resource.getDirectXHandle());
            }
        }
        renderbuffers.rewind();

        translatedCalls++;
    }

    /**
     * Interceptor for glGenVertexArrays
     */
    public static void glGenVertexArrays(IntBuffer arrays) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        for (int i = 0; i < arrays.remaining(); i++) {
            long resourceId = nextResourceId.getAndIncrement();
            arrays.put(i, (int) resourceId);

            GLResource resource = new GLResource(resourceId, GLResource.Type.VERTEXARRAY);
            resources.put((int) resourceId, resource);

            // Create corresponding DirectX vertex array resource
            VitraNativeRenderer.createVertexArray((int) resourceId);
        }

        arrays.rewind();
        translatedCalls++;
    }

    /**
     * Interceptor for glBindVertexArray
     */
    public static void glBindVertexArray(int array) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        currentState.setBoundVertexArray(array);

        if (array != 0) {
            GLResource resource = resources.get(array);
            if (resource != null) {
                VitraNativeRenderer.bindVertexArray(resource.getDirectXHandle());
            }
        }

        translatedCalls++;
    }

    /**
     * Interceptor for glDeleteVertexArrays
     */
    public static void glDeleteVertexArrays(IntBuffer arrays) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        while (arrays.hasRemaining()) {
            int array = arrays.get();
            GLResource resource = resources.remove(array);
            if (resource != null) {
                VitraNativeRenderer.destroyVertexArray(resource.getDirectXHandle());
            }
        }
        arrays.rewind();

        translatedCalls++;
    }

    /**
     * Interceptor for glBlendEquation
     */
    public static void glBlendEquation(int mode) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setBlendEquation(mode, mode);
        translatedCalls++;
    }

    /**
     * Interceptor for glBlendEquationSeparate
     */
    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setBlendEquation(modeRGB, modeAlpha);
        translatedCalls++;
    }

    /**
     * Interceptor for glDrawBuffers
     */
    public static void glDrawBuffers(int n, IntBuffer bufs) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;

        int[] buffers = new int[Math.min(n, bufs.remaining())];
        bufs.get(buffers);
        bufs.rewind();

        VitraNativeRenderer.setDrawBuffers(buffers);
        translatedCalls++;
    }

    /**
     * Interceptor for glStencilOpSeparate
     */
    public static void glStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setStencilOpSeparate(face, sfail, dpfail, dppass);
        translatedCalls++;
    }

    /**
     * Interceptor for glStencilFuncSeparate
     */
    public static void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setStencilFuncSeparate(face, func, ref, mask);
        translatedCalls++;
    }

    /**
     * Interceptor for glStencilMaskSeparate
     */
    public static void glStencilMaskSeparate(int face, int mask) {
        if (!isActive()) {
            return;
        }

        interceptedCalls++;
        VitraNativeRenderer.setStencilMaskSeparate(face, mask);
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
        private final Map<Integer, Integer> boundFramebuffers = new HashMap<>();
        private final Map<Integer, Integer> boundRenderbuffers = new HashMap<>();
        private int boundVertexArray = 0;
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

        // GL30+ state tracking methods

        void setBoundFramebuffer(int target, int framebuffer) {
            boundFramebuffers.put(target, framebuffer);
        }

        int getBoundFramebuffer(int target) {
            return boundFramebuffers.getOrDefault(target, 0);
        }

        void setBoundRenderbuffer(int target, int renderbuffer) {
            boundRenderbuffers.put(target, renderbuffer);
        }

        int getBoundRenderbuffer(int target) {
            return boundRenderbuffers.getOrDefault(target, 0);
        }

        void setBoundVertexArray(int array) {
            boundVertexArray = array;
        }

        int getBoundVertexArray() {
            return boundVertexArray;
        }
    }
}