package com.vitra.mixin.compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * DirectX 11 PostChain compatibility mixin
 *
 * Based on VulkanMod's PostChainM but adapted for DirectX 11 pipeline.
 * Handles post-processing effect chains consisting of multiple sequential render passes.
 *
 * Key responsibilities:
 * - Manage post-processing effect chains (bloom, motion blur, etc.)
 * - Coordinate multiple sequential PostPass operations
 * - Handle render target management for effect chains
 * - Manage effect timing and animation
 * - Apply filter modes (linear/nearest) for texture sampling
 * - Integrate with DirectX 11 viewport and scissor operations
 *
 * Architecture:
 * A PostChain is a sequence of PostPass operations, each rendering from one
 * RenderTarget to another with a specific shader effect. The chain typically:
 * 1. Reads from main screen buffer (screenTarget)
 * 2. Applies multiple shader passes through intermediate render targets
 * 3. Outputs final result to screen or another render target
 *
 * DirectX 11 specifics:
 * - Viewport reset after each pass to ensure correct rendering dimensions
 * - Filter mode changes handled via DirectX 11 sampler states
 * - Render target binding coordinated with DirectX 11 OMSetRenderTargets
 * - Time management for animated effects
 *
 * PostChain JSON format (loaded by Minecraft):
 * {
 *   "targets": [
 *     { "name": "swap", "width": screenWidth, "height": screenHeight },
 *     { "name": "temp", "width": screenWidth, "height": screenHeight }
 *   ],
 *   "passes": [
 *     {
 *       "name": "blur_horizontal",
 *       "intarget": "minecraft:main",
 *       "outtarget": "swap",
 *       "uniforms": [...]
 *     },
 *     {
 *       "name": "blur_vertical",
 *       "intarget": "swap",
 *       "outtarget": "minecraft:main",
 *       "uniforms": [...]
 *     }
 *   ]
 * }
 */
@Mixin(PostChain.class)
public abstract class PostChainM {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/PostChainM");

    @Shadow private int screenWidth;
    @Shadow private int screenHeight;

    @Shadow @Final private Map<String, RenderTarget> customRenderTargets;
    @Shadow @Final private RenderTarget screenTarget;
    @Shadow @Final private List<PostPass> passes;

    @Shadow private float lastStamp;
    @Shadow private float time;

    @Shadow protected abstract void setFilterMode(int filterMode);

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL post-processing chain execution with DirectX 11 pipeline
     *
     * Process the entire post-processing effect chain.
     * Each pass reads from an input render target, applies a shader effect,
     * and writes to an output render target.
     *
     * DirectX 11 workflow:
     * 1. Update effect timing for animation
     * 2. For each PostPass in the chain:
     *    a. Set DirectX 11 filter mode (sampler state)
     *    b. Execute the pass (handled by PostPassM)
     *    c. Reset viewport to ensure correct rendering dimensions
     * 3. Reset filter mode to default (nearest neighbor)
     * 4. Reset DirectX 11 viewport to screen dimensions
     *
     * @param timestamp Current frame timestamp (0.0 - 1.0 range, wraps around)
     */
    @Overwrite
    public void process(float timestamp) {
        // Update effect timing
        // Time wraps around every 20 seconds to prevent float precision issues
        if (timestamp < this.lastStamp) {
            // Timestamp wrapped around (crossed 1.0 boundary)
            this.time += 1.0F - this.lastStamp;
            this.time += timestamp;
        } else {
            this.time += timestamp - this.lastStamp;
        }

        this.lastStamp = timestamp;

        // Wrap time at 20 seconds to maintain precision
        while (this.time > 20.0F) {
            this.time -= 20.0F;
        }

        // Track current filter mode to avoid redundant state changes
        int currentFilterMode = 9728; // GL_NEAREST (default)

        LOGGER.trace("Processing PostChain with {} passes, time: {}", passes.size(), this.time);

        // Execute each post-processing pass in sequence
        for (PostPass postPass : this.passes) {
            int passFilterMode = postPass.getFilterMode();

            // Only update filter mode if changed (reduces DirectX 11 state changes)
            if (currentFilterMode != passFilterMode) {
                this.setFilterMode(passFilterMode);
                currentFilterMode = passFilterMode;
                LOGGER.trace("Updated filter mode to: {}", passFilterMode);
            }

            try {
                // Execute the pass - PostPassM handles DirectX 11 rendering
                // Passes normalized time (0.0 - 1.0 range)
                postPass.process(this.time / 20.0F);

            } catch (Exception e) {
                LOGGER.error("Exception processing PostPass in chain", e);
                // Continue processing remaining passes even if one fails
            }
        }

        // Reset filter mode to default (nearest neighbor)
        this.setFilterMode(9728); // GL_NEAREST

        // Reset DirectX 11 viewport to screen dimensions
        // This ensures subsequent rendering operations have correct viewport
        try {
            resetDirectX11Viewport();
        } catch (Exception e) {
            LOGGER.error("Failed to reset DirectX 11 viewport after PostChain processing", e);
        }

        LOGGER.trace("Completed PostChain processing");
    }

    /**
     * Reset DirectX 11 viewport to screen dimensions
     *
     * After post-processing effects complete, the viewport must be reset to
     * ensure subsequent rendering operations (UI, HUD, etc.) render correctly.
     *
     * DirectX 11 viewport structure:
     * - TopLeftX: 0
     * - TopLeftY: 0
     * - Width: screenWidth
     * - Height: screenHeight
     * - MinDepth: 0.0f
     * - MaxDepth: 1.0f
     */
    private void resetDirectX11Viewport() {
        VitraNativeRenderer.setViewport(0, 0, screenWidth, screenHeight);

        LOGGER.trace("Reset viewport to screen dimensions: {}x{}", screenWidth, screenHeight);
    }

    /**
     * Get custom render target by name
     *
     * Used by PostPass to resolve render target references from JSON configuration.
     * "minecraft:main" refers to the main screen buffer.
     *
     * @param name Render target name (e.g., "swap", "temp", or "minecraft:main")
     * @return RenderTarget instance or null if not found
     */
    @Shadow
    protected abstract RenderTarget getRenderTarget(String name);

    /**
     * Resize all render targets in the chain
     *
     * Called when window is resized or when render scale changes.
     * All intermediate render targets must be recreated with new dimensions.
     *
     * DirectX 11 resize workflow:
     * 1. Destroy existing render target resources
     * 2. Recreate render targets with new dimensions
     * 3. Update screenWidth/screenHeight
     * 4. Recreate DirectX 11 textures and views
     *
     * @param width New screen width
     * @param height New screen height
     */
    @Shadow
    public abstract void resize(int width, int height);

    /**
     * Close and cleanup all post-chain resources
     *
     * Called when the effect chain is no longer needed.
     * Releases all DirectX 11 render targets and shader resources.
     */
    @Shadow
    public abstract void close();
}
