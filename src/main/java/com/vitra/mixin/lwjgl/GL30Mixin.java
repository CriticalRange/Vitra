package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.IntBuffer;

/**
 * LWJGL GL30 mixin that intercepts OpenGL 3.0+ operations at the driver level
 * Based on VulkanMod's approach but adapted for DirectX 11 backend
 *
 * OpenGL 3.0+ features intercepted:
 * - Framebuffer objects (FBO) for render-to-texture
 * - Vertex array objects (VAO) for vertex attribute state
 * - Multiple render targets (MRT)
 * - Instanced rendering
 */
@Mixin(GL30.class)
public class GL30Mixin {

    /**
     * @author Vitra
     * @reason Intercept framebuffer generation for DirectX 11 render targets (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGenFramebuffers(@NativeType("GLuint *") IntBuffer framebuffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGenFramebuffers(framebuffers);
    }

    /**
     * @author Vitra
     * @reason Intercept framebuffer binding for DirectX 11 render targets (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBindFramebuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int framebuffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBindFramebuffer(target, framebuffer);
    }

    /**
     * @author Vitra
     * @reason Intercept framebuffer texture attachment for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glFramebufferTexture2D(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int textarget, @NativeType("GLuint") int texture, @NativeType("GLint") int level) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    /**
     * @author Vitra
     * @reason Intercept framebuffer renderbuffer attachment for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glFramebufferRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLenum") int renderbuffertarget, @NativeType("GLuint") int renderbuffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    /**
     * @author Vitra
     * @reason Intercept framebuffer completeness check for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glCheckFramebufferStatus(@NativeType("GLenum") int target) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glCheckFramebufferStatus(target);
    }

    /**
     * @author Vitra
     * @reason Intercept framebuffer deletion for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteFramebuffers(@NativeType("GLuint const *") IntBuffer framebuffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteFramebuffers(framebuffers);
    }

    /**
     * @author Vitra
     * @reason Intersect renderbuffer generation for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGenRenderbuffers(@NativeType("GLuint *") IntBuffer renderbuffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGenRenderbuffers(renderbuffers);
    }

    /**
     * @author Vitra
     * @reason Intersect renderbuffer binding for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBindRenderbuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int renderbuffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBindRenderbuffer(target, renderbuffer);
    }

    /**
     * @author Vitra
     * @reason Intersect renderbuffer storage for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glRenderbufferStorage(@NativeType("GLenum") int target, @NativeType("GLenum") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glRenderbufferStorage(target, internalformat, width, height);
    }

    /**
     * @author Vitra
     * @reason Intersect renderbuffer deletion for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteRenderbuffers(@NativeType("GLuint const *") IntBuffer renderbuffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteRenderbuffers(renderbuffers);
    }

    /**
     * @author Vitra
     * @reason Intersect vertex array object generation for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGenVertexArrays(@NativeType("GLuint *") IntBuffer arrays) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGenVertexArrays(arrays);
    }

    /**
     * @author Vitra
     * @reason Intersect vertex array object binding for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBindVertexArray(@NativeType("GLuint") int array) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBindVertexArray(array);
    }

    /**
     * @author Vitra
     * @reason Intersect vertex array object deletion for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteVertexArrays(@NativeType("GLuint const *") IntBuffer arrays) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteVertexArrays(arrays);
    }
}