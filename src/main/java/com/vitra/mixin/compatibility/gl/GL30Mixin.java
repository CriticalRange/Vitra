package com.vitra.mixin.compatibility.gl;

import com.vitra.render.jni.VitraD3D11Renderer;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;

@Mixin(GL30.class)
public class GL30Mixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GL30");
    private static final AtomicInteger framebufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger renderbufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger vaoIdCounter = new AtomicInteger(1);

    // Track renderbuffer info for FBO creation
    private static final Map<Integer, RenderbufferInfo> renderbufferInfo = new HashMap<>();
    private static int boundFramebuffer = 0;
    private static int boundRenderbuffer = 0;

    private static class RenderbufferInfo {
        int width;
        int height;
        int internalFormat;
        boolean isDepth;

        RenderbufferInfo(int width, int height, int internalFormat) {
            this.width = width;
            this.height = height;
            this.internalFormat = internalFormat;
            // Check if this is a depth/stencil format
            this.isDepth = (internalFormat == 0x88F0 || // GL_DEPTH24_STENCIL8
                            internalFormat == 0x81A6 || // GL_DEPTH_COMPONENT24
                            internalFormat == 0x81A7);  // GL_DEPTH_COMPONENT32
        }
    }

    @Overwrite(remap = false)
    public static void glGenerateMipmap(@NativeType("GLenum") int target) {
        // No-op - not needed for DirectX
    }

    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        return framebufferIdCounter.getAndIncrement();
    }

    @Overwrite(remap = false)
    public static void glBindFramebuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int framebuffer) {
        boundFramebuffer = framebuffer;
        VitraD3D11Renderer.bindFramebuffer(target, framebuffer);
    }

    @Overwrite(remap = false)
    public static void glFramebufferTexture2D(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int textarget, @NativeType("GLuint") int texture, @NativeType("GLint") int level) {
        // Not used by Minecraft for FBO creation - they use renderbuffers
    }

    @Overwrite(remap = false)
    public static void glFramebufferRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int renderbuffertarget, @NativeType("GLuint") int renderbuffer) {
        // This is called to attach renderbuffer to framebuffer
        // At this point we should have the renderbuffer info and can create the FBO
        if (boundFramebuffer != 0 && renderbuffer != 0) {
            RenderbufferInfo info = renderbufferInfo.get(renderbuffer);
            if (info != null) {
                // Create the FBO with proper dimensions
                boolean hasColor = !info.isDepth;
                boolean hasDepth = info.isDepth;

                boolean success = VitraD3D11Renderer.createFramebufferTextures(
                    boundFramebuffer, info.width, info.height, hasColor, hasDepth);

                LOGGER.debug("[GL30] Attached renderbuffer {} to framebuffer {} ({}x{}, depth={}), success={}",
                    renderbuffer, boundFramebuffer, info.width, info.height, hasDepth, success);
            }
        }
    }

    @Overwrite(remap = false)
    public static void glDeleteFramebuffers(@NativeType("GLuint const *") int framebuffer) {
        if (framebuffer != 0) {
            VitraD3D11Renderer.destroyFramebuffer(framebuffer);
        }
    }

    @Overwrite(remap = false)
    @NativeType("GLenum")
    public static int glCheckFramebufferStatus(@NativeType("GLenum") int target) {
        // Return complete status - handled by GlStateManagerM
        return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
    }

    @Overwrite(remap = false)
    public static void glBlitFramebuffer(@NativeType("GLint") int srcX0, @NativeType("GLint") int srcY0, @NativeType("GLint") int srcX1, @NativeType("GLint") int srcY1, @NativeType("GLint") int dstX0, @NativeType("GLint") int dstY0, @NativeType("GLint") int dstX1, @NativeType("GLint") int dstY1, @NativeType("GLbitfield") int mask, @NativeType("GLenum") int filter) {
        // No-op - not needed for DirectX
    }

    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenRenderbuffers() {
        return renderbufferIdCounter.getAndIncrement();
    }

    @Overwrite(remap = false)
    public static void glBindRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int renderbuffer) {
        boundRenderbuffer = renderbuffer;
    }

    @Overwrite(remap = false)
    public static void glRenderbufferStorage(@NativeType("GLenum") int target, @NativeType("GLenum") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        if (width == 0 || height == 0 || boundRenderbuffer == 0) return;

        // Store renderbuffer info for later FBO creation
        RenderbufferInfo info = new RenderbufferInfo(width, height, internalformat);
        renderbufferInfo.put(boundRenderbuffer, info);

        LOGGER.debug("[GL30] glRenderbufferStorage: rb={}, {}x{}, format=0x{}, isDepth={}",
            boundRenderbuffer, width, height, Integer.toHexString(internalformat), info.isDepth);
    }

    @Overwrite(remap = false)
    public static void glDeleteRenderbuffers(@NativeType("GLuint const *") int renderbuffer) {
        renderbufferInfo.remove(renderbuffer);
    }

    // VERTEX ARRAY OBJECTS (VAOs) - CRITICAL for Minecraft 1.21.1

    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenVertexArrays() {
        return vaoIdCounter.getAndIncrement();
    }

    @Overwrite(remap = false)
    public static void glBindVertexArray(@NativeType("GLuint") int array) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glDeleteVertexArrays(@NativeType("GLuint const *") int array) {
        // Handled by GlStateManagerM
    }
}
