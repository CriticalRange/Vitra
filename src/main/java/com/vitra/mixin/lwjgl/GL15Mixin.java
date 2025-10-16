package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL GL15 mixin that intercepts buffer operations at the driver level
 * Based on VulkanMod's approach but adapted for DirectX 11 backend
 */
@Mixin(GL15.class)
public class GL15Mixin {

    /**
     * @author Vitra
     * @reason Intersect buffer generation for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGenBuffers(@NativeType("GLuint *") IntBuffer buffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGenBuffers(buffers);
    }

    /**
     * @author Vitra
     * @reason Intersect single buffer generation for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGenBuffers() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGenBuffers();
    }

    /**
     * @author Vitra
     * @reason Intersect buffer binding for DirectX 11 (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBindBuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int buffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBindBuffer(target, buffer);
    }

    /**
     * @author Vitra
     * @reason Intersect buffer data upload (size version) (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBufferData(@NativeType("GLenum") int target, @NativeType("GLsizeiptr") long size, @NativeType("GLenum") int usage) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBufferData(target, size, usage);
    }

    /**
     * @author Vitra
     * @reason Intersect buffer data upload (ByteBuffer version) (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBufferData(@NativeType("GLenum") int target, @Nullable @NativeType("void const *") ByteBuffer data, @NativeType("GLenum") int usage) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBufferData(target, data, usage);
    }


    /**
     * @author Vitra
     * @reason Intersect buffer sub-data update (ByteBuffer version) (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBufferSubData(@NativeType("GLenum") int target, @NativeType("GLintptr") long offset, @Nullable @NativeType("void const *") ByteBuffer data) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBufferSubData(target, offset, data);
    }


    /**
     * @author Vitra
     * @reason Intersect buffer deletion (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteBuffers(@NativeType("GLuint const *") IntBuffer buffers) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteBuffers(buffers);
    }

    /**
     * @author Vitra
     * @reason Intersect buffer deletion (single buffer) (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteBuffers(@NativeType("GLuint") int buffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteBuffers(buffer);
    }

    /**
     * @author Vitra
     * @reason Intersect buffer mapping for CPU access (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static ByteBuffer glMapBuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int access) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glMapBuffer(target, access);
    }

    /**
     * @author Vitra
     * @reason Intersect buffer unmapping (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static boolean glUnmapBuffer(@NativeType("GLenum") int target) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glUnmapBuffer(target);
    }

    /**
     * @author Vitra
     * @reason Intersect getting buffer parameter (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glGetBufferParameteriv(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint *") IntBuffer params) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetBufferParameteriv(target, pname, params);
    }

    /**
     * @author Vitra
     * @reason Enhanced buffer mapping with validation (VulkanMod approach - direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static ByteBuffer glMapBuffer(@NativeType("GLenum") int target,
                                       @NativeType("GLenum") int access,
                                       long length,
                                       @Nullable @NativeType("void *") ByteBuffer old_buffer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glMapBuffer(target, access, length, old_buffer);
    }

    /**
     * @author Vitra
     * @reason Get buffer pointer query (OpenGL 1.5 glGetBufferPointerv)
     */
    @Overwrite(remap = false)
    public static void glGetBufferPointerv(@NativeType("GLenum") int target,
                                         @NativeType("GLenum") int pname,
                                         @NativeType("void **") ByteBuffer params) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetBufferPointerv(target, pname, params);
    }

    /**
     * @author Vitra
     * @reason Get buffer sub-data readback (OpenGL 1.5 glGetBufferSubData)
     */
    @Overwrite(remap = false)
    public static void glGetBufferSubData(@NativeType("GLenum") int target,
                                        @NativeType("GLintptr") long offset,
                                        @NativeType("GLsizeiptr") long size,
                                        @NativeType("void *") ByteBuffer data) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glGetBufferSubData(target, offset, size, data);
    }

}