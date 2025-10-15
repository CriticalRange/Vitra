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

    }