package com.vitra.mixin.window;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simplified Window mixin for Minecraft 26.1.
 * 
 * Note: 26.1 changed how Window is initialized - it no longer directly calls
 * glfwCreateWindow in the constructor. Window creation is handled by initializeBackend().
 * 
 * For now, we just override key methods without trying to redirect GLFW calls.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/WindowMixin");

    @Shadow private long handle;
    @Shadow private boolean vsync;
    @Shadow private boolean fullscreen;
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;
    @Shadow private int width;
    @Shadow private int height;

    @Shadow public abstract long handle();

    /**
     * Store window handle after construction for DirectX initialization.
     * CRITICAL: This is where D3D11 is actually initialized!
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void storeWindowHandle(WindowEventHandler windowEventHandler, DisplayData displayData,
                                   String string, String string2, GpuBackend[] gpuBackends,
                                   ShaderSource shaderSource, GpuDebugOptions debugOptions, CallbackInfo ci) {
        try {
            LOGGER.info("Window constructed with handle: 0x{}", Long.toHexString(this.handle));
            com.vitra.render.IVitraRenderer renderer = VitraMod.getRenderer();
            if (renderer instanceof com.vitra.render.AbstractRenderer abstractRenderer) {
                abstractRenderer.setWindowHandle(this.handle);
                LOGGER.info("Stored window handle in renderer for DirectX initialization");
            }
            
            // CRITICAL: Initialize VitraGpuDevice NOW that we have a window handle
            // RenderSystem.initRenderer() created the device but couldn't init D3D11 (no window)
            try {
                com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
                if (device instanceof com.vitra.render.bridge.VitraGpuDevice vitraDevice) {
                    boolean success = vitraDevice.initialize(this.handle, this.framebufferWidth, this.framebufferHeight, false);
                    if (success) {
                        LOGGER.info("✓ DirectX 11 initialized successfully via VitraGpuDevice");
                    } else {
                        LOGGER.error("✗ Failed to initialize DirectX 11 via VitraGpuDevice");
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to initialize VitraGpuDevice", e);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to store window handle", e);
        }
    }

    /**
     * Override VSync to use DirectX swap chain.
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        try {
            LOGGER.info("VSync changed to: {}", vsync);
            VitraD3D11Renderer.recreateSwapChain();
        } catch (Exception e) {
            LOGGER.error("Failed to update VSync", e);
        }
    }

    /**
     * Override display update for DirectX frame presentation.
     */
    @Overwrite
    public void updateDisplay(TracyFrameCapture tracyFrameCapture) {
        RenderSystem.flipFrame(tracyFrameCapture);
    }

    /**
     * Handle framebuffer resize for DirectX.
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.handle && width > 0 && height > 0) {
            this.framebufferWidth = width;
            this.framebufferHeight = height;
            LOGGER.debug("Framebuffer resize: {}x{}", width, height);
            try {
                VitraD3D11Renderer.recreateSwapChain();
            } catch (Exception e) {
                LOGGER.error("Failed to recreate swap chain", e);
            }
        }
    }

    /**
     * Handle window resize for DirectX.
     */
    @Overwrite
    private void onResize(long window, int width, int height) {
        this.width = width;
        this.height = height;
        if (width > 0 && height > 0) {
            LOGGER.debug("Window resize: {}x{}", width, height);
            try {
                VitraD3D11Renderer.recreateSwapChain();
            } catch (Exception e) {
                LOGGER.error("Failed to recreate swap chain", e);
            }
        }
    }
}
