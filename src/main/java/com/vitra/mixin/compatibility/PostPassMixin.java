package com.vitra.mixin.compatibility;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.VitraMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * DirectX PostPass compatibility mixin
 *
 * Based on VulkanMod's PostPassM but adapted for DirectX pipeline.
 * Handles individual post-processing effect passes within a PostChain.
 *
 * Key responsibilities:
 * - Execute single post-processing shader pass
 * - Bind input/output render targets for DirectX
 * - Configure auxiliary textures (depth buffers, additional samplers)
 * - Set up shader uniforms (InSize, OutSize, Time, ScreenSize, etc.)
 * - Render fullscreen quad with effect shader
 * - Manage DirectX viewport and depth state
 *
 * Architecture:
 * A PostPass represents a single rendering operation:
 * 1. Read from input render target (inTarget)
 * 2. Apply shader effect (EffectInstance)
 * 3. Write to output render target (outTarget)
 * 4. Optionally use auxiliary textures (depth buffers, additional samplers)
 *
 * DirectX workflow:
 * 1. Unbind input target from writing (prepare for reading)
 * 2. Set viewport to output target dimensions
 * 3. Bind input target as shader texture ("DiffuseSampler")
 * 4. Bind auxiliary textures (depth, additional samplers)
 * 5. Set shader uniforms (sizes, time, projection matrix)
 * 6. Clear and bind output target for writing
 * 7. Configure DirectX depth and cull state
 * 8. Apply effect shader and draw fullscreen quad
 * 9. Unbind all render targets
 *
 * Shader uniforms (standard for Minecraft post-processing):
 * - DiffuseSampler: Input texture from inTarget
 * - ProjMat: Orthographic projection matrix
 * - InSize: Input texture dimensions (vec2)
 * - OutSize: Output texture dimensions (vec2)
 * - Time: Normalized time for animation (0.0 - 1.0)
 * - ScreenSize: Window dimensions (vec2)
 * - AuxSize[i]: Auxiliary texture dimensions (vec2)
 *
 * DirectX specific optimizations:
 * - Inverted viewport for DirectX coordinate system
 * - Depth function set to GL_GREATER (519) during effect rendering
 * - Cull face disabled for fullscreen quad rendering
 * - Primitive topology explicitly set to TRIANGLES
 */
@Mixin(PostPass.class)
public class PostPassMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/PostPassM");

    // Helper to get renderer instance (with null-safety check)
    // Returns null for D3D12 (which doesn't need GL compatibility layer)
    @org.jetbrains.annotations.Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            // Not yet initialized - this is expected during early initialization
            return null;
        }

        // If it's already a VitraRenderer (D3D11), return it directly
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }

        // For D3D12, return null (D3D12 doesn't use GL compatibility layer)
        // D3D12 handles rendering directly without going through GL emulation
        return null;
    }

    @Shadow @Final public RenderTarget inTarget;
    @Shadow @Final public RenderTarget outTarget;
    @Shadow @Final private EffectInstance effect;

    // Auxiliary texture assets (additional samplers for effect)
    @Shadow @Final private List<IntSupplier> auxAssets;
    @Shadow @Final private List<String> auxNames;
    @Shadow @Final private List<Integer> auxWidths;
    @Shadow @Final private List<Integer> auxHeights;

    @Shadow private Matrix4f shaderOrthoMatrix;

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL post-pass execution with DirectX pipeline
     *
     * Execute a single post-processing shader pass.
     * Reads from inTarget, applies shader effect, writes to outTarget.
     *
     * @param normalizedTime Time value normalized to 0.0 - 1.0 range
     */
    @Overwrite
    public void process(float normalizedTime) {
        LOGGER.trace("Processing PostPass: {} -> {}",
            getTargetName(inTarget), getTargetName(outTarget));

        try {
            // Step 1: Unbind input target from writing (prepare for reading as texture)
            this.inTarget.unbindWrite();

            // Step 2: Calculate output dimensions and set viewport
            float outWidth = (float) this.outTarget.width;
            float outHeight = (float) this.outTarget.height;
            RenderSystem.viewport(0, 0, (int) outWidth, (int) outHeight);

            LOGGER.trace("Output dimensions: {}x{}", outWidth, outHeight);

            // Step 3: Bind input target as "DiffuseSampler" texture
            // Lambda captures inTarget for deferred texture ID retrieval
            Objects.requireNonNull(this.inTarget);
            this.effect.setSampler("DiffuseSampler", this.inTarget::getColorTextureId);

            // Special handling for main screen buffer
            if (this.inTarget instanceof MainTarget) {
                this.inTarget.bindRead();
            }

            // Step 4: Bind auxiliary textures (depth buffers, additional samplers)
            for (int i = 0; i < this.auxAssets.size(); ++i) {
                String auxName = this.auxNames.get(i);
                IntSupplier auxTextureSupplier = this.auxAssets.get(i);
                int auxWidth = this.auxWidths.get(i);
                int auxHeight = this.auxHeights.get(i);

                // Bind auxiliary texture to shader
                this.effect.setSampler(auxName, auxTextureSupplier);

                // Set auxiliary texture size uniform (AuxSize0, AuxSize1, etc.)
                this.effect.safeGetUniform("AuxSize" + i).set(
                    (float) auxWidth, (float) auxHeight);

                LOGGER.trace("Bound auxiliary texture {}: {} ({}x{})",
                    i, auxName, auxWidth, auxHeight);
            }

            // Step 5: Set standard post-processing shader uniforms
            // ProjMat: Orthographic projection matrix for fullscreen quad
            this.effect.safeGetUniform("ProjMat").set(this.shaderOrthoMatrix);

            // InSize: Input texture dimensions
            this.effect.safeGetUniform("InSize").set(
                (float) this.inTarget.width, (float) this.inTarget.height);

            // OutSize: Output texture dimensions
            this.effect.safeGetUniform("OutSize").set(outWidth, outHeight);

            // Time: Normalized time for animated effects
            this.effect.safeGetUniform("Time").set(normalizedTime);

            // ScreenSize: Window dimensions (for screen-space effects)
            Minecraft minecraft = Minecraft.getInstance();
            this.effect.safeGetUniform("ScreenSize").set(
                (float) minecraft.getWindow().getWidth(),
                (float) minecraft.getWindow().getHeight());

            LOGGER.trace("Set shader uniforms - InSize: {}x{}, OutSize: {}x{}, Time: {}",
                this.inTarget.width, this.inTarget.height,
                (int) outWidth, (int) outHeight, normalizedTime);

            // Step 6: Clear and bind output target for writing
            // Clear depth on macOS (Minecraft.ON_OSX flag)
            this.outTarget.clear(Minecraft.ON_OSX);
            this.outTarget.bindWrite(false); // Don't set viewport (already set above)

            // Step 7: Configure DirectX rendering state
            disableDirectX11Cull();
            setDirectX11DepthFunc(519); // GL_GREATER
            setPrimitiveTopology(GL11.GL_TRIANGLES);

            // Set inverted viewport for DirectX coordinate system
            // DirectX uses top-left origin, OpenGL uses bottom-left
            setInvertedViewport(0, 0, this.outTarget.width, this.outTarget.height);
            resetScissor();

            // Step 8: Apply effect shader and render fullscreen quad
            this.effect.apply();

            // Build fullscreen quad geometry
            // Z=500.0f ensures quad is in front of other geometry
            BufferBuilder bufferBuilder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            bufferBuilder.addVertex(0.0f, 0.0f, 500.0f);
            bufferBuilder.addVertex(outWidth, 0.0f, 500.0f);
            bufferBuilder.addVertex(outWidth, outHeight, 500.0f);
            bufferBuilder.addVertex(0.0f, outHeight, 500.0f);

            // Draw the fullscreen quad with effect shader
            BufferUploader.draw(bufferBuilder.buildOrThrow());

            // Restore depth function to default
            RenderSystem.depthFunc(515); // GL_LEQUAL

            // Step 9: Cleanup - unbind all render targets
            this.effect.clear();
            this.outTarget.unbindWrite();
            this.inTarget.unbindRead();

            // Unbind auxiliary render targets if they're RenderTarget instances
            for (Object auxAsset : this.auxAssets) {
                if (auxAsset instanceof RenderTarget) {
                    ((RenderTarget) auxAsset).unbindRead();
                }
            }

            // Re-enable culling
            enableDirectX11Cull();

            LOGGER.trace("PostPass processing completed successfully");

        } catch (Exception e) {
            LOGGER.error("Exception during PostPass processing", e);
            // Re-enable culling even on error to avoid corrupting render state
            try {
                enableDirectX11Cull();
            } catch (Exception cullError) {
                LOGGER.error("Failed to re-enable culling after error", cullError);
            }
        }
    }

    /**
     * Disable DirectX face culling
     *
     * Fullscreen quad rendering requires backface culling to be disabled
     * since the quad covers the entire screen and may have inconsistent winding.
     */
    @Unique
    private void disableDirectX11Cull() {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // CULL_MODE_NONE = 0 (D3D11_CULL_NONE)
                renderer.setRasterizerState(0, 0, false);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to disable DirectX cull mode", e);
        }
    }

    /**
     * Enable DirectX face culling (restore default state)
     *
     * Re-enable backface culling after fullscreen quad rendering completes.
     */
    @Unique
    private void enableDirectX11Cull() {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // CULL_MODE_BACK = 2 (D3D11_CULL_BACK)
                renderer.setRasterizerState(2, 0, false);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to enable DirectX cull mode", e);
        }
    }

    /**
     * Set DirectX depth comparison function
     *
     * @param glDepthFunc OpenGL depth function constant (e.g., GL_GREATER = 519)
     */
    @Unique
    private void setDirectX11DepthFunc(int glDepthFunc) {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // Map OpenGL depth func to DirectX D3D11_COMPARISON_FUNC
                // GL_GREATER (519) -> D3D11_COMPARISON_GREATER
                int d3d11ComparisonFunc = mapGLDepthFuncToD3D11(glDepthFunc);
                renderer.depthFunc(d3d11ComparisonFunc);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to set DirectX depth function", e);
        }
    }

    /**
     * Map OpenGL depth function to DirectX comparison function
     *
     * OpenGL constants:
     * - GL_NEVER (512) -> D3D11_COMPARISON_NEVER (1)
     * - GL_LESS (513) -> D3D11_COMPARISON_LESS (2)
     * - GL_EQUAL (514) -> D3D11_COMPARISON_EQUAL (3)
     * - GL_LEQUAL (515) -> D3D11_COMPARISON_LESS_EQUAL (4)
     * - GL_GREATER (516) -> D3D11_COMPARISON_GREATER (5)
     * - GL_NOTEQUAL (517) -> D3D11_COMPARISON_NOT_EQUAL (6)
     * - GL_GEQUAL (518) -> D3D11_COMPARISON_GREATER_EQUAL (7)
     * - GL_ALWAYS (519) -> D3D11_COMPARISON_ALWAYS (8)
     *
     * @param glDepthFunc OpenGL depth function constant
     * @return DirectX D3D11_COMPARISON_FUNC value
     */
    @Unique
    private int mapGLDepthFuncToD3D11(int glDepthFunc) {
        return switch (glDepthFunc) {
            case 512 -> 1; // GL_NEVER -> D3D11_COMPARISON_NEVER
            case 513 -> 2; // GL_LESS -> D3D11_COMPARISON_LESS
            case 514 -> 3; // GL_EQUAL -> D3D11_COMPARISON_EQUAL
            case 515 -> 4; // GL_LEQUAL -> D3D11_COMPARISON_LESS_EQUAL
            case 516 -> 5; // GL_GREATER -> D3D11_COMPARISON_GREATER
            case 517 -> 6; // GL_NOTEQUAL -> D3D11_COMPARISON_NOT_EQUAL
            case 518 -> 7; // GL_GEQUAL -> D3D11_COMPARISON_GREATER_EQUAL
            case 519 -> 8; // GL_ALWAYS -> D3D11_COMPARISON_ALWAYS
            default -> 4; // Default to LESS_EQUAL
        };
    }

    /**
     * Set DirectX primitive topology
     *
     * @param glTopology OpenGL primitive topology (e.g., GL_TRIANGLES)
     */
    @Unique
    private void setPrimitiveTopology(int glTopology) {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // Map OpenGL topology to DirectX
                // GL_TRIANGLES (4) -> D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST (4)
                renderer.setPrimitiveTopology(glTopology);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to set DirectX primitive topology", e);
        }
    }

    /**
     * Set inverted viewport for DirectX coordinate system
     *
     * DirectX uses top-left origin (Y down), OpenGL uses bottom-left (Y up).
     * Inverted viewport flips Y coordinates to match expected behavior.
     *
     * @param x Viewport X coordinate
     * @param y Viewport Y coordinate
     * @param width Viewport width
     * @param height Viewport height
     */
    @Unique
    private void setInvertedViewport(int x, int y, int width, int height) {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // DirectX viewport with inverted Y
                renderer.setViewport(x, y, width, height);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to set inverted DirectX viewport", e);
        }
    }

    /**
     * Reset DirectX scissor test to full viewport
     *
     * Ensures scissor test doesn't clip post-processing effects.
     */
    @Unique
    private void resetScissor() {
        try {
            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                renderer.setScissorRect(0, 0, this.outTarget.width, this.outTarget.height);
            }
            // For D3D12 or not initialized: skip
        } catch (Exception e) {
            LOGGER.warn("Failed to reset DirectX scissor", e);
        }
    }

    /**
     * Get render target name for logging
     *
     * @param target RenderTarget instance
     * @return Target name or class name if not identifiable
     */
    @Unique
    private String getTargetName(RenderTarget target) {
        if (target instanceof MainTarget) {
            return "MainTarget";
        }
        return target.getClass().getSimpleName();
    }
}
