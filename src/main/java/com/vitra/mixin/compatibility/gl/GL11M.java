package com.vitra.mixin.compatibility.gl;

import com.vitra.render.jni.VitraNativeRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import org.jetbrains.annotations.Nullable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(GL11.class)
public class GL11M {

    /**
     * @author
     * @reason ideally Scissor should be used. but using vkCmdSetScissor() caused glitches with invisible menus with replay mod, so disabled for now as temp fix
     */
    @Overwrite(remap = false)
    public static void glScissor(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        VitraNativeRenderer.setScissor(x, y, width, height);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glViewport(@NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int w, @NativeType("GLsizei") int h) {
        VitraNativeRenderer.setViewport(x, y, w, h);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBindTexture(@NativeType("GLenum") int target, @NativeType("GLuint") int texture) {
        VitraNativeRenderer.bindTexture(texture);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glLineWidth(@NativeType("GLfloat") float width) {
        // Line width - no-op for DirectX 11
    }

    /**
     * @author
     * @reason
     */
    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGenTextures() {
        return VitraNativeRenderer.generateGLTextureId();
    }

    /**
     * @author
     * @reason
     */
    @NativeType("GLboolean")
    @Overwrite(remap = false)
    public static boolean glIsEnabled(@NativeType("GLenum") int cap) {
        return true;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glClear(@NativeType("GLbitfield") int mask) {
        VitraNativeRenderer.clear(mask);
    }

    /**
     * @author
     * @reason
     */
    @NativeType("GLenum")
    @Overwrite(remap = false)
    public static int glGetError() {
        return 0;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glClearColor(@NativeType("GLfloat") float red, @NativeType("GLfloat") float green, @NativeType("GLfloat") float blue, @NativeType("GLfloat") float alpha) {
        VitraNativeRenderer.setClearColor(red, green, blue, alpha);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDepthMask(@NativeType("GLboolean") boolean flag) {
        VitraNativeRenderer.depthMask(flag);
    }

    /**
     * @author
     * @reason
     */
    @NativeType("void")
    @Overwrite(remap = false)
    public static int glGetInteger(@NativeType("GLenum") int pname) {
        return 0;
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        VitraNativeRenderer.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLint") int border, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void const *") long pixels) {
        ByteBuffer buffer = pixels != 0 ? MemoryUtil.memByteBuffer(pixels, width * height * 4) : null;
        VitraNativeRenderer.texImage2D(target, level, internalformat, width, height, border, format, type, buffer);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        VitraNativeRenderer.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable ByteBuffer pixels) {
        long address = pixels != null ? MemoryUtil.memAddress(pixels) : 0;
        VitraNativeRenderer.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, address);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, @Nullable IntBuffer pixels) {
        long address = pixels != null ? MemoryUtil.memAddress(pixels) : 0;
        VitraNativeRenderer.texSubImage2D(target, level, xOffset, yOffset, width, height, format, type, address);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        VitraNativeRenderer.texParameteri(target, pname, param);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glTexParameterf(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLfloat") float param) {

    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static int glGetTexParameteri(@NativeType("GLenum") int target, @NativeType("GLenum") int pname) {
        return VitraNativeRenderer.getTextureParameter(target, pname);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static int glGetTexLevelParameteri(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLenum") int pname) {
        return VitraNativeRenderer.getTextureLevelParameter(target, level, pname);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glPixelStorei(@NativeType("GLenum") int pname, @NativeType("GLint") int param) {
        VitraNativeRenderer.setPixelStore(pname, param);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glEnable(@NativeType("GLenum") int target) {

    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDisable(@NativeType("GLenum") int target) {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glFinish() {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glHint(@NativeType("GLenum") int target, @NativeType("GLenum") int hint) {
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") int texture) {
        VitraNativeRenderer.deleteTexture(texture);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glDeleteTextures(@NativeType("GLuint const *") IntBuffer textures) {
        while (textures.hasRemaining()) {
            VitraNativeRenderer.deleteTexture(textures.get());
        }
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") long pixels) {
        VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") ByteBuffer pixels) {
        VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glGetTexImage(@NativeType("GLenum") int tex, @NativeType("GLint") int level, @NativeType("GLenum") int format, @NativeType("GLenum") int type, @NativeType("void *") IntBuffer pixels) {
        VitraNativeRenderer.glGetTexImage(tex, level, format, type, pixels);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glCopyTexSubImage2D(@NativeType("GLenum") int target, @NativeType("GLint") int level, @NativeType("GLint") int xoffset, @NativeType("GLint") int yoffset, @NativeType("GLint") int x, @NativeType("GLint") int y, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height) {
        // TODO
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glBlendFunc(@NativeType("GLenum") int sfactor, @NativeType("GLenum") int dfactor) {
        // TODO
    }



    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    public static void glPolygonOffset(@NativeType("GLfloat") float factor, @NativeType("GLfloat") float units) {
        VitraNativeRenderer.polygonOffset(factor, units);
    }
}
