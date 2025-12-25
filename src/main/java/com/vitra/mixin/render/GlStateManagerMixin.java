package com.vitra.mixin.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GlStateManager mixin for Minecraft 26.1
 * Redirects OpenGL state calls to DirectX backend.
 * 
 * 26.1 API Changes:
 * - Package moved from com.mojang.blaze3d.platform to com.mojang.blaze3d.opengl
 * - _clearColor(), _clearDepth() REMOVED (handled elsewhere now)
 * - _blendFunc() REMOVED (only _blendFuncSeparate)
 * - _clear() now takes single int (no boolean)
 * - _texImage2D takes ByteBuffer instead of IntBuffer
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

    // Helper to get renderer instance (with null-safety check)
    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            return null;
        }
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }
        return null;
    }

    // ID counters for resource management
    private static final AtomicInteger framebufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger programIdCounter = new AtomicInteger(1);

    // ==================== BLEND STATE ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX blend disable
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.disableBlend();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX blend enable
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.enableBlend();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX blend function
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    // ==================== SCISSOR STATE ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX scissor disable
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        VitraRenderer r = getRenderer(); if (r != null) r.resetScissor();
    }

    /**
     * @author Vitra
     * @reason No-op - scissor enabled by setScissor call
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        // No-op - scissor enabled by setScissor call
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX scissor rect
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        VitraRenderer r = getRenderer(); if (r != null) r.setScissor(x, y, width, height);
    }

    // ==================== CULL STATE ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX cull enable
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        VitraRenderer r = getRenderer(); if (r != null) r.enableCull();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX cull disable
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        VitraRenderer r = getRenderer(); if (r != null) r.disableCull();
    }

    // ==================== DEPTH STATE ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX depth test disable
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.disableDepthTest();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX depth test enable
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.enableDepthTest();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX depth function
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int func) {
        VitraRenderer r = getRenderer(); if (r != null) r.depthFunc(func);
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX depth mask
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean mask) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.depthMask(mask);
    }

    // ==================== CLEAR ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX clear
     * 
     * 26.1 API Change: No longer takes boolean parameter
     */
    @Overwrite(remap = false)
    public static void _clear(int mask) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer renderer = getRenderer();
        if (renderer != null) {
            renderer.clear(mask);
        }
    }

    // ==================== VIEWPORT ====================

    /**
     * @author Vitra
     * @reason Redirect viewport to DirectX
     */
    @Redirect(method = "_viewport", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"), remap = false)
    private static void _viewport(int x, int y, int width, int height) {
        VitraRenderer r = getRenderer(); if (r != null) r.setViewport(x, y, width, height);
    }

    // ==================== COLOR MASK ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX color mask
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.colorMask(red, green, blue, alpha);
    }

    // ==================== POLYGON STATE ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX polygon mode
     */
    @Overwrite(remap = false)
    public static void _polygonMode(int face, int mode) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.setPolygonMode(mode);
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX polygon offset enable
     */
    @Overwrite(remap = false)
    public static void _enablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.enablePolygonOffset();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX polygon offset disable
     */
    @Overwrite(remap = false)
    public static void _disablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.disablePolygonOffset();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX polygon offset values
     */
    @Overwrite(remap = false)
    public static void _polygonOffset(float factor, float units) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.polygonOffset(factor, units);
    }

    // ==================== LOGIC OP ====================

    /**
     * @author Vitra
     * @reason Redirect to DirectX color logic op enable
     */
    @Overwrite(remap = false)
    public static void _enableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.enableColorLogicOp();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX color logic op disable
     */
    @Overwrite(remap = false)
    public static void _disableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.disableColorLogicOp();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX logic op
     */
    @Overwrite(remap = false)
    public static void _logicOp(int op) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.logicOp(op);
    }

    // ==================== TEXTURE ====================

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture system for texture binding
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int textureId) {
        RenderSystem.assertOnRenderThread();
        com.vitra.render.jni.VitraD3D11Renderer.setActiveTextureUnit(0);
        com.vitra.render.D3D11Texture.bindTexture(textureId);
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for active texture slot tracking
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int texture) {
        RenderSystem.assertOnRenderThread();
        com.vitra.render.D3D11Texture.activeTexture(texture);
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture ID generation
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        RenderSystem.assertOnRenderThread();
        return com.vitra.render.D3D11Texture.genTextureId();
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture deletion
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int textureId) {
        RenderSystem.assertOnRenderThread();
        com.vitra.render.D3D11Texture.deleteTexture(textureId);
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture parameters
     */
    @Overwrite(remap = false)
    public static void _texParameter(int target, int pname, int param) {
        VitraRenderer r = getRenderer(); if (r != null) r.texParameteri(target, pname, param);
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture level parameter query
     */
    @Overwrite(remap = false)
    public static int _getTexLevelParameter(int target, int level, int pname) {
        VitraRenderer r = getRenderer();
        return (r != null) ? r.getTexLevelParameter(target, level, pname) : 0;
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for pixel store parameters
     */
    @Overwrite(remap = false)
    public static void _pixelStore(int pname, int param) {
        RenderSystem.assertOnRenderThread();

        final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
        final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
        final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;

        switch (pname) {
            case GL_UNPACK_ROW_LENGTH:
                com.vitra.render.D3D11Texture.setUnpackRowLength(param);
                break;
            case GL_UNPACK_SKIP_ROWS:
                com.vitra.render.D3D11Texture.setUnpackSkipRows(param);
                break;
            case GL_UNPACK_SKIP_PIXELS:
                com.vitra.render.D3D11Texture.setUnpackSkipPixels(param);
                break;
        }

        VitraRenderer r = getRenderer(); if (r != null) r.pixelStore(pname, param);
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture image upload
     * 
     * 26.1 API Change: Takes ByteBuffer instead of IntBuffer
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        com.vitra.render.D3D11Texture.texImage2D(
            target, level, internalFormat, width, height, border, format, type, pixels
        );
    }

    /**
     * @author Vitra
     * @reason Redirect to D3D11Texture for texture subimage upload
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels) {
        RenderSystem.assertOnRenderThread();
        com.vitra.render.D3D11Texture.texSubImage2D(
            target, level, offsetX, offsetY, width, height, format, type, pixels
        );
    }

    // ==================== FRAMEBUFFER ====================

    /**
     * @author Vitra
     * @reason Generate DirectX framebuffer ID
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        RenderSystem.assertOnRenderThread();
        return framebufferIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX framebuffer binding
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int target, int framebuffer) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.bindFramebuffer(target, framebuffer);
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX framebuffer texture attachment
     */
    @Overwrite(remap = false)
    public static void _glFramebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.framebufferTexture2D(target, attachment, texTarget, texture, level);
    }

    // ==================== PROGRAM ====================

    /**
     * @author Vitra
     * @reason Generate DirectX shader program ID
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        RenderSystem.assertOnRenderThread();
        return programIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX program usage
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int program) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.useProgram(program);
    }

    /**
     * @author Vitra
     * @reason Redirect to DirectX program deletion
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int program) {
        RenderSystem.assertOnRenderThread();
        VitraRenderer r = getRenderer(); if (r != null) r.deleteProgram(program);
    }

    // ==================== BUFFER ====================

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer generation (pixel buffers)
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        RenderSystem.assertOnRenderThread();
        return org.lwjgl.opengl.GL15.glGenBuffers();
    }

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer binding
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int target, int buffer) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
    }

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer data
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, ByteBuffer data, int usage) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL15.glBufferData(target, data, usage);
    }

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer data (size only)
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, long size, int usage) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL15.glBufferData(target, size, usage);
    }

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer unmap
     */
    @Overwrite(remap = false)
    public static void _glUnmapBuffer(int target) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL15.glUnmapBuffer(target);
    }

    /**
     * @author Vitra
     * @reason Delegate to GL15 for buffer deletion
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int buffer) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
    }

    // ==================== VAO ====================

    /**
     * @author Vitra
     * @reason DirectX doesn't use VAOs - return dummy ID
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        RenderSystem.assertOnRenderThread();
        return 1;
    }

    // ==================== ERROR ====================

    /**
     * @author Vitra
     * @reason No OpenGL errors in DirectX
     */
    @Overwrite(remap = false)
    public static int _getError() {
        return 0;
    }
}
