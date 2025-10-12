package com.vitra.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TimeSource;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;

/**
 * Priority 1: RenderSystem Initialization and State Mixin
 *
 * This mixin implements the "yer değiştirme" (replacement) strategy:
 * - Prevent ALL OpenGL initialization (context, capabilities, default state)
 * - Minecraft's render loop continues, but NO OpenGL calls execute
 * - BGFX DirectX 11 handles ALL actual rendering
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
     * @reason Complete replacement of OpenGL renderer initialization with BGFX DirectX 11
     *
     * Original: Initializes OpenGL default rendering state
     * Replacement: Skip OpenGL state setup, BGFX handles its own state internally
     *
     * Minecraft 1.21.8 signature: setupDefaultState() - no parameters
     */
    @Overwrite(remap = false)
    public static void setupDefaultState() {
        LOGGER.info("RenderSystem.setupDefaultState intercepted - BGFX handles state internally");
        // NO-OP: BGFX will set up its own DirectX 11 state internally
        // We don't call any OpenGL functions here
    }

    /**
     * @author Vitra
     * @reason Complete replacement of OpenGL device initialization with BGFX device
     *
     * Original: Creates GlDevice for OpenGL rendering
     * Replacement: Do nothing - VitraGpuDevice is returned via RenderSystemDeviceMixin.getDevice()
     */
    @Overwrite(remap = false)
    public static void initRenderer(long windowHandle, int debugVerbosity, boolean sync,
                                     BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter,
                                     boolean renderDebugLabels) {
        LOGGER.info("RenderSystem.initRenderer intercepted - using BGFX DirectX 11 instead of OpenGL");
        LOGGER.info("Window handle: 0x{}, debugVerbosity: {}, sync: {}, renderDebugLabels: {}",
            Long.toHexString(windowHandle), debugVerbosity, sync, renderDebugLabels);

        // NO-OP: Do not create OpenGL device
        // getDevice() calls are intercepted in RenderSystemDeviceMixin to return VitraGpuDevice
        // BGFX was already initialized in WindowMixin with the window handle
    }

    /**
     * CRITICAL FIX: Override flipFrame to fix event polling order
     *
     * BGFX REQUIREMENT: glfwPollEvents() MUST be called BEFORE bgfx_frame()
     * - This is documented in BGFX examples (HelloBGFX.java line 110, 138)
     * - If bgfx_frame() is called first, it blocks waiting for GPU
     * - While blocked, events aren't processed → window freeze
     *
     * Original Minecraft order (WRONG):
     * 1. glfwSwapBuffers()  → we replace with bgfx_frame()
     * 2. glfwPollEvents()   → too late, already blocked!
     * 3. capturer.endFrame()
     *
     * Correct BGFX order (from examples):
     * 1. glfwPollEvents()   → process events FIRST
     * 2. bgfx_frame()       → then submit frame (may block)
     * 3. capturer.endFrame() → cleanup
     *
     * Why @Overwrite instead of @Redirect:
     * - Need to reorder the calls completely
     * - @Redirect can only replace individual calls, not reorder them
     * - This is critical for preventing window freeze
     */
    @Overwrite(remap = false)
    public static void flipFrame(long window, com.mojang.blaze3d.TracyFrameCapture capturer) {
        frameStartTime = System.nanoTime();
        redirectCallCount++;

        boolean verboseLog = (redirectCallCount <= 5) || (redirectCallCount % 120 == 0);

        if (redirectCallCount == 1) {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  FIRST FRAME - CORRECT EVENT ORDER                         ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ 1. glfwPollEvents() - Process input events               ║");
            LOGGER.info("║ 2. bgfx_frame()     - Submit frame (may block)           ║");
            LOGGER.info("║ 3. capturer.endFrame() - Tracy cleanup                   ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Window:  0x{}", Long.toHexString(window));
            LOGGER.info("║ Thread:  {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // STEP 1: Poll events FIRST (before bgfx_frame blocks)
        // This prevents window freeze when bgfx_frame() waits for GPU
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            if (verboseLog) {
                LOGGER.info("[TRACE] Frame #{} - glfwPollEvents() called", redirectCallCount);
            }
        } catch (Exception e) {
            LOGGER.error("Exception during glfwPollEvents()", e);
        }

        // CRITICAL: Only call bgfx_frame() if BGFX is actually initialized
        if (com.vitra.render.bgfx.Util.isInitialized()) {
            try {
                if (verboseLog) {
                    LOGGER.info("[TRACE] BGFX initialized, submitting frame...");
                }

                // CRITICAL FIX: Set view rectangle every frame (required by BGFX)
                // Get dynamic framebuffer size instead of hardcoding
                int[] width = new int[1];
                int[] height = new int[1];
                org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, width, height);
                BGFX.bgfx_set_view_rect(0, 0, 0, width[0], height[0]);

                // CRITICAL FIX: Touch view 0 to ensure it's processed
                // From BGFX helloworld example - ensures view is cleared even with no draws
                // Without this, BGFX may skip the view entirely
                BGFX.bgfx_touch(0);

                long bgfxStartTime = System.nanoTime();
                int frameNum = BGFX.bgfx_frame(false);
                long bgfxEndTime = System.nanoTime();
                long bgfxMs = (bgfxEndTime - bgfxStartTime) / 1_000_000;

                // Calculate frame time
                long frameDelta = 0;
                if (lastFrameTime > 0) {
                    frameDelta = (frameStartTime - lastFrameTime) / 1_000_000; // ms
                }
                lastFrameTime = frameStartTime;

                if (redirectCallCount <= 5) {
                    LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                    LOGGER.info("║  BGFX FRAME #{} SUBMITTED", frameNum);
                    LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                    LOGGER.info("║ Frame Time:  {} ms (target: ~16.67ms for 60 FPS)", frameDelta > 0 ? frameDelta : "N/A");
                    LOGGER.info("║ BGFX Time:   {} ms", bgfxMs);
                    LOGGER.info("║ Thread:      {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                    LOGGER.info("╚════════════════════════════════════════════════════════════╝");
                } else if (verboseLog) {
                    LOGGER.info("[TRACE] Frame #{} submitted ({}ms frame, {}ms BGFX)",
                        frameNum, frameDelta, bgfxMs);
                }

                // Warn if frame time is excessive
                if (frameDelta > 100) { // More than 100ms = less than 10 FPS
                    LOGGER.warn("[PERFORMANCE] Frame #{} took {}ms - possible stutter or hang!", frameNum, frameDelta);
                }

                // Advance fence synchronization
                com.vitra.render.bgfx.BgfxFence.advanceFrame();

            } catch (Exception e) {
                LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                LOGGER.error("║  EXCEPTION DURING BGFX FRAME SUBMISSION                    ║");
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ Frame #{}  Exception: {}", redirectCallCount, e.getClass().getName());
                LOGGER.error("║ Message: {}", e.getMessage());
                LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                LOGGER.error("Full stack trace:", e);
                System.err.println("[CRITICAL] Exception during bgfx_frame(): " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            // BGFX not initialized - this is a CRITICAL error!
            if (redirectCallCount <= 10 || redirectCallCount % 60 == 0) {
                LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                LOGGER.error("║  BGFX NOT INITIALIZED - CANNOT RENDER                      ║");
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ Redirect Call #: {}", redirectCallCount);
                LOGGER.error("║ Window:          0x{}", Long.toHexString(window));
                LOGGER.error("║ Thread:          {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ This is why you're seeing a gray/black screen!             ║");
                LOGGER.error("║ BGFX should have been initialized in WindowMixin.          ║");
                LOGGER.error("║ Check WindowMixin logs for initialization failures.        ║");
                LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                System.err.println("[CRITICAL] BGFX not initialized - frame #" + redirectCallCount);
            }
        }

        // STEP 3: Tracy frame capture cleanup (may be null)
        if (capturer != null) {
            try {
                capturer.endFrame();
                if (verboseLog) {
                    LOGGER.info("[TRACE] Frame #{} - capturer.endFrame() called", redirectCallCount);
                }
            } catch (Exception e) {
                LOGGER.error("Exception during capturer.endFrame()", e);
            }
        }

        // NOTE: DO NOT call glfwSwapBuffers() - we use GLFW_NO_API, no OpenGL context exists
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
     * These high-level RenderSystem methods are no longer needed for state interception.
     */
}
