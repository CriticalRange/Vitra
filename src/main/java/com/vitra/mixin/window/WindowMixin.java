package com.vitra.mixin.window;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Critical mixin to prevent OpenGL context creation and enable DirectX
 * Pattern from VulkanMod - redirects OpenGL calls and sets GLFW_NO_API
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/WindowMixin");

    @Final @Shadow private long window;

    @Shadow private boolean vsync;

    @Shadow protected abstract void updateFullscreen(boolean bl);

    @Shadow private boolean fullscreen;

    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();

    // Track fullscreen dirty state for deferred switching (matches VulkanMod pattern)
    private static boolean fullscreenDirty = false;

    /**
     * CRITICAL: Redirect all glfwWindowHint calls to prevent OpenGL hints
     * This stops Minecraft from requesting an OpenGL context
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void redirectWindowHint(int hint, int value) {
        // NO-OP: Prevent OpenGL hints, we'll set our own
    }

    /**
     * CRITICAL: Prevent OpenGL context from being made current
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void redirectMakeContextCurrent(long window) {
        // NO-OP: Don't make OpenGL context current
    }

    /**
     * CRITICAL: Prevent OpenGL capabilities from being created
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities redirectCreateCapabilities() {
        // Return null - no OpenGL capabilities needed
        return null;
    }

    // DirectX device not initialized yet during Window construction
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;maxSupportedTextureSize()I"))
    private int redirectMaxTextureSize() {
        return 0;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowSizeLimits(JIIII)V"))
    private void redirectSetWindowSizeLimits(long window, int minwidth, int minheight, int maxwidth, int maxheight) {
    }

    /**
     * CRITICAL: Inject BEFORE glfwCreateWindow to set GLFW_NO_API
     * This tells GLFW to NOT create an OpenGL context
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void setNoApiHint(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        LOGGER.info("Setting GLFW_CLIENT_API to GLFW_NO_API for DirectX");
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    }

    /**
     * Store window handle in VitraRenderer after Window is constructed
     */
    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void storeWindowHandle(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        try {
            LOGGER.info("Window constructed with handle: 0x{} (GLFW_NO_API)", Long.toHexString(this.window));

            // Store the window handle in renderer (works for both DirectX and DirectX 12)
            com.vitra.render.IVitraRenderer renderer = VitraMod.getRenderer();
            if (renderer instanceof com.vitra.render.AbstractRenderer abstractRenderer) {
                abstractRenderer.setWindowHandle(this.window);
                LOGGER.info("Stored window handle in {} for initialization", renderer.getClass().getSimpleName());
            } else {
                LOGGER.error("Renderer is not AbstractRenderer, cannot store window handle!");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to store window handle", e);
        }
    }

    /**
     * CRITICAL: Override VSync management to use DirectX swap chain
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX swap chain handles VSync, not GLFW glfwSwapInterval()
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        try {
            // DirectX swap chain VSync is set during creation
            // Changing VSync requires recreating the swap chain
            LOGGER.info("VSync changed to: {} - recreating DirectX swap chain", vsync);
            VitraD3D11Renderer.recreateSwapChain();
        } catch (Exception e) {
            LOGGER.error("Failed to update VSync setting", e);
        }
    }

    /**
     * CRITICAL: Override display update for DirectX frame presentation and deferred fullscreen
     * @author Vitra (adapted from VulkanMod)
     * @reason Custom frame presentation flow for DirectX
     */
    @Overwrite
    public void updateDisplay() {
        // Call flipFrame which eventually calls presentDirectX11Frame()
        RenderSystem.flipFrame(this.window);

        // Handle deferred fullscreen switching (avoids mid-frame mode changes)
        if (fullscreenDirty) {
            fullscreenDirty = false;
            this.updateFullscreen(this.vsync);
        }
    }

    /**
     * CRITICAL: Override fullscreen toggle for deferred switching
     * @author Vitra (adapted from VulkanMod)
     * @reason Deferred fullscreen switching prevents mid-frame swap chain errors
     */
    @Overwrite
    public void toggleFullScreen() {
        this.fullscreen = !this.fullscreen;
        fullscreenDirty = true;
        LOGGER.info("Fullscreen toggled (deferred): {}", this.fullscreen);
    }

    /**
     * CRITICAL: Override framebuffer resize to recreate DirectX swap chain
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX swap chain buffers must match framebuffer size
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.window) {
            if (width > 0 && height > 0) {
                this.framebufferWidth = width;
                this.framebufferHeight = height;

                LOGGER.info("Framebuffer resized to {}x{} - recreating DirectX swap chain", width, height);

                // Schedule swap chain recreation (matches VulkanMod's Renderer.scheduleSwapChainUpdate())
                try {
                    VitraD3D11Renderer.recreateSwapChain();
                } catch (Exception e) {
                    LOGGER.error("Failed to recreate swap chain on framebuffer resize", e);
                }
            }
        }
    }

    /**
     * CRITICAL: Override window resize to recreate DirectX swap chain
     * @author Vitra (adapted from VulkanMod)
     * @reason DirectX swap chain buffers must match window size
     */
    @Overwrite
    private void onResize(long window, int width, int height) {
        this.width = width;
        this.height = height;

        if (width > 0 && height > 0) {
            LOGGER.info("Window resized to {}x{} - recreating DirectX swap chain", width, height);

            // Schedule swap chain recreation (matches VulkanMod's Renderer.scheduleSwapChainUpdate())
            try {
                VitraD3D11Renderer.recreateSwapChain();
            } catch (Exception e) {
                LOGGER.error("Failed to recreate swap chain on window resize", e);
            }
        }
    }
}
