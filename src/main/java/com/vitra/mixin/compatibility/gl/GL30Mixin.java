package com.vitra.mixin.compatibility.gl;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(GL30.class)
public class GL30Mixin {
    private static final AtomicInteger framebufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger renderbufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger vaoIdCounter = new AtomicInteger(1);

    @Overwrite(remap = false)
    public static void glGenerateMipmap(@NativeType("GLenum") int target) {
        // No-op - not needed for DirectX 11
    }

    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        return framebufferIdCounter.getAndIncrement();
    }

    @Overwrite(remap = false)
    public static void glBindFramebuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int framebuffer) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glFramebufferTexture2D(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int textarget, @NativeType("GLuint") int texture, @NativeType("GLint") int level) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glFramebufferRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int renderbuffertarget, @NativeType("GLuint") int renderbuffer) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glDeleteFramebuffers(@NativeType("GLuint const *") int framebuffer) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    @NativeType("GLenum")
    public static int glCheckFramebufferStatus(@NativeType("GLenum") int target) {
        // Return complete status - handled by GlStateManagerM
        return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
    }

    @Overwrite(remap = false)
    public static void glBlitFramebuffer(@NativeType("GLint") int srcX0, @NativeType("GLint") int srcY0, @NativeType("GLint") int srcX1, @NativeType("GLint") int srcY1, @NativeType("GLint") int dstX0, @NativeType("GLint") int dstY0, @NativeType("GLint") int dstX1, @NativeType("GLint") int dstY1, @NativeType("GLbitfield") int mask, @NativeType("GLenum") int filter) {
        // No-op - not needed for DirectX 11
    }

    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenRenderbuffers() {
        return renderbufferIdCounter.getAndIncrement();
    }

    @Overwrite(remap = false)
    public static void glBindRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int framebuffer) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glRenderbufferStorage(@NativeType("GLenum") int target, @NativeType("GLenum") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        // Handled by GlStateManagerM
    }

    @Overwrite(remap = false)
    public static void glDeleteRenderbuffers(@NativeType("GLuint const *") int renderbuffer) {
        // Handled by GlStateManagerM
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
