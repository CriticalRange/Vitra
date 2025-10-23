package com.vitra.mixin.compatibility.gl;

import org.lwjgl.opengl.GL20;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Mixin(GL20.class)
public class GL20Mixin {

    // VERTEX ATTRIBUTE ARRAY METHODS - CRITICAL for Minecraft rendering

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glEnableVertexAttribArray(@NativeType("GLuint") int index) {
        // Handled by GlStateManagerM
    }

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glDisableVertexAttribArray(@NativeType("GLuint") int index) {
        // Handled by GlStateManagerM
    }

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @NativeType("void const *") ByteBuffer pointer) {
        // Handled by GlStateManagerM
    }

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @NativeType("void const *") long pointer) {
        // Handled by GlStateManagerM
    }

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @NativeType("void const *") IntBuffer pointer) {
        // Handled by GlStateManagerM
    }

    /**
     * @author Vitra
     * @reason Prevent OpenGL calls - handled by DirectX 11 via GlStateManagerM
     */
    @Overwrite(remap = false)
    public static void glVertexAttribPointer(@NativeType("GLuint") int index, @NativeType("GLint") int size, @NativeType("GLenum") int type, @NativeType("GLboolean") boolean normalized, @NativeType("GLsizei") int stride, @NativeType("void const *") FloatBuffer pointer) {
        // Handled by GlStateManagerM
    }
}
