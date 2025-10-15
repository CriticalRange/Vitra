package com.vitra.mixin.lwjgl;

import com.vitra.render.opengl.GLInterceptor;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import org.jetbrains.annotations.Nullable;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL GL20 mixin that intercepts shader operations at the driver level
 * Critical for fixing yellow ray artifacts and shader-related rendering issues
 */
@Mixin(GL20.class)
public class GL20Mixin {

    /**
     * @author Vitra
     * @reason Intersect shader program usage (CRITICAL for ray rendering) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUseProgram(@NativeType("GLuint") int program) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUseProgram(program);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform 1i setting (CRITICAL for texture binding in shaders) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniform1i(@NativeType("GLint") int location, @NativeType("GLint") int v0) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniform1i(location, v0);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform 1f setting - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniform1f(@NativeType("GLint") int location, @NativeType("GLfloat") float v0) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniform1f(location, v0);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform 2f setting - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniform2f(@NativeType("GLint") int location, @NativeType("GLfloat") float v0, @NativeType("GLfloat") float v1) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniform2f(location, v0, v1);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform 3f setting - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniform3f(@NativeType("GLint") int location, @NativeType("GLfloat") float v0, @NativeType("GLfloat") float v1, @NativeType("GLfloat") float v2) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniform3f(location, v0, v1, v2);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform 4f setting (CRITICAL for color and position data) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniform4f(@NativeType("GLint") int location, @NativeType("GLfloat") float v0, @NativeType("GLfloat") float v1, @NativeType("GLfloat") float v2, @NativeType("GLfloat") float v3) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniform4f(location, v0, v1, v2, v3);
    }

    /**
     * @author Vitra
     * @reason Intersect uniform matrix 4x4 setting (CRITICAL for transformations) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glUniformMatrix4fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @Nullable @NativeType("GLfloat const *") FloatBuffer value) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glUniformMatrix4fv(location, transpose, value);
    }

    
    /**
     * @author Vitra
     * @reason Intersect getting uniform location - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetUniformLocation(@NativeType("GLuint") int program, @Nullable @NativeType("GLchar const *") java.nio.ByteBuffer name) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetUniformLocation(program, name);
    }

    /**
     * @author Vitra
     * @reason Intersect getting uniform location (string version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetUniformLocation(@NativeType("GLuint") int program, @NativeType("GLchar const *") java.lang.CharSequence name) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetUniformLocation(program, name);
    }

    /**
     * @author Vitra
     * @reason Intersect getting attribute location - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetAttribLocation(@NativeType("GLuint") int program, @Nullable @NativeType("GLchar const *") java.nio.ByteBuffer name) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetAttribLocation(program, name);
    }

    /**
     * @author Vitra
     * @reason Intersect getting attribute location (string version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glGetAttribLocation(@NativeType("GLuint") int program, @NativeType("GLchar const *") java.lang.CharSequence name) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glGetAttribLocation(program, name);
    }

    /**
     * @author Vitra
     * @reason Intersect enabling vertex attribute arrays - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glEnableVertexAttribArray(@NativeType("GLuint") int index) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glEnableVertexAttribArray(index);
    }

    /**
     * @author Vitra
     * @reason Intersect disabling vertex attribute arrays - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDisableVertexAttribArray(@NativeType("GLuint") int index) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDisableVertexAttribArray(index);
    }

    /**
     * @author Vitra
     * @reason Intersect vertex attribute pointer - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @NativeType("void const *") long pointer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    /**
     * @author Vitra
     * @reason Intersect vertex attribute pointer (buffer version) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @Nullable @NativeType("void const *") java.nio.ByteBuffer pointer) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    
    /**
     * @author Vitra
     * @reason Intersect creating shader - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glCreateShader(@NativeType("GLenum") int type) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glCreateShader(type);
    }

    
    /**
     * @author Vitra
     * @reason Intersect shader source code (CRITICAL for shader compilation) - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glShaderSource(@NativeType("GLuint") int shader, @Nullable @NativeType("GLchar const *") java.lang.CharSequence string) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glShaderSource(shader, string);
    }

    
    /**
     * @author Vitra
     * @reason Intersect compiling shader - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glCompileShader(@NativeType("GLuint") int shader) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glCompileShader(shader);
    }

    /**
     * @author Vitra
     * @reason Intersect creating program - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        return GLInterceptor.glCreateProgram();
    }

    /**
     * @author Vitra
     * @reason Intersect attaching shader to program - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glAttachShader(@NativeType("GLuint") int program, @NativeType("GLuint") int shader) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glAttachShader(program, shader);
    }

    /**
     * @author Vitra
     * @reason Intersect linking program - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glLinkProgram(@NativeType("GLuint") int program) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glLinkProgram(program);
    }

    /**
     * @author Vitra
     * @reason Intersect validating program - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glValidateProgram(@NativeType("GLuint") int program) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glValidateProgram(program);
    }

    /**
     * @author Vitra
     * @reason Intersect deleting shader - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteShader(@NativeType("GLuint") int shader) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteShader(shader);
    }

    /**
     * @author Vitra
     * @reason Intersect deleting program - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(@NativeType("GLuint") int program) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glDeleteProgram(program);
    }

    /**
     * @author Vitra
     * @reason Intersect color blending equation for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glBlendEquationSeparate(@NativeType("GLenum") int modeRGB, @NativeType("GLenum") int modeAlpha) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    /**
     * @author Vitra
     * @reason Intersect stencil operations for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glStencilOpSeparate(@NativeType("GLenum") int face, @NativeType("GLenum") int sfail, @NativeType("GLenum") int dpfail, @NativeType("GLenum") int dppass) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glStencilOpSeparate(face, sfail, dpfail, dppass);
    }

    /**
     * @author Vitra
     * @reason Intersect stencil function for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glStencilFuncSeparate(@NativeType("GLenum") int face, @NativeType("GLenum") int func, @NativeType("GLint") int ref, @NativeType("GLuint") int mask) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glStencilFuncSeparate(face, func, ref, mask);
    }

    /**
     * @author Vitra
     * @reason Intersect stencil mask for DirectX 11 - VulkanMod approach (direct DirectX 11)
     */
    @Overwrite(remap = false)
    public static void glStencilMaskSeparate(@NativeType("GLenum") int face, @NativeType("GLuint") int mask) {
        // Direct call to DirectX 11 implementation - no OpenGL fallback (VulkanMod approach)
        GLInterceptor.glStencilMaskSeparate(face, mask);
    }
}