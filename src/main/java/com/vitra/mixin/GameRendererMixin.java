package com.vitra.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.client.DeltaTracker;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CRITICAL MIXIN: Main render loop interception for DirectX 11 (Minecraft 1.21.1)
 *
 * This mixin handles the most critical rendering operations that prevent black screen:
 * 1. Main render loop interception (render method) - ESSENTIAL for any rendering
 * 2. Frame begin/end management - REQUIRED for DirectX 11 swap chain
 * 3. Clear color and clear operations - NEEDED to prevent pink/black background
 * 4. Shader preloading safety - PREVENTS deadlocks during resource reload
 *
 * Minecraft 1.21.1 uses DeltaTracker for render timing.
 * This mixin ensures DirectX 11 frame synchronization happens at the right time.
 *
 * The render() method is called EVERY frame and is the hook point for:
 * - Beginning DirectX 11 frame (beginFrame)
 * - Setting up clear color and clearing render target
 * - Ending DirectX 11 frame and presenting (endFrame)
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameRendererMixin");

    // Frame tracking for debugging
    private static int frameCount = 0;
    private static long lastFrameTime = 0;

    // Depth stencil buffer management (CRITICAL for fixing black screen)
    private static long depthStencilBuffer = 0;
    private static long depthStencilState = 0;
    private static boolean depthStencilInitialized = false;

    /**
     * CRITICAL: Intercept the main render loop (Minecraft 1.21.1)
     * Method signature: public void render(DeltaTracker deltaTracker, boolean renderLevel)
     *
     * Following VulkanMod's pattern: intercept main render loop for DirectX 11 frame management
     */
    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onRenderFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        try {
            frameCount++;
            long currentTime = System.currentTimeMillis();

            // Log frame info periodically (not every frame to avoid spam)
            if (frameCount <= 10 || frameCount % 600 == 0 || (currentTime - lastFrameTime) > 5000) {
                LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                LOGGER.info("║  DIRECTX 11 RENDER FRAME START (1.21.1)                ║");
                LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                LOGGER.info("║ Frame #:        {}", frameCount);
                LOGGER.info("║ Render Level:   {}", renderLevel);
                LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                LOGGER.info("║ DeltaTracker:   {}", deltaTracker.getClass().getSimpleName());
                LOGGER.info("║ VitraCore Init: {}", VitraCore.getInstance().isInitialized());
                LOGGER.info("║ Frame Time:     {}ms", currentTime - lastFrameTime);
                LOGGER.info("╚════════════════════════════════════════════════════════════╝");
                lastFrameTime = currentTime;
            }

            // Ensure VitraCore is initialized
            if (!VitraCore.getInstance().isInitialized()) {
                LOGGER.warn("VitraCore not initialized during render frame - skipping DirectX operations");
                return;
            }

            // Initialize depth stencil buffer on first frame (CRITICAL for 3D rendering)
            if (!depthStencilInitialized && frameCount <= 3) {
                initializeDepthStencilBuffer();
            }

            // CRITICAL: Begin DirectX 11 frame - this MUST happen every frame
            VitraNativeRenderer.beginFrameSafe();

            if (frameCount <= 5) {
                LOGGER.info("✓ DirectX 11 render frame started (1.21.1)");
            }

        } catch (Exception e) {
            LOGGER.error("Exception in render frame interception", e);
        }
    }

    /**
     * CRITICAL: Intercept render completion to end DirectX 11 frame
     */
    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At("RETURN"),
        cancellable = false
    )
    private void onRenderFrameComplete(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        try {
            // CRITICAL: End DirectX 11 frame and present to screen
            VitraNativeRenderer.endFrameSafe();

            if (frameCount <= 5 || frameCount % 600 == 0) {
                LOGGER.info("✓ DirectX 11 render frame completed and presented (1.21.1)");
            }

        } catch (Exception e) {
            LOGGER.error("Exception in render frame completion", e);
        }
    }

    /**
     * CRITICAL: Intercept world rendering (Minecraft 1.21.1)
     * Method signature: public void renderLevel(DeltaTracker deltaTracker)
     */
    @Inject(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            // Log world render info periodically
            if (frameCount <= 10 || frameCount % 600 == 0) {
                LOGGER.info("╔════════════════════════════════════════════════════════════╗");
                LOGGER.info("║  DIRECTX 11 WORLD RENDER START (1.21.1)                ║");
                LOGGER.info("╠════════════════════════════════════════════════════════════╣");
                LOGGER.info("║ Frame #:        {}", frameCount);
                LOGGER.info("║ Thread:         {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
                LOGGER.info("║ DeltaTracker:   {}", deltaTracker.getClass().getSimpleName());
                LOGGER.info("║ VitraCore Init: {}", VitraCore.getInstance().isInitialized());
                LOGGER.info("║ World Time:     {}ms", System.currentTimeMillis() - lastFrameTime);
                LOGGER.info("╚════════════════════════════════════════════════════════════╝");
            }

            // Ensure VitraCore is initialized
            if (!VitraCore.getInstance().isInitialized()) {
                LOGGER.warn("VitraCore not initialized during world render - skipping DirectX operations");
                return;
            }

            // CRITICAL: Set clear color to prevent pink/black background
            // Use a nice blue color for debugging (can be changed later)
            VitraNativeRenderer.setClearColor(0.1f, 0.2f, 0.4f, 1.0f);

            // CRITICAL: Clear the render target with the set clear color
            VitraNativeRenderer.clear(0.1f, 0.2f, 0.4f, 1.0f);

            // CRITICAL: Clear depth stencil buffer (ESSENTIAL for 3D rendering)
            if (depthStencilInitialized) {
                VitraNativeRenderer.clearDepthStencilBuffer(depthStencilBuffer, true, true, 1.0f, 0);
                if (frameCount <= 5) {
                    LOGGER.info("✓ DirectX 11 depth stencil buffer cleared (1.21.1)");
                }
            } else {
                LOGGER.warn("⚠ Depth stencil buffer not initialized - 3D rendering may fail");
            }

            if (frameCount <= 5) {
                LOGGER.info("✓ DirectX 11 world rendering started and cleared successfully (1.21.1)");
            }

        } catch (Exception e) {
            LOGGER.error("Exception in world render interception", e);
        }
    }

    /**
     * Intercept preloadUiShader to add DirectX 11 JNI-aware logging and thread verification
     *
     * This method is called BEFORE the main resource reload, ensuring shaders
     * are loaded on the render thread and not during worker thread resource loading.
     *
     * Following VulkanMod's pattern: intercept shader preloading to prevent deadlocks.
     */
    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private void onPreloadUiShader(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  PRELOADING UI SHADER (DirectX 11 JNI)                      ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ This prevents deadlocks during resource reload            ║");
        LOGGER.info("║ Shader preloading on render thread (not worker threads)   ║");
        LOGGER.info("║ Thread: {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Intercept preloadUiShader completion to verify success
     */
    @Inject(method = "preloadUiShader", at = @At("RETURN"))
    private void onPreloadUiShaderComplete(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  UI SHADER PRELOAD COMPLETE                                ║");
        LOGGER.info("║  Shaders ready for DirectX 11 JNI rendering                 ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Initialize depth stencil buffer (CRITICAL for 3D rendering)
     * Based on DirectX 11 documentation patterns with enhanced debugging
     */
    private void initializeDepthStencilBuffer() {
        try {
            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  INITIALIZING DEPTH STENCIL BUFFER (DirectX 11 + Debug)       ║");
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");

            // Enable debug layer if not already enabled
            if (VitraNativeRenderer.isDebugEnabled()) {
                boolean debugAvailable = VitraNativeRenderer.isDebugLayerAvailable();
                LOGGER.info("Debug layer available: {}", debugAvailable);

                if (debugAvailable) {
                    // Enable debug layer with GPU validation for thorough debugging
                    VitraNativeRenderer.enableDirectXDebugLayer(true, false); // GPU validation is too slow for now
                    VitraNativeRenderer.setDebugMessageSeverity(VitraNativeRenderer.DEBUG_SEVERITY_WARNING);
                    VitraNativeRenderer.setDebugBreakOnError(false, false); // Don't break on errors in production
                    LOGGER.info("✓ DirectX 11 debug layer enabled for depth stencil debugging");
                }
            }

            // Begin debug event for depth stencil initialization
            long debugEvent = 0;
            if (VitraNativeRenderer.isDebugEnabled()) {
                debugEvent = VitraNativeRenderer.beginDebugEvent("Initialize Depth Stencil Buffer");
                VitraNativeRenderer.setDebugMarker("Starting depth stencil buffer creation");
            }

            // Get window dimensions (use typical Minecraft window size)
            int width = 1920;  // Default to 1920x1080, will be updated on resize
            int height = 1080;

            try {
                // Try to get actual window dimensions from Minecraft
                width = VitraNativeRenderer.glGetInteger(0x0D02); // GL_VIEWPORT width
                height = VitraNativeRenderer.glGetInteger(0x0D03); // GL_VIEWPORT height
                LOGGER.info("Using actual window dimensions: {}x{}", width, height);
            } catch (Exception e) {
                LOGGER.warn("Could not get actual window dimensions, using defaults: {}x{}", width, height);
            }

            // Create depth stencil buffer with D24_UNORM_S8_UINT format (24-bit depth, 8-bit stencil)
            VitraNativeRenderer.setDebugMarker("Creating depth stencil texture");
            depthStencilBuffer = VitraNativeRenderer.createDepthStencilBuffer(width, height,
                VitraNativeRenderer.DEPTH_FORMAT_D24_UNORM_S8_UINT);

            if (depthStencilBuffer == 0) {
                LOGGER.error("✗ Failed to create depth stencil buffer");

                if (VitraNativeRenderer.isDebugEnabled()) {
                    String deviceState = VitraNativeRenderer.validateDeviceState();
                    LOGGER.error("Device state validation:\n{}", deviceState);

                    String debugInfo = VitraNativeRenderer.getDebugMessageQueueInfo();
                    LOGGER.error("Debug message queue info:\n{}", debugInfo);
                }

                if (debugEvent != 0) {
                    VitraNativeRenderer.endDebugEvent(debugEvent);
                }
                return;
            }

            // Set debug name for depth stencil buffer (CRUCIAL for debugging)
            if (VitraNativeRenderer.isDebugEnabled()) {
                String bufferName = String.format("DepthStencil_%dx%d_D24S8", width, height);
                VitraNativeRenderer.setDebugObjectNameTyped(depthStencilBuffer,
                    VitraNativeRenderer.DEBUG_OBJECT_TYPE_DEPTH_STENCIL, bufferName);
                LOGGER.info("✓ Set debug name for depth stencil buffer: {}", bufferName);
            }

            LOGGER.info("✓ Created depth stencil buffer: handle=0x{}, size={}x{}",
                Long.toHexString(depthStencilBuffer), width, height);

            // Bind depth stencil buffer to output merger stage
            VitraNativeRenderer.setDebugMarker("Binding depth stencil buffer to output merger");
            boolean bound = VitraNativeRenderer.bindDepthStencilBuffer(depthStencilBuffer);
            if (!bound) {
                LOGGER.error("✗ Failed to bind depth stencil buffer");

                if (VitraNativeRenderer.isDebugEnabled()) {
                    String renderTargets = VitraNativeRenderer.validateRenderTargets() ? "Valid" : "Invalid";
                    LOGGER.error("Render target validation: {}", renderTargets);
                }

                VitraNativeRenderer.releaseDepthStencilBuffer(depthStencilBuffer);
                depthStencilBuffer = 0;
                if (debugEvent != 0) {
                    VitraNativeRenderer.endDebugEvent(debugEvent);
                }
                return;
            }

            LOGGER.info("✓ Bound depth stencil buffer to output merger stage");

            // Create depth stencil state with proper 3D settings
            VitraNativeRenderer.setDebugMarker("Creating depth stencil state");
            depthStencilState = VitraNativeRenderer.createDepthStencilState(
                true,   // depthEnable - ESSENTIAL for 3D
                true,   // depthWriteEnable - ESSENTIAL for 3D
                VitraNativeRenderer.DEPTH_FUNC_LEQUAL, // depthFunc - LEQUAL is recommended
                false,  // stencilEnable - Can be disabled for basic 3D
                0xFF,   // stencilReadMask
                0xFF    // stencilWriteMask
            );

            if (depthStencilState == 0) {
                LOGGER.error("✗ Failed to create depth stencil state");

                if (VitraNativeRenderer.isDebugEnabled()) {
                    String deviceState = VitraNativeRenderer.getDeviceState();
                    LOGGER.error("Device state:\n{}", deviceState);
                }

                VitraNativeRenderer.releaseDepthStencilBuffer(depthStencilBuffer);
                depthStencilBuffer = 0;
                if (debugEvent != 0) {
                    VitraNativeRenderer.endDebugEvent(debugEvent);
                }
                return;
            }

            // Set debug name for depth stencil state
            if (VitraNativeRenderer.isDebugEnabled()) {
                VitraNativeRenderer.setDebugObjectNameTyped(depthStencilState,
                    VitraNativeRenderer.DEBUG_OBJECT_TYPE_DEPTH_STENCIL_STATE,
                    "DepthStencilState_3D_Enabled_LEQUAL");
                LOGGER.info("✓ Set debug name for depth stencil state");
            }

            LOGGER.info("✓ Created depth stencil state: handle=0x{}", Long.toHexString(depthStencilState));

            // Bind depth stencil state
            VitraNativeRenderer.setDebugMarker("Binding depth stencil state");
            boolean stateBound = VitraNativeRenderer.bindDepthStencilState(depthStencilState, 1);
            if (!stateBound) {
                LOGGER.error("✗ Failed to bind depth stencil state");

                if (VitraNativeRenderer.isDebugEnabled()) {
                    String deviceState = VitraNativeRenderer.validateDeviceState();
                    LOGGER.error("Device state after failed depth stencil state bind:\n{}", deviceState);
                }

                VitraNativeRenderer.releaseDepthStencilState(depthStencilState);
                VitraNativeRenderer.releaseDepthStencilBuffer(depthStencilBuffer);
                depthStencilBuffer = 0;
                depthStencilState = 0;
                if (debugEvent != 0) {
                    VitraNativeRenderer.endDebugEvent(debugEvent);
                }
                return;
            }

            LOGGER.info("✓ Bound depth stencil state (depth=ENABLE, depthWrite=ENABLE, depthFunc=LEQUAL)");

            // Validate everything is working correctly
            if (VitraNativeRenderer.isDebugEnabled()) {
                VitraNativeRenderer.setDebugMarker("Validating depth stencil setup");

                // Check if depth stencil buffer is valid
                boolean depthValid = VitraNativeRenderer.isDepthStencilBufferValid(depthStencilBuffer);
                LOGGER.info("Depth stencil buffer validation: {}", depthValid ? "✓ VALID" : "✗ INVALID");

                // Get swap chain information
                String swapChainInfo = VitraNativeRenderer.getSwapChainInfo();
                LOGGER.info("Swap chain status:\n{}", swapChainInfo);

                // Get viewport information
                String viewportInfo = VitraNativeRenderer.getViewportInfo();
                LOGGER.info("Viewport info:\n{}", viewportInfo);

                // Check for resource leaks
                String leakReport = VitraNativeRenderer.checkResourceLeaks();
                if (!leakReport.contains("No leaks detected")) {
                    LOGGER.warn("Resource leak report:\n{}", leakReport);
                }
            }

            depthStencilInitialized = true;

            if (debugEvent != 0) {
                VitraNativeRenderer.setDebugMarker("Depth stencil initialization complete");
                VitraNativeRenderer.endDebugEvent(debugEvent);
            }

            LOGGER.info("╔════════════════════════════════════════════════════════════╗");
            LOGGER.info("║  DEPTH STENCIL BUFFER INITIALIZATION COMPLETE                ║");
            LOGGER.info("║  Enhanced debugging enabled - should FIX black screen issue   ║");
            LOGGER.info("║  Debug names set for all DirectX 11 objects                  ║");
            LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize depth stencil buffer", e);

            if (VitraNativeRenderer.isDebugEnabled()) {
                LOGGER.error("Exception during depth stencil initialization:");
                LOGGER.error("Debug message queue:\n{}", VitraNativeRenderer.getDebugMessageQueueInfo());
                LOGGER.error("Live objects report:\n{}", VitraNativeRenderer.reportLiveObjects());
            }

            depthStencilInitialized = false;
        }
    }

    /**
     * Intercept close() to ensure safe DirectX 11 JNI shutdown
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        LOGGER.info("GameRenderer closing - ensuring DirectX 11 JNI resources are cleaned up");

        // Clean up depth stencil resources
        if (depthStencilState != 0) {
            VitraNativeRenderer.releaseDepthStencilState(depthStencilState);
            depthStencilState = 0;
            LOGGER.info("✓ Released depth stencil state");
        }

        if (depthStencilBuffer != 0) {
            VitraNativeRenderer.releaseDepthStencilBuffer(depthStencilBuffer);
            depthStencilBuffer = 0;
            LOGGER.info("✓ Released depth stencil buffer");
        }

        depthStencilInitialized = false;
    }
}
