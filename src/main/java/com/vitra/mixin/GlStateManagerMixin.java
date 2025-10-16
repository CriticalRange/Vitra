package com.vitra.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.vitra.VitraMod;
import com.vitra.render.jni.JniUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Critical Mixin: OpenGL State Management → DirectX 11 JNI State Tracking
 *
 * This mixin intercepts ALL OpenGL state changes from GlStateManager and forwards them
 * to the DirectX 11 JNI system. DirectX 11 uses a different state model than OpenGL:
 *
 * OpenGL Model:
 * - Global state machine
 * - glEnable/glDisable changes affect all subsequent draw calls
 * - State persists until explicitly changed
 *
 * DirectX 11 JNI Model:
 * - State is managed through native DirectX 11 calls
 * - State changes must be explicitly forwarded to native layer
 * - Uses immediate mode state management
 *
 * Strategy:
 * 1. Intercept all OpenGL state changes via @Overwrite
 * 2. Forward state changes to DirectX 11 JNI system
 * 3. Track state locally for debugging and synchronization
 * 4. Use @Overwrite to COMPLETELY REPLACE OpenGL calls (VulkanMod strategy)
 *
 * CRITICAL: @Overwrite ensures NO OpenGL code executes at all
 * - More reliable than @Inject + cancel
 * - No dependency on injection point timing
 * - Guarantees OpenGL DLL is never called
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlStateManagerMixin");

    // State tracking for DirectX 11 synchronization and debugging
    private static boolean blendEnabled = false;
    private static boolean depthTestEnabled = true; // Default OpenGL state
    private static boolean cullEnabled = false;
    private static int depthFunc = 513; // GL_LESS by default
    private static boolean depthMask = true;
    private static boolean colorMaskRed = true;
    private static boolean colorMaskGreen = true;
    private static boolean colorMaskBlue = true;
    private static boolean colorMaskAlpha = true;

    /**
     * Get current state for debugging and synchronization
     */
    public static boolean isBlendEnabled() { return blendEnabled; }
    public static boolean isDepthTestEnabled() { return depthTestEnabled; }
    public static boolean isCullEnabled() { return cullEnabled; }
    public static int getDepthFunc() { return depthFunc; }
    public static boolean isDepthMask() { return depthMask; }
    public static boolean[] getColorMask() { return new boolean[]{colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha}; }

    /**
     * Initialize DirectX 11 state to match current OpenGL state
     * Called when DirectX 11 is initialized to synchronize states
     */
    public static void synchronizeDirectXState() {
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            LOGGER.info("Synchronizing DirectX 11 state with current OpenGL state");
            LOGGER.info("Blend: {}, Depth: {}, Cull: {}, DepthFunc: {}, DepthMask: {}, ColorMask: [{}, {}, {}, {}]",
                blendEnabled, depthTestEnabled, cullEnabled, depthFunc, depthMask,
                colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);

            try {
                JniUtils.enableBlend(blendEnabled);
                JniUtils.enableDepthTest(depthTestEnabled);
                JniUtils.enableCull(cullEnabled);
                JniUtils.setDepthFunc(depthFunc);
                JniUtils.setDepthMask(depthMask);
                JniUtils.setColorMask(colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);
                LOGGER.info("DirectX 11 state synchronized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to synchronize DirectX 11 state", e);
            }
        }
    }

    // ============================================================================
    // DEPTH TEST STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        if (!depthTestEnabled) {
            depthTestEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_DEPTH_TEST) -> DirectX 11: Enable depth test");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableDepthTest(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable depth test in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        if (depthTestEnabled) {
            depthTestEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_DEPTH_TEST) -> DirectX 11: Disable depth test");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableDepthTest(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable depth test in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth func with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int func) {
        if (depthFunc != func) {
            depthFunc = func;
            LOGGER.trace("OpenGL: glDepthFunc(0x{}) -> DirectX 11: Set depth function", Integer.toHexString(func));

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setDepthFunc(func);
                } catch (Exception e) {
                    LOGGER.error("Failed to set depth function in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth mask with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean flag) {
        if (depthMask != flag) {
            depthMask = flag;
            LOGGER.trace("OpenGL: glDepthMask({}) -> DirectX 11: Set depth write mask", flag);

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setDepthMask(flag);
                } catch (Exception e) {
                    LOGGER.error("Failed to set depth mask in DirectX 11", e);
                }
            }
        }
    }

    // ============================================================================
    // BLENDING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        if (!blendEnabled) {
            blendEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_BLEND) -> DirectX 11: Enable blending");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableBlend(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable blending in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        if (blendEnabled) {
            blendEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_BLEND) -> DirectX 11: Disable blending");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableBlend(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable blending in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend func with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        LOGGER.trace("OpenGL: glBlendFuncSeparate(0x{}, 0x{}, 0x{}, 0x{}) -> DirectX 11: Set blend factors",
            Integer.toHexString(srcRGB), Integer.toHexString(dstRGB),
            Integer.toHexString(srcAlpha), Integer.toHexString(dstAlpha));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setBlendFunc(srcRGB, dstRGB, srcAlpha, dstAlpha);
            } catch (Exception e) {
                LOGGER.error("Failed to set blend function in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // CULLING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        if (!cullEnabled) {
            cullEnabled = true;
            LOGGER.trace("OpenGL: glEnable(GL_CULL_FACE) -> DirectX 11: Enable face culling");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableCull(true);
                } catch (Exception e) {
                    LOGGER.error("Failed to enable face culling in DirectX 11", e);
                }
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        if (cullEnabled) {
            cullEnabled = false;
            LOGGER.trace("OpenGL: glDisable(GL_CULL_FACE) -> DirectX 11: Disable face culling");

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.enableCull(false);
                } catch (Exception e) {
                    LOGGER.error("Failed to disable face culling in DirectX 11", e);
                }
            }
        }
    }

    // ============================================================================
    // COLOR MASK STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL color mask with DirectX 11 JNI
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        // Only forward if state actually changed
        if (colorMaskRed != red || colorMaskGreen != green || colorMaskBlue != blue || colorMaskAlpha != alpha) {
            colorMaskRed = red;
            colorMaskGreen = green;
            colorMaskBlue = blue;
            colorMaskAlpha = alpha;
            LOGGER.trace("OpenGL: glColorMask({}, {}, {}, {}) -> DirectX 11: Set color mask", red, green, blue, alpha);

            // Forward to DirectX 11 JNI system if available
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                try {
                    JniUtils.setColorMask(red, green, blue, alpha);
                } catch (Exception e) {
                    LOGGER.error("Failed to set color mask in DirectX 11", e);
                }
            }
        }
    }

    // ============================================================================
    // VIEWPORT AND SCISSOR (These are view-level operations in DirectX 11)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL viewport with DirectX 11 viewport
     */
    @Overwrite(remap = false)
    public static void _viewport(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glViewport({}, {}, {}, {}) -> DirectX 11: Set viewport", x, y, width, height);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setViewport(x, y, width, height);
            } catch (Exception e) {
                LOGGER.error("Failed to set viewport in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with DirectX 11 scissor
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        LOGGER.trace("OpenGL: glEnable(GL_SCISSOR_TEST) -> DirectX 11: Enable scissor test");

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.enableScissorTest(true);
            } catch (Exception e) {
                LOGGER.error("Failed to enable scissor test in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with DirectX 11 scissor
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        LOGGER.trace("OpenGL: glDisable(GL_SCISSOR_TEST) -> DirectX 11: Disable scissor test");

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.enableScissorTest(false);
            } catch (Exception e) {
                LOGGER.error("Failed to disable scissor test in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor box with DirectX 11 scissor rect
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glScissor({}, {}, {}, {}) -> DirectX 11: Set scissor rect", x, y, width, height);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.setScissorRect(x, y, width, height);
            } catch (Exception e) {
                LOGGER.error("Failed to set scissor rect in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // CLEAR OPERATIONS (These are view-level operations in DirectX 11)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL clear with DirectX 11 clear
     *
     * Minecraft 1.21.1 signature: _clear(int mask) - one parameter
     */
    @Overwrite(remap = false)
    public static void _clear(int mask) {
        LOGGER.trace("OpenGL: glClear(0x{}) -> DirectX 11: Clear render target", Integer.toHexString(mask));

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.clear(mask);
            } catch (Exception e) {
                LOGGER.error("Failed to clear render target in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // BUFFER OPERATIONS - SIMPLIFIED FOR DIRECTX 11 JNI
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer generation - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        LOGGER.trace("OpenGL: glGenBuffers() - BLOCKED, DirectX 11 handles buffers");
        // Return fake buffer ID - DirectX 11 JNI handles actual buffer creation
        return 999; // Fake ID, won't be used
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer binding - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int target, int buffer) {
        LOGGER.trace("OpenGL: glBindBuffer(target=0x{}, buffer={}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), buffer);
        // No-op: DirectX 11 JNI handles buffer binding internally
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), data != null ? data.remaining() : 0, Integer.toHexString(usage));
        // No-op: DirectX 11 JNI handles buffer data
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data (long size variant) - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, long size, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED, DirectX 11 handles buffers",
            Integer.toHexString(target), size, Integer.toHexString(usage));
        // No-op: DirectX 11 JNI handles buffer data
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer deletion - DirectX 11 handles buffers
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int buffer) {
        LOGGER.trace("OpenGL: glDeleteBuffers({}) - BLOCKED, DirectX 11 handles buffers", buffer);
        // No-op: DirectX 11 JNI handles buffer cleanup
    }

    // ============================================================================
    // VERTEX ARRAY OPERATIONS - SIMPLIFIED FOR DIRECTX 11 JNI
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO generation - DirectX 11 handles vertex arrays
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        LOGGER.trace("OpenGL: glGenVertexArrays() - BLOCKED, DirectX 11 handles vertex arrays");
        // Return fake VAO ID - DirectX 11 JNI doesn't use VAOs
        return 888; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO binding - DirectX 11 handles vertex arrays
     */
    @Overwrite(remap = false)
    public static void _glBindVertexArray(int array) {
        LOGGER.trace("OpenGL: glBindVertexArray({}) - BLOCKED, DirectX 11 handles vertex arrays", array);
        // No-op: DirectX 11 JNI doesn't use VAOs
    }

    // NOTE: _glDeleteVertexArrays doesn't exist in 1.21.1 GlStateManager
    // NOTE: _disableVertexAttribArray doesn't exist in 1.21.1 GlStateManager

    // ============================================================================
    // DRAWING OPERATIONS - CRITICAL FOR RENDERING
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL draw arrays - DirectX 11 JNI draw calls
     */
    @Overwrite(remap = false)
    public static void _drawArrays(int mode, int first, int count) {
        LOGGER.trace("OpenGL: glDrawArrays(mode=0x{}, first={}, count={}) -> DirectX 11: Draw arrays",
            Integer.toHexString(mode), first, count);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.drawArrays(mode, first, count);
            } catch (Exception e) {
                LOGGER.error("Failed to draw arrays in DirectX 11", e);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL draw elements - DirectX 11 JNI draw calls
     */
    @Overwrite(remap = false)
    public static void _drawElements(int mode, int count, int type, long indices) {
        LOGGER.trace("OpenGL: glDrawElements(mode=0x{}, count={}, type=0x{}, indices={}) -> DirectX 11: Draw elements",
            Integer.toHexString(mode), count, Integer.toHexString(type), indices);

        // Forward to DirectX 11 JNI system if available
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                JniUtils.drawElements(mode, count, type, indices);
            } catch (Exception e) {
                LOGGER.error("Failed to draw elements in DirectX 11", e);
            }
        }
    }

    // ============================================================================
    // SIMPLIFIED OPERATIONS - BLOCKED FOR DIRECTX 11 JNI
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture operations - DirectX 11 handles textures
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        LOGGER.trace("OpenGL: glGenTextures() - BLOCKED, DirectX 11 handles textures");
        return 1; // Fake texture ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture binding - DirectX 11 handles textures
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int texture) {
        LOGGER.trace("OpenGL: glBindTexture({}) - BLOCKED, DirectX 11 handles textures", texture);
        // No-op: DirectX 11 JNI handles textures
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture deletion - DirectX 11 handles textures
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int texture) {
        LOGGER.trace("OpenGL: glDeleteTexture({}) - BLOCKED, DirectX 11 handles textures", texture);
        // No-op: DirectX 11 JNI handles texture cleanup
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program usage - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int program) {
        LOGGER.trace("OpenGL: glUseProgram({}) - BLOCKED, DirectX 11 handles shaders", program);
        // No-op: DirectX 11 JNI handles shader programs
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL error handling - DirectX 11 has no GL errors
     */
    @Overwrite(remap = false)
    public static int _getError() {
        // Always return GL_NO_ERROR (0) - DirectX 11 has different error handling
        return 0;
    }

    // Additional critical methods that should be blocked/simplified:
    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program creation - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        LOGGER.trace("OpenGL: glCreateProgram() - BLOCKED, DirectX 11 handles shaders");
        return 1; // Fake program ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program deletion - DirectX 11 handles shaders
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int program) {
        LOGGER.trace("OpenGL: glDeleteProgram({}) - BLOCKED, DirectX 11 handles shaders", program);
        // No-op: DirectX 11 JNI handles shader cleanup
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer generation - DirectX 11 handles framebuffers
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        LOGGER.trace("OpenGL: glGenFramebuffers() - BLOCKED, DirectX 11 handles framebuffers");
        return 1; // Fake framebuffer ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer binding - DirectX 11 handles framebuffers
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int target, int framebuffer) {
        LOGGER.trace("OpenGL: glBindFramebuffer(target=0x{}, framebuffer={}) - BLOCKED, DirectX 11 handles framebuffers",
            Integer.toHexString(target), framebuffer);
        // No-op: DirectX 11 JNI handles framebuffers
    }
}
