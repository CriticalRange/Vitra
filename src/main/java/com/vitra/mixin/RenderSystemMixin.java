package com.vitra.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.dx11.DirectX11GpuDevice;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TimeSource;
import com.vitra.VitraMod;
import com.vitra.debug.VitraDebugUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.lang.reflect.Field;
import java.util.function.BiFunction;

/**
 * Priority 1: RenderSystem Initialization and State Mixin
 *
 * This mixin implements the "yer değiştirme" (replacement) strategy:
 * - Prevent ALL OpenGL initialization (context, capabilities, default state)
 * - Minecraft's render loop continues, but NO OpenGL calls execute
 * - DirectX 11 JNI handles ALL actual rendering
 *
 * IMPORTANT: Uses official Mojang-mapped class names for Minecraft 1.21.8:
 * - TimeSource.NanoTimeSource (not TimeSupplier.Nanoseconds)
 * - ResourceLocation (not Identifier - that's Yarn mapping)
 *
 * STRATEGY: Following VulkanMod's proven approach:
 * - Use @Overwrite for initialization methods (setupDefaultState, initRenderer)
 * - Use @Redirect for glfwSwapBuffers() call interception (more robust than @Overwrite)
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderSystemMixin");
    private static int redirectCallCount = 0;
    private static long lastFrameTime = 0;
    private static long frameStartTime = 0;

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL renderer initialization with DirectX 11 JNI
     *
     * Original: Initializes OpenGL default rendering state
     * Replacement: Skip OpenGL state setup, DirectX 11 JNI handles its own state internally
     *
     * Minecraft 1.21.8 signature: setupDefaultState() - no parameters
     */
    @Overwrite(remap = false)
    public static void setupDefaultState() {
        LOGGER.info("RenderSystem.setupDefaultState intercepted - DirectX 11 JNI handles state internally");
        // NO-OP: DirectX 11 JNI will set up its own state internally
        // We don't call any OpenGL functions here
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL device initialization with DirectX 11 JNI
     *
     * Original: Creates GlDevice for OpenGL rendering
     * Replacement: Create DirectX11GpuDevice and set it in RenderSystem
     */
    @Overwrite(remap = false)
    public static void initRenderer(long windowHandle, int debugVerbosity, boolean sync,
                                     BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter,
                                     boolean renderDebugLabels) {
        LOGGER.info("RenderSystem.initRenderer intercepted - creating DirectX 11 GpuDevice");
        LOGGER.info("Window handle: 0x{}, debugVerbosity: {}, sync: {}, renderDebugLabels: {}",
            Long.toHexString(windowHandle), debugVerbosity, sync, renderDebugLabels);

        try {
            // Create our DirectX 11 GpuDevice implementation
            DirectX11GpuDevice device = new DirectX11GpuDevice(windowHandle, debugVerbosity, sync);

            // Set the device in RenderSystem using reflection
            // We need to access the private static DEVICE field
            Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
            deviceField.setAccessible(true);
            deviceField.set(null, device);

            LOGGER.info("✓ DirectX 11 GpuDevice created and set in RenderSystem");

            // Also set the apiDescription field
            try {
                Field apiDescField = RenderSystem.class.getDeclaredField("apiDescription");
                apiDescField.setAccessible(true);
                apiDescField.set(null, device.getImplementationInformation());
            } catch (Exception e) {
                LOGGER.warn("Could not set apiDescription field", e);
            }

            // Initialize dynamicUniforms field (lowercase, not DYNAMIC_UNIFORMS)
            // This is required by Minecraft 1.21.8's getDynamicUniforms() method
            try {
                Field dynamicUniformsField = RenderSystem.class.getDeclaredField("dynamicUniforms");
                dynamicUniformsField.setAccessible(true);

                Class<?> dynamicUniformsClass = dynamicUniformsField.getType();
                LOGGER.info("Found dynamicUniforms field of type: {}", dynamicUniformsClass.getName());

                // Create a new DynamicUniforms instance using reflection
                // DynamicUniforms has a no-arg constructor: public DynamicUniforms()
                Object dynamicUniforms = dynamicUniformsClass.getDeclaredConstructor().newInstance();
                dynamicUniformsField.set(null, dynamicUniforms);
                LOGGER.info("✓ dynamicUniforms initialized with new {} instance", dynamicUniformsClass.getSimpleName());
            } catch (NoSuchFieldException e) {
                LOGGER.debug("No dynamicUniforms field found (may not be needed in this version)");
            } catch (Exception e) {
                LOGGER.warn("Could not initialize dynamicUniforms field", e);
            }

        } catch (NoSuchFieldException e) {
            LOGGER.error("CRITICAL: Could not find DEVICE field in RenderSystem - field name may have changed!", e);
            throw new RuntimeException("Failed to set DirectX 11 GpuDevice in RenderSystem", e);
        } catch (IllegalAccessException e) {
            LOGGER.error("CRITICAL: Could not access DEVICE field in RenderSystem", e);
            throw new RuntimeException("Failed to set DirectX 11 GpuDevice in RenderSystem", e);
        } catch (Exception e) {
            LOGGER.error("CRITICAL: Unexpected error creating DirectX 11 GpuDevice", e);
            throw new RuntimeException("Failed to create DirectX 11 GpuDevice", e);
        }
    }

    /**
     * CRITICAL FIX: Override flipFrame for Minecraft 1.21.8 DirectX 11 rendering
     *
     * Minecraft 1.21.8 uses a new rendering architecture:
     * - GpuDevice interface (DirectX11GpuDevice) handles all rendering
     * - CommandEncoder.presentTexture() is called by Minecraft to present frames
     * - We DON'T need manual frame submission here anymore
     *
     * What we DO need:
     * 1. glfwPollEvents() - Process window/input events
     * 2. Tracy cleanup - End frame capture if enabled
     *
     * What we DON'T need:
     * - Manual DirectX frame submission (handled by CommandEncoder.presentTexture())
     * - VitraRenderer checks (old BGFX architecture, no longer used)
     * - glfwSwapBuffers() (DirectX swap chain presents automatically)
     *
     * @author Vitra
     * @reason Replace OpenGL buffer swap with DirectX 11 rendering pipeline
     */
    @Overwrite(remap = false)
    public static void flipFrame(long window, com.mojang.blaze3d.TracyFrameCapture capturer) {
        frameStartTime = System.nanoTime();
        redirectCallCount++;

        boolean verboseLog = (redirectCallCount <= 5) || (redirectCallCount % 120 == 0);

        if (redirectCallCount == 1) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  DIRECTX 11 RENDERING ACTIVE (Minecraft 1.21.8)           ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Rendering Pipeline:                                        ║");
            LOGGER.info("║   • DirectX11GpuDevice    - GPU abstraction                ║");
            LOGGER.info("║   • DirectX11CommandEncoder - Command recording            ║");
            LOGGER.info("║   • DirectX11RenderPass   - Render pass execution          ║");
            LOGGER.info("║   • presentTexture()      - Automatic frame presentation   ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Window:  0x{}", Long.toHexString(window));
            LOGGER.info("║ Thread:  {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // STEP 1: Poll events FIRST
        // This must happen before any potential GPU waits to prevent window freezing
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            if (verboseLog) {
                LOGGER.info("[Frame #{}] glfwPollEvents() called", redirectCallCount);
            }
        } catch (Exception e) {
            LOGGER.error("Exception during glfwPollEvents()", e);
        }

        // STEP 2: Tracy frame capture cleanup (if enabled)
        if (capturer != null) {
            try {
                capturer.endFrame();
                if (verboseLog) {
                    LOGGER.info("[Frame #{}] capturer.endFrame() called", redirectCallCount);
                }
            } catch (Exception e) {
                LOGGER.error("Exception during capturer.endFrame()", e);
            }
        }

        // Calculate frame time for performance monitoring
        long frameDelta = 0;
        if (lastFrameTime > 0) {
            frameDelta = (frameStartTime - lastFrameTime) / 1_000_000; // ms
        }
        lastFrameTime = frameStartTime;

        if (verboseLog) {
            LOGGER.info("[Frame #{}] Frame time: {}ms (target: ~16.67ms for 60 FPS)",
                redirectCallCount, frameDelta > 0 ? frameDelta : "N/A");
        }

        // Warn if frame time is excessive
        if (frameDelta > 100) { // More than 100ms = less than 10 FPS
            LOGGER.warn("[PERFORMANCE] Frame #{} took {}ms - possible stutter or hang!", redirectCallCount, frameDelta);
        }

        // NOTE: Frame presentation is handled automatically by:
        // - CommandEncoder.presentTexture() called by Minecraft's render loop
        // - DirectX 11 swap chain presents the frame when ready
        // We do NOT call any manual frame submission here.
    }

    // ============================================================================
    // CRITICAL: Minecraft 1.21.8 Texture Binding Interception
    // ============================================================================

    /**
     * CRITICAL FIX: Intercept setShaderTexture to bind textures to DirectX 11
     *
     * Minecraft 1.21.8 uses RenderSystem.setShaderTexture(int, GpuTextureView) to bind textures
     * before draw calls. We MUST intercept this to bind the texture to our DirectX 11 renderer.
     *
     * Without this, textures are not bound and we only see vertex colors (the blue/black mess).
     *
     * @param index The texture slot (0-15, typically 0 for main texture)
     * @param texture The GpuTextureView to bind
     * @param ci Mixin callback info
     */
    @Inject(method = "setShaderTexture(ILcom/mojang/blaze3d/textures/GpuTextureView;)V", at = @At("HEAD"), remap = false)
    private static void onSetShaderTexture(int index, GpuTextureView texture, CallbackInfo ci) {
        try {
            if (texture == null) {
                LOGGER.debug("Texture slot {} unbind (null texture)", index);
                // Unbind texture in DirectX 11
                if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
                    com.vitra.render.jni.VitraNativeRenderer.bindTexture(0, index);
                }
                return;
            }

            // Get the underlying GpuTexture from the view
            com.mojang.blaze3d.textures.GpuTexture gpuTexture = texture.texture();

            if (gpuTexture instanceof com.vitra.render.dx11.DirectX11GpuTexture dx11Texture) {
                long nativeHandle = dx11Texture.getNativeHandle();

                if (nativeHandle != 0) {
                    // Bind texture to DirectX 11 shader slot
                    com.vitra.render.jni.VitraNativeRenderer.bindTexture(nativeHandle, index);

                    LOGGER.debug("✓ Texture bound to slot {}: handle=0x{} ({})",
                        index, Long.toHexString(nativeHandle), gpuTexture.getLabel());
                } else {
                    LOGGER.warn("Texture {} has no native handle - not created yet?", gpuTexture.getLabel());
                }
            } else {
                LOGGER.warn("Texture is not DirectX11GpuTexture: {}",
                    gpuTexture != null ? gpuTexture.getClass().getName() : "null");
            }
        } catch (Exception e) {
            LOGGER.error("Exception in onSetShaderTexture", e);
        }
    }

    // ============================================================================
    // NOTE: Minecraft 1.21.8 RenderSystem Refactor
    // ============================================================================

    /**
     * Minecraft 1.21.8 has completely refactored RenderSystem to use a new GPU abstraction layer.
     * The old OpenGL-style methods (setShaderTexture with ResourceLocation, setShader with Supplier,
     * enableDepthTest, disableBlend, blendFunc, etc.) DO NOT EXIST in this version.
     *
     * Instead, Minecraft 1.21.8 uses:
     * - setShaderTexture(int, GpuTextureView) instead of setShaderTexture(int, ResourceLocation)
     * - GpuBufferSlice, RenderPass, and other new abstractions
     *
     * State tracking is handled at the lower level in GlStateManagerMixin, which intercepts
     * the actual OpenGL state calls (_enableBlend, _depthFunc, etc.) that still exist.
     *
     * NOW INTERCEPTED: setShaderTexture(int, GpuTextureView) - see onSetShaderTexture() above
     */
}
