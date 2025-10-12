package com.vitra.mixin;

import com.vitra.render.backend.BgfxGlTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * CRITICAL Mixin: Intercepts LWJGL GL11 calls directly
 *
 * This is ESSENTIAL because Minecraft/fonts sometimes call LWJGL GL11 methods DIRECTLY,
 * bypassing GlStateManager entirely. Without this mixin, those calls would reach
 * the native OpenGL driver (nvoglv64.dll) and cause ACCESS_VIOLATION crashes.
 *
 * VulkanMod's approach: Intercept at the LWJGL level to catch ALL OpenGL calls.
 *
 * Based on: VulkanMod's GL11M.java
 */
@Mixin(value = GL11.class, remap = false)
public class LWJGLGL11Mixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LWJGLGL11Mixin");

    // ============================================================================
    // TEXTURE GENERATION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glGenTextures() - Called directly by font loading
     */
    @NativeType("void")
    @Overwrite
    public static int glGenTextures() {
        int id = BgfxGlTexture.genTextureId();
        LOGGER.info("LWJGL: GL11.glGenTextures() - Generated texture ID {}", id);
        return id;
    }

    // ============================================================================
    // TEXTURE BINDING
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glBindTexture() - Called directly by font loading
     */
    @Overwrite
    public static void glBindTexture(@NativeType("GLenum") int target, @NativeType("GLuint") int texture) {
        LOGGER.info("LWJGL: GL11.glBindTexture(target=0x{}, texture={})", Integer.toHexString(target), texture);
        BgfxGlTexture.bindTexture(texture);
    }

    // ============================================================================
    // TEXTURE IMAGE UPLOAD (Full texture)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexImage2D() ByteBuffer variant - Called directly by font loading
     */
    @Overwrite
    public static void glTexImage2D(int target, int level, int internalformat,
                                     int width, int height, int border,
                                     int format, int type, @Nullable ByteBuffer pixels) {
        LOGGER.info("LWJGL: GL11.glTexImage2D(ByteBuffer) - target=0x{}, level={}, format=0x{}, size={}x{}, pixels={}",
            Integer.toHexString(target), level, Integer.toHexString(internalformat),
            width, height, pixels != null ? "present" : "null");

        // Convert ByteBuffer to IntBuffer for BgfxGlTexture
        if (pixels != null) {
            long addr = MemoryUtil.memAddress(pixels);
            IntBuffer intPixels = MemoryUtil.memIntBuffer(addr, pixels.remaining() / 4);
            BgfxGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, intPixels);
        } else {
            BgfxGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, null);
        }
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexImage2D() long pointer variant - Called directly by font loading
     */
    @Overwrite
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level,
                                     @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width,
                                     @NativeType("GLsizei") int height, @NativeType("GLint") int border,
                                     @NativeType("GLenum") int format, @NativeType("GLenum") int type,
                                     @NativeType("void const *") long pixels) {
        LOGGER.info("LWJGL: GL11.glTexImage2D(long) - target=0x{}, level={}, format=0x{}, size={}x{}, pixels=0x{}",
            Integer.toHexString(target), level, Integer.toHexString(internalformat),
            width, height, Long.toHexString(pixels));

        // Convert long pointer to IntBuffer
        if (pixels != 0L) {
            int pixelCount = width * height;
            IntBuffer intPixels = MemoryUtil.memIntBuffer(pixels, pixelCount);
            BgfxGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, intPixels);
        } else {
            BgfxGlTexture.texImage2D(target, level, internalformat, width, height, border, format, type, null);
        }
    }

    // ============================================================================
    // TEXTURE SUB-IMAGE UPLOAD (Partial update)
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexSubImage2D() long pointer variant - Called directly by font loading
     */
    @Overwrite
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                        int width, int height, int format, int type, long pixels) {
        LOGGER.info("LWJGL: GL11.glTexSubImage2D(long) - target=0x{}, level={}, offset=({},{}), size={}x{}, pixels=0x{}",
            Integer.toHexString(target), level, xOffset, yOffset, width, height, Long.toHexString(pixels));
        BgfxGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexSubImage2D() ByteBuffer variant - Called directly by font loading
     */
    @Overwrite
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                        int width, int height, int format, int type, @Nullable ByteBuffer pixels) {
        LOGGER.info("LWJGL: GL11.glTexSubImage2D(ByteBuffer) - target=0x{}, level={}, offset=({},{}), size={}x{}",
            Integer.toHexString(target), level, xOffset, yOffset, width, height);

        if (pixels != null) {
            long addr = MemoryUtil.memAddress(pixels);
            BgfxGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, addr);
        }
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexSubImage2D() IntBuffer variant - Called directly by font loading
     */
    @Overwrite
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                        int width, int height, int format, int type, @Nullable IntBuffer pixels) {
        LOGGER.info("LWJGL: GL11.glTexSubImage2D(IntBuffer) - target=0x{}, level={}, offset=({},{}), size={}x{}",
            Integer.toHexString(target), level, xOffset, yOffset, width, height);

        if (pixels != null) {
            ByteBuffer bytePixels = MemoryUtil.memByteBuffer(pixels);
            long addr = MemoryUtil.memAddress(bytePixels);
            BgfxGlTexture.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, addr);
        }
    }

    // ============================================================================
    // PIXEL STORE PARAMETERS
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glPixelStorei() - Called directly by font loading
     */
    @Overwrite
    public static void glPixelStorei(@NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        LOGGER.debug("LWJGL: GL11.glPixelStorei(pname=0x{}, param={})", Integer.toHexString(pname), param);
        BgfxGlTexture.pixelStore(pname, param);
    }

    // ============================================================================
    // TEXTURE PARAMETERS
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexParameteri() - Called directly by font loading
     */
    @Overwrite
    public static void glTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        LOGGER.debug("LWJGL: GL11.glTexParameteri(target=0x{}, pname=0x{}, param=0x{})",
            Integer.toHexString(target), Integer.toHexString(pname), Integer.toHexString(param));
        BgfxGlTexture.texParameter(target, pname, param);
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glTexParameterf() - Called directly by font loading
     */
    @Overwrite
    public static void glTexParameterf(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat") float param) {
        LOGGER.debug("LWJGL: GL11.glTexParameterf(target=0x{}, pname=0x{}, param={}) - NO-OP",
            Integer.toHexString(target), Integer.toHexString(pname), param);
        // No-op: BgfxGlTexture only handles int parameters
    }

    // ============================================================================
    // TEXTURE QUERIES
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glGetTexLevelParameteri() - Called directly by font loading
     */
    @Overwrite
    public static int glGetTexLevelParameteri(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLenum") int pname) {
        int value = BgfxGlTexture.getTexLevelParameter(target, level, pname);
        LOGGER.debug("LWJGL: GL11.glGetTexLevelParameteri(target=0x{}, level={}, pname=0x{}) - returning {}",
            Integer.toHexString(target), level, Integer.toHexString(pname), value);
        return value;
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glGetTexParameteri() - Called directly by font loading
     */
    @Overwrite
    public static int glGetTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname) {
        // Return default values for common parameters
        int value = switch (pname) {
            case 0x2800 -> 0x2601; // GL_TEXTURE_MAG_FILTER -> GL_LINEAR
            case 0x2801 -> 0x2601; // GL_TEXTURE_MIN_FILTER -> GL_LINEAR
            case 0x2802 -> 0x812F; // GL_TEXTURE_WRAP_S -> GL_CLAMP_TO_EDGE
            case 0x2803 -> 0x812F; // GL_TEXTURE_WRAP_T -> GL_CLAMP_TO_EDGE
            default -> 0;
        };
        LOGGER.debug("LWJGL: GL11.glGetTexParameteri(target=0x{}, pname=0x{}) - returning {}",
            Integer.toHexString(target), Integer.toHexString(pname), value);
        return value;
    }

    // ============================================================================
    // TEXTURE DELETION
    // ============================================================================

    /**
     * @author Vitra
     * @reason Intercept GL11.glDeleteTextures() single texture variant
     */
    @Overwrite
    public static void glDeleteTextures(@NativeType("GLuint const *") int texture) {
        LOGGER.info("LWJGL: GL11.glDeleteTextures(texture={})", texture);
        BgfxGlTexture.deleteTexture(texture);
    }

    /**
     * @author Vitra
     * @reason Intercept GL11.glDeleteTextures() IntBuffer variant
     */
    @Overwrite
    public static void glDeleteTextures(@NativeType("GLuint const *") IntBuffer textures) {
        LOGGER.info("LWJGL: GL11.glDeleteTextures(IntBuffer) - count={}", textures.remaining());
        while (textures.hasRemaining()) {
            BgfxGlTexture.deleteTexture(textures.get());
        }
    }

    // ============================================================================
    // ERROR HANDLING
    // ============================================================================

    /**
     * @author Vitra
     * @reason Always return GL_NO_ERROR - BGFX has no errors
     */
    @NativeType("GLenum")
    @Overwrite
    public static int glGetError() {
        return 0; // GL_NO_ERROR
    }

    // ============================================================================
    // OTHER GL11 METHODS (No-ops for compatibility)
    // ============================================================================

    /**
     * @author Vitra
     * @reason No-op for compatibility
     */
    @Overwrite
    public static void glFinish() {
        // No-op: BGFX handles synchronization internally
    }

    /**
     * @author Vitra
     * @reason No-op for compatibility
     */
    @Overwrite
    public static void glHint(@NativeType("GLenum") int target, @NativeType("GLenum") int hint) {
        // No-op: BGFX doesn't use hints
    }

    /**
     * @author Vitra
     * @reason Always return true for compatibility
     */
    @NativeType("GLboolean")
    @Overwrite
    public static boolean glIsEnabled(@NativeType("GLenum") int cap) {
        return true; // Assume enabled
    }

    /**
     * @author Vitra
     * @reason Return 0 for all GL integer queries
     */
    @NativeType("void")
    @Overwrite
    public static int glGetInteger(@NativeType("GLenum") int pname) {
        return 0; // Default value
    }
}
