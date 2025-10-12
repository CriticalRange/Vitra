package com.vitra.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.vitra.render.backend.BgfxGlTexture;
import com.vitra.render.bgfx.BgfxStateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Critical Mixin: OpenGL State Management → BGFX State Tracking
 *
 * This mixin intercepts ALL OpenGL state changes from GlStateManager and tracks them
 * for BGFX rendering. BGFX uses a different state model than OpenGL:
 *
 * OpenGL Model:
 * - Global state machine
 * - glEnable/glDisable changes affect all subsequent draw calls
 * - State persists until explicitly changed
 *
 * BGFX Model:
 * - State is set per draw call
 * - bgfx_set_state() must be called before each draw
 * - No global state machine
 *
 * Strategy:
 * 1. Track all OpenGL state changes in BgfxStateTracker
 * 2. Build BGFX state flags from tracked state
 * 3. Apply state before BGFX draw calls (done in rendering code)
 * 4. Use @Overwrite to COMPLETELY REPLACE OpenGL calls (VulkanMod strategy)
 *
 * CRITICAL: @Overwrite ensures NO OpenGL code executes at all
 * - More reliable than @Inject + cancel
 * - No dependency on injection point timing
 * - Guarantees nvoglv64.dll is never called
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GlStateManagerMixin");
    private static final BgfxStateTracker stateTracker = new BgfxStateTracker();

    /**
     * Accessor for the state tracker - used by rendering code to get current BGFX state.
     */
    public static BgfxStateTracker getStateTracker() {
        return stateTracker;
    }

    // ============================================================================
    // DEPTH TEST STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        LOGGER.trace("OpenGL: glEnable(GL_DEPTH_TEST) - BLOCKED, tracking for BGFX");
        stateTracker.setDepthTestEnabled(true);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth test with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        LOGGER.trace("OpenGL: glDisable(GL_DEPTH_TEST) - BLOCKED, tracking for BGFX");
        stateTracker.setDepthTestEnabled(false);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth func with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int func) {
        LOGGER.trace("OpenGL: glDepthFunc(0x{}) - BLOCKED, tracking for BGFX", Integer.toHexString(func));
        stateTracker.setDepthFunc(func);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL depth mask with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean flag) {
        LOGGER.trace("OpenGL: glDepthMask({}) - BLOCKED, tracking for BGFX", flag);
        stateTracker.setDepthWriteEnabled(flag);
    }

    // ============================================================================
    // BLENDING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        LOGGER.trace("OpenGL: glEnable(GL_BLEND) - BLOCKED, tracking for BGFX");
        stateTracker.setBlendEnabled(true);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        LOGGER.trace("OpenGL: glDisable(GL_BLEND) - BLOCKED, tracking for BGFX");
        stateTracker.setBlendEnabled(false);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL blend func with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        LOGGER.trace("OpenGL: glBlendFuncSeparate(0x{}, 0x{}, 0x{}, 0x{}) - BLOCKED, tracking for BGFX",
            Integer.toHexString(srcRGB), Integer.toHexString(dstRGB),
            Integer.toHexString(srcAlpha), Integer.toHexString(dstAlpha));
        stateTracker.setBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    // ============================================================================
    // CULLING STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        LOGGER.trace("OpenGL: glEnable(GL_CULL_FACE) - BLOCKED, tracking for BGFX");
        stateTracker.setCullFaceEnabled(true);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL cull face with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        LOGGER.trace("OpenGL: glDisable(GL_CULL_FACE) - BLOCKED, tracking for BGFX");
        stateTracker.setCullFaceEnabled(false);
    }

    // ============================================================================
    // COLOR MASK STATE
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL color mask with BGFX state tracking
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        LOGGER.trace("OpenGL: glColorMask({}, {}, {}, {}) - BLOCKED, tracking for BGFX", red, green, blue, alpha);
        stateTracker.setColorMask(red, green, blue, alpha);
    }

    // ============================================================================
    // VIEWPORT AND SCISSOR (These are view-level operations in BGFX)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL viewport with BGFX view rect
     */
    @Overwrite(remap = false)
    public static void _viewport(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glViewport({}, {}, {}, {}) - BLOCKED, handled by BGFX", x, y, width, height);
        // Viewport is handled at view level in BGFX via bgfx_set_view_rect()
        // No state tracking needed here
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with BGFX scissor
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        LOGGER.trace("OpenGL: glEnable(GL_SCISSOR_TEST) - BLOCKED, handled by BGFX");
        // Scissor is handled separately in BGFX via bgfx_set_scissor()
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor test with BGFX scissor
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        LOGGER.trace("OpenGL: glDisable(GL_SCISSOR_TEST) - BLOCKED, handled by BGFX");
        // Scissor is handled separately in BGFX
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL scissor box with BGFX scissor
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        LOGGER.trace("OpenGL: glScissor({}, {}, {}, {}) - BLOCKED, handled by BGFX", x, y, width, height);
        // TODO: Track scissor rect and apply with bgfx_set_scissor() before draws
    }

    // ============================================================================
    // CLEAR OPERATIONS (These are view-level operations in BGFX)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL clear with BGFX clear
     *
     * Minecraft 1.21.8 signature: _clear(int mask) - one parameter
     */
    @Overwrite(remap = false)
    public static void _clear(int mask) {
        LOGGER.trace("OpenGL: glClear(0x{}) - BLOCKED, handled by BGFX", Integer.toHexString(mask));
        // Clear is handled at view level in BGFX
        // See VitraRenderer initialization where bgfx_set_view_clear() is called
    }

    // NOTE: _clearColor and _clearDepth don't exist in 1.21.8 GlStateManager
    // These are handled at a higher level

    // ============================================================================
    // BUFFER OPERATIONS - CRITICAL FOR PREVENTING ACCESS_VIOLATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer generation - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        LOGGER.trace("OpenGL: glGenBuffers() - BLOCKED, returning fake ID");
        // Return fake buffer ID - actual buffers are created via GpuDevice.createBuffer()
        return 999; // Fake ID, won't be used
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer binding - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int target, int buffer) {
        LOGGER.trace("OpenGL: glBindBuffer(target=0x{}, buffer={}) - BLOCKED",
            Integer.toHexString(target), buffer);
        // No-op: BGFX handles buffer binding internally
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, java.nio.ByteBuffer data, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED",
            Integer.toHexString(target), data != null ? data.remaining() : 0, Integer.toHexString(usage));
        // No-op: BGFX handles buffer data via BgfxBuffer
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer data (long size variant) - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, long size, int usage) {
        LOGGER.trace("OpenGL: glBufferData(target=0x{}, size={}, usage=0x{}) - BLOCKED",
            Integer.toHexString(target), size, Integer.toHexString(usage));
        // No-op: BGFX handles buffer data via BgfxBuffer
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL buffer deletion - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int buffer) {
        LOGGER.trace("OpenGL: glDeleteBuffers({}) - BLOCKED", buffer);
        // No-op: BGFX handles buffer cleanup
    }

    // ============================================================================
    // VERTEX ARRAY OPERATIONS - CRITICAL FOR PREVENTING ACCESS_VIOLATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO generation - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        LOGGER.trace("OpenGL: glGenVertexArrays() - BLOCKED, returning fake ID");
        // Return fake VAO ID - BGFX doesn't use VAOs
        return 888; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL VAO binding - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBindVertexArray(int array) {
        LOGGER.trace("OpenGL: glBindVertexArray({}) - BLOCKED", array);
        // No-op: BGFX doesn't use VAOs
    }

    // NOTE: _glDeleteVertexArrays doesn't exist in 1.21.8 GlStateManager
    // NOTE: _disableVertexAttribArray doesn't exist in 1.21.8 GlStateManager

    // ============================================================================
    // TEXTURE OPERATIONS - CRITICAL FOR PREVENTING ACCESS_VIOLATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture generation - BGFX texture manager
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        int id = BgfxGlTexture.genTextureId();
        LOGGER.info("[TEXTURE TRACE] OpenGL: glGenTextures() - Managed by BGFX, returned ID {}", id);
        return id;
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture binding - BGFX texture manager
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int texture) {
        LOGGER.info("[TEXTURE TRACE] OpenGL: glBindTexture({}) - Managed by BGFX", texture);
        BgfxGlTexture.bindTexture(texture);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture deletion - BGFX texture manager
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int texture) {
        LOGGER.trace("OpenGL: glDeleteTexture({}) - Managed by BGFX", texture);
        BgfxGlTexture.deleteTexture(texture);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL active texture - BGFX texture manager
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int texture) {
        LOGGER.trace("OpenGL: glActiveTexture(0x{}) - Managed by BGFX", Integer.toHexString(texture));
        BgfxGlTexture.activeTexture(texture);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texture parameter - BGFX texture manager
     */
    @Overwrite(remap = false)
    public static void _texParameter(int target, int pname, int param) {
        LOGGER.trace("OpenGL: glTexParameteri(target=0x{}, pname=0x{}, param=0x{}) - Managed by BGFX",
            Integer.toHexString(target), Integer.toHexString(pname), Integer.toHexString(param));
        BgfxGlTexture.texParameter(target, pname, param);
    }

    // NOTE: _texParameter(int, int, float) doesn't exist in 1.21.8 GlStateManager
    // Only int variant exists

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texImage2D - BGFX texture upload
     *
     * CRITICAL: This is called during font loading to upload texture data.
     * Now actually uploads to BGFX via BgfxGlTexture manager.
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalFormat, int width, int height,
                                   int border, int format, int type, java.nio.IntBuffer pixels) {
        LOGGER.info("[TEXTURE TRACE] OpenGL: glTexImage2D(target=0x{}, level={}, format=0x{}, size={}x{}) - Uploading to BGFX",
            Integer.toHexString(target), level, Integer.toHexString(internalFormat), width, height);
        BgfxGlTexture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL texSubImage2D - BGFX texture upload
     *
     * CRITICAL: This is called to update texture regions (font glyphs, etc.)
     * Now actually uploads to BGFX via BgfxGlTexture manager.
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int offsetX, int offsetY,
                                     int width, int height, int format, int type, long pixels) {
        LOGGER.debug("OpenGL: glTexSubImage2D(target=0x{}, level={}, offset=({},{}), size={}x{}) - Uploading to BGFX",
            Integer.toHexString(target), level, offsetX, offsetY, width, height);
        BgfxGlTexture.texSubImage2D(target, level, offsetX, offsetY, width, height, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL pixelStore - BGFX texture manager
     *
     * CRITICAL: This sets pixel alignment for texture uploads.
     * Now tracked by BgfxGlTexture for correct texture upload.
     */
    @Overwrite(remap = false)
    public static void _pixelStore(int pname, int param) {
        LOGGER.trace("OpenGL: glPixelStorei(pname=0x{}, param={}) - Managed by BGFX",
            Integer.toHexString(pname), param);
        BgfxGlTexture.pixelStore(pname, param);
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL getTexLevelParameter - BGFX texture manager
     *
     * CRITICAL: This queries texture properties (width, height, format, etc.)
     * Now returns actual values from BGFX texture manager.
     */
    @Overwrite(remap = false)
    public static int _getTexLevelParameter(int target, int level, int pname) {
        int value = BgfxGlTexture.getTexLevelParameter(target, level, pname);
        LOGGER.trace("OpenGL: glGetTexLevelParameteriv(target=0x{}, level={}, pname=0x{}) - Managed by BGFX, returning {}",
            Integer.toHexString(target), level, Integer.toHexString(pname), value);
        return value;
    }

    // ============================================================================
    // PROGRAM/SHADER OPERATIONS - CRITICAL FOR PREVENTING ACCESS_VIOLATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program usage - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int program) {
        LOGGER.trace("OpenGL: glUseProgram({}) - BLOCKED", program);
        // No-op: BGFX uses program handles set in draw calls
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program creation - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        LOGGER.trace("OpenGL: glCreateProgram() - BLOCKED, returning fake ID");
        // Return fake program ID
        return 666; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL program deletion - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int program) {
        LOGGER.trace("OpenGL: glDeleteProgram({}) - BLOCKED", program);
        // No-op: BGFX handles program cleanup
    }

    // ============================================================================
    // ERROR HANDLING - Return success always
    // ============================================================================

    /**
     * @author Vitra
     * @reason Always return GL_NO_ERROR - BGFX has no errors
     */
    @Overwrite(remap = false)
    public static int _getError() {
        // Always return GL_NO_ERROR (0)
        return 0;
    }

    // ============================================================================
    // FRAMEBUFFER OPERATIONS - CRITICAL FOR PREVENTING ACCESS_VIOLATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer generation - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        LOGGER.trace("OpenGL: glGenFramebuffers() - BLOCKED, returning fake ID");
        return 555; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer binding - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int target, int framebuffer) {
        LOGGER.trace("OpenGL: glBindFramebuffer(target=0x{}, framebuffer={}) - BLOCKED",
            Integer.toHexString(target), framebuffer);
        // No-op: BGFX handles framebuffers
    }

    // NOTE: glCheckFramebufferStatus doesn't exist in 1.21.8 GlStateManager
    // It's been moved to a different class or removed

    // ============================================================================
    // RENDERBUFFER OPERATIONS - CRITICAL FOR FBO/TEXTURE ATTACHMENTS
    // ============================================================================

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL renderbuffer generation - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static int glGenRenderbuffers() {
        LOGGER.trace("OpenGL: glGenRenderbuffers() - BLOCKED, returning fake ID");
        return 444; // Fake ID
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL renderbuffer binding - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glBindRenderbuffer(int target, int renderbuffer) {
        LOGGER.trace("OpenGL: glBindRenderbuffer(target=0x{}, renderbuffer={}) - BLOCKED",
            Integer.toHexString(target), renderbuffer);
        // No-op: BGFX handles renderbuffers internally
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL framebuffer renderbuffer attachment - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        LOGGER.trace("OpenGL: glFramebufferRenderbuffer(target=0x{}, attachment=0x{}, renderbuffer={}) - BLOCKED",
            Integer.toHexString(target), Integer.toHexString(attachment), renderbuffer);
        // No-op: BGFX handles framebuffer attachments
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL renderbuffer storage - PREVENTS CRASH
     */
    @Overwrite(remap = false)
    public static void _glRenderbufferStorage(int target, int internalformat, int width, int height) {
        LOGGER.trace("OpenGL: glRenderbufferStorage(target=0x{}, format=0x{}, size={}x{}) - BLOCKED",
            Integer.toHexString(target), Integer.toHexString(internalformat), width, height);
        // No-op: BGFX handles renderbuffer storage
    }
}
