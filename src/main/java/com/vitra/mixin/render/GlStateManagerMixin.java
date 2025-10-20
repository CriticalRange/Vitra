package com.vitra.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.jni.VitraNativeRenderer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Critical mixin to intercept ALL GlStateManager calls and redirect to DirectX 11
 * Pattern based on VulkanMod's GlStateManagerM - prevents OpenGL calls
 */
@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GlStateManager");
    private static final AtomicInteger textureIdCounter = new AtomicInteger(1);
    private static final AtomicInteger framebufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger renderbufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger bufferIdCounter = new AtomicInteger(1);
    private static final AtomicInteger programIdCounter = new AtomicInteger(1);

    // ==================== TEXTURE OPERATIONS ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture binding to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int textureId) {
        try {
            VitraNativeRenderer.bindTexture(textureId);
        } catch (Exception e) {
            // Silent error following VulkanMod pattern
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Generate DirectX 11 texture ID instead of OpenGL
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        RenderSystem.assertOnRenderThread();
        // Generate unique texture ID (DirectX handles created on-demand during texImage2D)
        return textureIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture deletion to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int textureId) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.deleteTexture(textureId);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texImage2D to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable IntBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        try {
            // Convert IntBuffer to ByteBuffer if needed
            ByteBuffer byteBuffer = null;
            if (pixels != null) {
                byteBuffer = ByteBuffer.allocateDirect(pixels.remaining() * 4);
                while (pixels.hasRemaining()) {
                    int value = pixels.get();
                    byteBuffer.put((byte)(value & 0xFF));
                    byteBuffer.put((byte)((value >> 8) & 0xFF));
                    byteBuffer.put((byte)((value >> 16) & 0xFF));
                    byteBuffer.put((byte)((value >> 24) & 0xFF));
                }
                byteBuffer.flip();
            }
            VitraNativeRenderer.texImage2D(target, level, internalFormat, width, height, border, format, type, byteBuffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texSubImage2D to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.texSubImage2D(target, level, offsetX, offsetY, width, height, format, type, pixels);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect active texture to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int texture) {
        try {
            VitraNativeRenderer.activeTexture(texture);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture parameter to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _texParameter(int target, int pname, int param) {
        try {
            VitraNativeRenderer.texParameteri(target, pname, param);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture parameter (float) to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _texParameter(int target, int pname, float param) {
        // TODO: Implement float texture parameters if needed
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect getTexLevelParameter to DirectX 11
     */
    @Overwrite(remap = false)
    public static int _getTexLevelParameter(int target, int level, int pname) {
        try {
            return VitraNativeRenderer.getTexLevelParameter(target, level, pname);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect pixelStore to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _pixelStore(int pname, int param) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.pixelStore(pname, param);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== BLEND STATE ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect blend enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.enableBlend();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect blend disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.disableBlend();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect blend function to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _blendFunc(int srcFactor, int dstFactor) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.blendFunc(srcFactor, dstFactor);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect blend function separate to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect blend equation to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _blendEquation(int mode) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.blendEquation(mode);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== DEPTH STATE ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect depth test enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.enableDepthTest();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect depth test disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.disableDepthTest();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect depth function to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int func) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            VitraNativeRenderer.depthFunc(func);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect depth mask to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean flag) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.depthMask(flag);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== CULL STATE ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect cull enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        try {
            VitraNativeRenderer.enableCull();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect cull disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        try {
            VitraNativeRenderer.disableCull();
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== SCISSOR/VIEWPORT ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect scissor enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        // DirectX 11 scissor is always available, just set the rect
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect scissor disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        try {
            // Reset scissor to full viewport
            VitraNativeRenderer.resetScissor();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect scissor box to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        try {
            VitraNativeRenderer.setScissor(x, y, width, height);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect viewport to DirectX 11
     */
    @Redirect(method = "_viewport", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"), remap = false)
    private static void redirectViewport(int x, int y, int width, int height) {
        try {
            VitraNativeRenderer.setViewport(x, y, width, height);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== CLEAR OPERATIONS ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect clear color to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _clearColor(float red, float green, float blue, float alpha) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            VitraNativeRenderer.setClearColor(red, green, blue, alpha);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect clear depth to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _clearDepth(double depth) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.clearDepth((float)depth);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect clear to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _clear(int mask, boolean checkError) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.clear(mask);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== COLOR/POLYGON STATE ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect color mask to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.colorMask(red, green, blue, alpha);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect polygon mode to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _polygonMode(int face, int mode) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.setPolygonMode(mode);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect polygon offset enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.enablePolygonOffset();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect polygon offset disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.disablePolygonOffset();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect polygon offset to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _polygonOffset(float factor, float units) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.polygonOffset(factor, units);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== COLOR LOGIC OP ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect color logic op enable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _enableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.enableColorLogicOp();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect color logic op disable to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.disableColorLogicOp();
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect logic op to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _logicOp(int opcode) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.logicOp(opcode);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== FRAMEBUFFER OPERATIONS ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Generate DirectX 11 framebuffer ID
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        RenderSystem.assertOnRenderThread();
        return framebufferIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Generate DirectX 11 renderbuffer ID
     */
    @Overwrite(remap = false)
    public static int glGenRenderbuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        return renderbufferIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect framebuffer bind to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int target, int framebuffer) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.bindFramebuffer(target, framebuffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect framebuffer texture attachment to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.framebufferTexture2D(target, attachment, textarget, texture, level);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect renderbuffer bind to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glBindRenderbuffer(int target, int renderbuffer) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            VitraNativeRenderer.bindRenderbuffer(target, renderbuffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect framebuffer renderbuffer attachment to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            VitraNativeRenderer.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect renderbuffer storage to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glRenderbufferStorage(int target, int internalformat, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            VitraNativeRenderer.renderbufferStorage(target, internalformat, width, height);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 framebuffers always complete
     */
    @Overwrite(remap = false)
    public static int glCheckFramebufferStatus(int target) {
        RenderSystem.assertOnRenderThreadOrInit();
        return 0x8CD5; // GL_FRAMEBUFFER_COMPLETE
    }

    // ==================== BUFFER OPERATIONS ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Generate DirectX 11 buffer ID
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        return bufferIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer bind to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int target, int buffer) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.bindBuffer(target, buffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer data (ByteBuffer) to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, ByteBuffer data, int usage) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.bufferData(target, data, usage);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer data (size) to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int target, long size, int usage) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.bufferData(target, size, usage);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer mapping to DirectX 11
     */
    @Overwrite(remap = false)
    @Nullable
    public static ByteBuffer _glMapBuffer(int target, int access) {
        RenderSystem.assertOnRenderThreadOrInit();
        try {
            return VitraNativeRenderer.mapBuffer(target, access);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer unmapping to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glUnmapBuffer(int target) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.unmapBuffer(target);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect buffer deletion to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int buffer) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.deleteBuffer(buffer);
        } catch (Exception e) {
            // Silent error
        }
    }

    // ==================== SHADER/PROGRAM OPERATIONS ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect program use to DirectX 11
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int program) {
        RenderSystem.assertOnRenderThread();
        try {
            VitraNativeRenderer.useProgram(program);
        } catch (Exception e) {
            // Silent error
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Generate DirectX 11 program ID
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        RenderSystem.assertOnRenderThread();
        return programIdCounter.getAndIncrement();
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect program deletion to DirectX 11
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int program) {
        RenderSystem.assertOnRenderThread();
        // DirectX programs are managed by shader manager
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason VAOs not used in DirectX 11
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        RenderSystem.assertOnRenderThreadOrInit();
        return 0;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Vertex attrib arrays handled by input layout in DirectX 11
     */
    @Overwrite(remap = false)
    public static void _disableVertexAttribArray(int index) {
        // NO-OP: DirectX 11 uses input layout instead
    }

    // ==================== ERROR HANDLING ====================

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX 11 uses debug layer instead of glGetError
     */
    @Overwrite(remap = false)
    public static int _getError() {
        return 0; // No error
    }
}
