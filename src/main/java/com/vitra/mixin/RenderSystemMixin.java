package com.vitra.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
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
import org.spongepowered.asm.mixin.injection.Redirect;

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
     * Replacement: Do nothing - DirectX 11 JNI handles device initialization
     */
    @Overwrite(remap = false)
    public static void initRenderer(long windowHandle, int debugVerbosity, boolean sync,
                                     BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter,
                                     boolean renderDebugLabels) {
        LOGGER.info("RenderSystem.initRenderer intercepted - using DirectX 11 JNI instead of OpenGL");
        LOGGER.info("Window handle: 0x{}, debugVerbosity: {}, sync: {}, renderDebugLabels: {}",
            Long.toHexString(windowHandle), debugVerbosity, sync, renderDebugLabels);

        // NO-OP: Do not create OpenGL device
        // DirectX 11 JNI was already initialized in WindowMixin with the window handle
    }

    /**
     * CRITICAL FIX: Override flipFrame to fix event polling order
     *
     * DirectX 11 JNI REQUIREMENT: glfwPollEvents() MUST be called BEFORE frame submission
     * - This prevents event processing blocks
     * - If frame submission is called first, it may block waiting for GPU
     * - While blocked, events aren't processed → window freeze
     *
     * Original Minecraft order (WRONG):
     * 1. glfwSwapBuffers()  → we replace with DirectX 11 frame submission
     * 2. glfwPollEvents()   → too late, already blocked!
     * 3. capturer.endFrame()
     *
     * Correct DirectX 11 JNI order:
     * 1. glfwPollEvents()   → process events FIRST
     * 2. DirectX frame submission → then submit frame (may block)
     * 3. capturer.endFrame() → cleanup
     *
     * Why @Overwrite instead of @Redirect:
     * - Need to reorder the calls completely
     * - @Redirect can only replace individual calls, not reorder them
     * - This is critical for preventing window freeze
     *
     * @author Vitra
     * @reason Complete replacement of glfwSwapBuffers with DirectX 11 JNI frame submission and correct event polling order
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
            LOGGER.info("║ 2. DirectX frame()  - Submit frame (may block)           ║");
            LOGGER.info("║ 3. capturer.endFrame() - Tracy cleanup                   ║");
            LOGGER.info("╠════════════════════════════════════════════════════════════╣");
            LOGGER.info("║ Window:  0x{}", Long.toHexString(window));
            LOGGER.info("║ Thread:  {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        }

        // STEP 1: Poll events FIRST (before frame submission blocks)
        // This prevents window freeze when DirectX 11 waits for GPU
        try {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            if (verboseLog) {
                LOGGER.info("[TRACE] Frame #{} - glfwPollEvents() called", redirectCallCount);
            }
        } catch (Exception e) {
            LOGGER.error("Exception during glfwPollEvents()", e);
        }

        // CRITICAL: Only call DirectX frame submission if VitraRenderer is actually initialized
        if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isInitialized()) {
            try {
                if (verboseLog) {
                    LOGGER.info("[TRACE] VitraRenderer initialized, submitting frame...");
                }

                // Get dynamic framebuffer size for resize
                int[] width = new int[1];
                int[] height = new int[1];
                org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, width, height);

                // Resize renderer if framebuffer size changed
                VitraMod.getRenderer().resize(width[0], height[0]);

                long directXStartTime = System.nanoTime();
                VitraMod.getRenderer().beginFrame();

                // Submit frame to DirectX renderer
                VitraMod.getRenderer().endFrame();

                long directXEndTime = System.nanoTime();
                long directXMs = (directXEndTime - directXStartTime) / 1_000_000;

                // Calculate frame time
                long frameDelta = 0;
                if (lastFrameTime > 0) {
                    frameDelta = (frameStartTime - lastFrameTime) / 1_000_000; // ms
                }
                lastFrameTime = frameStartTime;

                if (redirectCallCount <= 5) {
                    LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                    LOGGER.info("║  DIRECTX 11 JNI FRAME #{} SUBMITTED", redirectCallCount);
                    LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                    LOGGER.info("║ Frame Time:  {} ms (target: ~16.67ms for 60 FPS)", frameDelta > 0 ? frameDelta : "N/A");
                    LOGGER.info("║ DirectX Time: {} ms", directXMs);
                    LOGGER.info("║ Resolution:  {}x{}", width[0], height[0]);
                    LOGGER.info("║ Thread:      {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                    LOGGER.info("╚════════════════════════════════════════════════════════════╝");
                } else if (verboseLog) {
                    LOGGER.info("[TRACE] Frame #{} submitted ({}ms frame, {}ms DirectX)",
                        redirectCallCount, frameDelta, directXMs);
                }

                // Warn if frame time is excessive
                if (frameDelta > 100) { // More than 100ms = less than 10 FPS
                    LOGGER.warn("[PERFORMANCE] Frame #{} took {}ms - possible stutter or hang!", redirectCallCount, frameDelta);
                }

            } catch (Exception e) {
                LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                LOGGER.error("║  EXCEPTION DURING DIRECTX 11 JNI FRAME SUBMISSION           ║");
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ Frame #{}  Exception: {}", redirectCallCount, e.getClass().getName());
                LOGGER.error("║ Message: {}", e.getMessage());
                LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                LOGGER.error("Full stack trace:", e);
                System.err.println("[CRITICAL] Exception during DirectX 11 JNI frame(): " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            // DirectX 11 JNI not initialized - this is a CRITICAL error!
            if (redirectCallCount <= 10 || redirectCallCount % 60 == 0) {
                LOGGER.error("╔════════════════════════════════════════════════════════════╗");
                LOGGER.error("║  DIRECTX 11 JNI NOT INITIALIZED - CANNOT RENDER            ║");
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ Redirect Call #: {}", redirectCallCount);
                LOGGER.error("║ Window:          0x{}", Long.toHexString(window));
                LOGGER.error("║ Thread:          {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                LOGGER.error("╠════════════════════════════════════════════════════════════╣");
                LOGGER.error("║ This is why you're seeing a gray/black screen!             ║");
                LOGGER.error("║ DirectX 11 JNI should have been initialized in WindowMixin. ║");
                LOGGER.error("║ Check WindowMixin logs for initialization failures.        ║");
                LOGGER.error("╚════════════════════════════════════════════════════════════╝");
                System.err.println("[CRITICAL] DirectX 11 JNI not initialized - frame #" + redirectCallCount);
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

        // NOTE: DO NOT call glfwSwapBuffers() - DirectX 11 JNI handles frame presentation
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
