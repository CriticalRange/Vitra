package com.vitra.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.vitra.VitraMod;
import com.vitra.render.bgfx.BgfxGpuDevice;
import net.minecraft.util.TimeSource;
import net.minecraft.client.renderer.DynamicUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to completely replace OpenGL RenderSystem initialization with BGFX DirectX 11
 * Following VulkanMod approach with @Overwrite
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraRenderSystemMixin");

    @Shadow
    private static Thread renderThread;

    @Shadow
    private static String apiDescription;

    // Static fields to track BGFX device state
    private static DynamicUniforms dynamicUniforms;
    private static GpuBuffer globalSettingsUniform;

    /**
     * Complete replacement of initRenderer to bypass GlDevice creation entirely
     * @author Vitra
     * @reason Replace OpenGL GlDevice initialization with BGFX DirectX 11 backend
     */
    @Overwrite(remap = false)
    public static void initRenderer(long window, int debugFlags, boolean synchronous, java.util.function.BiFunction contextFactory, boolean validateState) {
        LOGGER.info("=== VITRA DEBUG: RenderSystem.initRenderer() COMPLETE OVERRIDE ===");
        LOGGER.info("Overwriting RenderSystem.initRenderer() - using pure BGFX DirectX 11 instead of OpenGL");
        LOGGER.info("Window handle: 0x{}, debugFlags: {}, synchronous: {}, validateState: {}", Long.toHexString(window), debugFlags, synchronous, validateState);
        LOGGER.info("contextFactory provided: {}", contextFactory != null ? "YES (will be ignored)" : "NO");

        try {
            // Initialize GLFW for window management (needed for BGFX)
            if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                LOGGER.warn("GLFW not initialized, initializing now for BGFX DirectX 11");
                if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                    throw new RuntimeException("Failed to initialize GLFW for BGFX DirectX 11");
                }
            }
            LOGGER.info("GLFW initialized for BGFX DirectX 11 window management");

            // Set the API description for crash reports - this replaces GLX.getOpenGLVersionString()
            apiDescription = "BGFX DirectX 11 Backend (replacing OpenGL)";
            LOGGER.info("=== VITRA DEBUG: Set API description to: {} ===", apiDescription);
            LOGGER.info("This should prevent RivaTuner from detecting OpenGL");

            // Try to update renderer info for system exposure
            try {
                com.vitra.render.bgfx.BgfxRendererExposer.updateRendererInfo();
            } catch (Exception e) {
                LOGGER.warn("Failed to update renderer info: {}", e.getMessage());
            }

            // Ensure BGFX is initialized instead of OpenGL GlDevice
            if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                LOGGER.info("BGFX DirectX 11 already initialized - skipping OpenGL GlDevice creation");
            } else {
                LOGGER.info("BGFX DirectX 11 not yet ready - will initialize during window creation");
            }

            // Set render thread priority like VulkanMod does
            renderThread = Thread.currentThread();
            renderThread.setPriority(Thread.NORM_PRIORITY + 2);

            // Initialize BGFX device state tracking
            initializeBgfxDeviceState();

            LOGGER.info("BGFX DirectX 11 renderer initialized successfully - no OpenGL GlDevice created");
            LOGGER.info("=== VITRA DEBUG: OpenGL COMPLETELY BYPASSED IN initRenderer() ===");

        } catch (Exception e) {
            LOGGER.error("Error in BGFX DirectX 11 renderer initialization", e);
            throw new RuntimeException("BGFX DirectX 11 initialization failed", e);
        }
    }

    /**
     * Provide BGFX device instead of OpenGL device
     * @author Vitra
     * @reason Return BGFX DirectX 11 device instead of OpenGL GlDevice
     */
    @Overwrite(remap = false)
    public static GpuDevice getDevice() {
        LOGGER.info("=== VITRA DEBUG: RenderSystem.getDevice() called - returning BGFX DirectX 11 device (NOT OpenGL) ===");
        GpuDevice device = BgfxGpuDevice.getInstance();
        LOGGER.info("Device class: {}", device.getClass().getName());
        return device;
    }

    /**
     * Provide BGFX device instead of OpenGL device
     * @author Vitra
     * @reason Return BGFX DirectX 11 device instead of OpenGL GlDevice
     */
    @Overwrite(remap = false)
    public static GpuDevice tryGetDevice() {
        LOGGER.info("=== VITRA DEBUG: RenderSystem.tryGetDevice() called - returning BGFX DirectX 11 device (NOT OpenGL) ===");
        GpuDevice device = BgfxGpuDevice.getInstance();
        LOGGER.info("tryGetDevice class: {}", device.getClass().getName());
        return device;
    }

    /**
     * Initialize BGFX device state to prevent "device not initialized" errors
     */
    private static void initializeBgfxDeviceState() {
        try {
            LOGGER.info("Initializing BGFX device state...");

            // Create dummy dynamic uniforms to satisfy RenderSystem requirements
            dynamicUniforms = new DynamicUniforms();

            // Create a dummy global settings uniform buffer
            BgfxGpuDevice device = BgfxGpuDevice.getInstance();
            globalSettingsUniform = device.createBuffer(() -> "GlobalSettings", 0, 256);

            LOGGER.info("BGFX device state initialized successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize BGFX device state", e);
        }
    }

    /**
     * Return BGFX dynamic uniforms
     * @author Vitra
     * @reason Provide BGFX dynamic uniforms instead of OpenGL uniforms
     */
    @Overwrite(remap = false)
    public static DynamicUniforms getDynamicUniforms() {
        if (dynamicUniforms == null) {
            LOGGER.warn("getDynamicUniforms() called before initialization - creating on demand");
            dynamicUniforms = new DynamicUniforms();
        }
        return dynamicUniforms;
    }

    /**
     * Return BGFX global settings uniform
     * @author Vitra
     * @reason Provide BGFX global settings uniform instead of OpenGL uniform
     */
    @Overwrite(remap = false)
    public static GpuBuffer getGlobalSettingsUniform() {
        if (globalSettingsUniform == null) {
            LOGGER.warn("getGlobalSettingsUniform() called before initialization - creating on demand");
            BgfxGpuDevice device = BgfxGpuDevice.getInstance();
            globalSettingsUniform = device.createBuffer(() -> "GlobalSettings", 0, 256);
        }
        return globalSettingsUniform;
    }

    /**
     * Override backend initialization to prevent OpenGL initialization
     * @author Vitra
     * @reason Prevent OpenGL backend initialization, use BGFX DirectX 11 only
     */
    @Overwrite(remap = false)
    public static TimeSource.NanoTimeSource initBackendSystem() {
        LOGGER.info("initBackendSystem() called - preventing OpenGL backend, using BGFX DirectX 11");
        // Return a simple time source without initializing OpenGL backend
        return System::nanoTime;
    }

    /**
     * Override render thread initialization to prevent OpenGL setup
     * @author Vitra
     * @reason Prevent OpenGL render thread setup, use BGFX DirectX 11 only
     */
    @Overwrite(remap = false)
    public static void initRenderThread() {
        LOGGER.info("initRenderThread() called - skipping OpenGL setup, using BGFX DirectX 11");
        // Set render thread but don't initialize OpenGL context
        renderThread = Thread.currentThread();
        renderThread.setName("Render thread");
    }
}