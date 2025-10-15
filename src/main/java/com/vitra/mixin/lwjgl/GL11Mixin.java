package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL GL11 mixin that intercepts OpenGL calls at the driver level
 * Based on VulkanMod's approach but adapted for DirectX 11 backend
 *
 * Using @Redirect instead of @Overwrite to avoid recursion issues
 */
@Mixin(GL11.class)
public class GL11Mixin {

    /**
     * @author Vitra
     * @reason Intercept texture binding for DirectX 11
     */
    @Overwrite(remap = false)
    public static void glBindTexture(@NativeType("GLenum") int target, @NativeType("GLuint") int texture) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBindTexture(target, texture);
    }

    /**
     * @author Vitra
     * @reason Intersect viewport for DirectX 11
     */
    @Overwrite(remap = false)
    public static void glViewport(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int w, @NativeType("GLsizei") int h) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glViewport(x, y, w, h);
    }

    /**
     * @author Vitra
     * @reason Intersect scissor test - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glScissor(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glScissor(x, y, width, height);
    }

    /**
     * @author Vitra
     * @reason Intersect texture generation
     */
    @Overwrite(remap = false)
    public static int glGenTextures() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGenTextures();
    }

    /**
     * @author Vitra
     * @reason Intersect clear color for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glClearColor(@NativeType("GLfloat") float red, @NativeType("GLfloat") float green, @NativeType("GLfloat") float blue, @NativeType("GLfloat") float alpha) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glClearColor(red, green, blue, alpha);
    }

    /**
     * @author Vitra
     * @reason Intersect clear operation for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glClear(@NativeType("GLbitfield") int mask) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glClear(mask);
    }

    /**
     * @author Vitra
     * @reason Intersect texture image upload - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect texture image upload (pointer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void const *") long pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        // Pass pointer directly to GLInterceptor for native handling
        GLInterceptor.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect texture sub-image update (pointer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        // Pass pointer directly to GLInterceptor for native handling
        GLInterceptor.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect texture sub-image update (ByteBuffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable ByteBuffer pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect texture sub-image update (IntBuffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable IntBuffer pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect texture parameter setting (CRITICAL for fixing yellow rays) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glTexParameteri(target, pname, param);
    }

    /**
     * @author Vitra
     * @reason Intersect texture parameter setting (float version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glTexParameterf(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat") float param) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glTexParameterf(target, pname, param);
    }

    /**
     * @author Vitra
     * @reason Intersect getting texture parameter - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetTexParameteri(target, pname);
    }

    /**
     * @author Vitra
     * @reason Intersect getting texture level parameter - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetTexLevelParameteri(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLenum") int pname) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetTexLevelParameteri(target, level, pname);
    }

    /**
     * @author Vitra
     * @reason Intersect pixel storage mode (important for texture alignment) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glPixelStorei(@NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glPixelStorei(pname, param);
    }

    /**
     * @author Vitra
     * @reason Intersect getting texture image data - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") long pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetTexImage(tex, level, format, type, pixels);
    }

    /**
     * @author Vitra
     * @reason Intersect getting texture image data (ByteBuffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") ByteBuffer pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetTexImage(tex, level, format, type, MemoryUtil.memAddress(pixels));
    }

    /**
     * @author Vitra
     * @reason Intersect getting texture image data (IntBuffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") IntBuffer pixels) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetTexImage(tex, level, format, type, MemoryUtil.memAddress(pixels));
    }

    /**
     * @author Vitra
     * @reason Intersect texture deletion - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") int texture) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteTextures(texture);
    }

    /**
     * @author Vitra
     * @reason Intersect texture deletion (IntBuffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") IntBuffer textures) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteTextures(textures);
    }

    /**
     * @author Vitra
     * @reason Intersect line width setting - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glLineWidth(@NativeType("GLfloat") float width) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glLineWidth(width);
    }

    /**
     * @author Vitra
     * @reason Intersect depth mask setting - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDepthMask(@NativeType("GLboolean") boolean flag) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDepthMask(flag);
    }

    /**
     * @author Vitra
     * @reason Intersect polygon offset (for depth fighting) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glPolygonOffset(@NativeType("GLfloat") float factor, @NativeType("GLfloat") float units) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glPolygonOffset(factor, units);
    }

    /**
     * @author Vitra
     * @reason Intersect blending function (CRITICAL for proper rendering) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBlendFunc(@NativeType("GLenum") int sfactor, @NativeType("GLenum") int dfactor) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBlendFunc(sfactor, dfactor);
    }

    /**
     * @author Vitra
     * @reason Intersect enabling OpenGL capabilities - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glEnable(@NativeType("GLenum") int cap) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glEnable(cap);
    }

    /**
     * @author Vitra
     * @reason Intersect disabling OpenGL capabilities - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDisable(@NativeType("GLenum") int cap) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDisable(cap);
    }

    /**
     * @author Vitra
     * @reason Intersect checking if capability is enabled - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static boolean glIsEnabled(@NativeType("GLenum") int cap) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glIsEnabled(cap);
    }

    /**
     * @author Vitra
     * @reason Intersect getting integer state - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetInteger(@NativeType("GLenum") int pname) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetInteger(pname);
    }

    /**
     * @author Vitra
     * @reason Intersect getting error state - CRITICAL FIX for StackOverflowError
     */
    @Overwrite(remap = false)
    public static int glGetError() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetError();
    }

    /**
     * @author Vitra
     * @reason Intersect finish command - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glFinish() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glFinish();
    }

    /**
     * @author Vitra
     * @reason Intersect implementation hints - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glHint(@NativeType("GLenum") int target, @NativeType("GLenum") int hint) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glHint(target, hint);
    }

    /**
     * @author Vitra
     * @reason Intersect copy texture sub-image - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glCopyTexSubImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int xoffset, @NativeType("GLint") int yoffset, @NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    /**
     * @author Vitra
     * @reason Intersect front face definition - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glFrontFace(@NativeType("GLenum") int mode) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glFrontFace(mode);
    }

    /**
     * @author Vitra
     * @reason Intersect cull face - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glCullFace(@NativeType("GLenum") int mode) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glCullFace(mode);
    }

    /**
     * @author Vitra
     * @reason Intersect depth function - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDepthFunc(@NativeType("GLenum") int func) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDepthFunc(func);
    }

    /**
     * @author Vitra
     * @reason Intersect point size - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glPointSize(@NativeType("GLfloat") float size) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glPointSize(size);
    }

    /**
     * @author Vitra
     * @reason Intersect color mask - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glColorMask(@NativeType("GLboolean") boolean red, @NativeType("GLboolean") boolean green, @NativeType("GLboolean") boolean blue, @NativeType("GLboolean") boolean alpha) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glColorMask(red, green, blue, alpha);
    }

    /**
     * @author Vitra
     * @reason Intersect getting integer state (array version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetIntegerv(@NativeType("GLenum") int pname, @NativeType("GLint *") IntBuffer data) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetIntegerv(pname, data);
    }

    /**
     * @author Vitra
     * @reason Intersect getting float state (array version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetFloatv(@NativeType("GLenum") int pname, @NativeType("GLfloat *") FloatBuffer data) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetFloatv(pname, data);
    }

    /**
     * @author Vitra
     * @reason Intersect getting boolean state - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetBooleanv(@NativeType("GLenum") int pname, @NativeType("GLboolean *") java.nio.ByteBuffer data) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetBooleanv(pname, data);
    }

    /**
     * @author Vitra
     * @reason Intersect flush command - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glFlush() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glFlush();
    }
}