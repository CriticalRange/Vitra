package com.vitra.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * DirectX 11 GameRenderer mixin following VulkanMod standards.
 * Replaces OpenGL shader management with DirectX 11 shader pipeline management.
 * Handles shader cleanup, UI shader preloading, and depth settings for DirectX 11.
 *
 * Pattern based on VulkanMod's MGameRenderer with DirectX 11 adaptations.
 */
@Mixin(GameRenderer.class)
public abstract class MGameRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GameRenderer");

    @Shadow @Final private Map<String, ShaderInstance> shaders;

    @Shadow private @Nullable static ShaderInstance positionShader;
    @Shadow private @Nullable static ShaderInstance positionColorShader;
    @Shadow private @Nullable static ShaderInstance positionTexShader;
    @Shadow private @Nullable static ShaderInstance positionTexColorShader;
    @Shadow private @Nullable static ShaderInstance rendertypeTextShader;
    @Shadow private static @Nullable ShaderInstance rendertypeGuiShader;
    @Shadow private static @Nullable ShaderInstance rendertypeGuiOverlayShader;

    @Shadow public ShaderInstance blitShader;

    @Shadow protected abstract ShaderInstance preloadShader(ResourceProvider resourceProvider, String string, VertexFormat vertexFormat);

    @Shadow public abstract float getRenderDistance();

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL shader cleanup with DirectX 11 shader pipeline cleanup
     */
    @Overwrite
    private void shutdownShaders() {
        RenderSystem.assertOnRenderThread();

        LOGGER.debug("Shutting down DirectX 11 shader pipelines");

        final var clearList = ImmutableList.copyOf(this.shaders.values());

        // Follow VulkanMod pattern: frame-based cleanup with error suppression
        VitraNativeRenderer.addFrameOperation(() -> {
            for (ShaderInstance shader : clearList) {
                try {
                    if (shader != null) {
                        shader.close();
                        LOGGER.debug("Closed DirectX 11 shader pipeline: {}", shader.getName());
                    }
                } catch (Exception e) {
                    // Silent error handling following VulkanMod pattern
                    LOGGER.warn("Failed to close DirectX 11 shader pipeline: {}", shader.getName());
                }
            }
        });

        this.shaders.clear();

        LOGGER.debug("Successfully shutdown {} DirectX 11 shader pipelines", clearList.size());
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL UI shader preloading with DirectX 11 shader pipeline creation
     */
    @Overwrite
    public void preloadUiShader(ResourceProvider resourceProvider) {
        if (this.blitShader != null) {
            throw new RuntimeException("Blit shader already preloaded");
        } else {
            LOGGER.debug("Preloading DirectX 11 UI shader pipelines");

            try {
                this.blitShader = new ShaderInstance(resourceProvider, "blit_screen", DefaultVertexFormat.POSITION_TEX);
                LOGGER.debug("Created DirectX 11 blit shader pipeline");
            } catch (IOException var3) {
                LOGGER.error("Failed to preload DirectX 11 blit shader pipeline", var3);
                throw new RuntimeException("could not preload blit shader", var3);
            }

            // Preload essential UI shader pipelines
            positionShader = this.preloadShader(resourceProvider, "position", DefaultVertexFormat.POSITION);
            positionColorShader = this.preloadShader(resourceProvider, "position_color", DefaultVertexFormat.POSITION_COLOR);
            positionTexShader = this.preloadShader(resourceProvider, "position_tex", DefaultVertexFormat.POSITION_TEX);
            positionTexColorShader = this.preloadShader(resourceProvider, "position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR);
            rendertypeTextShader = this.preloadShader(resourceProvider, "rendertype_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

            rendertypeGuiShader = positionColorShader;
            rendertypeGuiOverlayShader = positionColorShader;

            LOGGER.debug("Successfully preloaded {} DirectX 11 UI shader pipelines", 5);
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Set infinite depth for DirectX 11 rendering to avoid z-fighting at distance
     */
    @Overwrite
    public float getDepthFar() {
        // DirectX 11 supports infinite far plane better than OpenGL
        // This allows for maximum render distance without z-fighting
        return Float.POSITIVE_INFINITY;
    }

    /**
     * Helper method to check if VitraCore is properly initialized
     */
    private boolean isVitraCoreInitialized() {
        return VitraCore.getInstance() != null && VitraCore.getInstance().isInitialized();
    }

    /**
     * Helper method to log shader pipeline statistics
     */
    private void logShaderStats() {
        if (isVitraCoreInitialized()) {
            int activePipelines = VitraNativeRenderer.getActiveShaderPipelineCount();
            int totalPipelines = VitraNativeRenderer.getTotalShaderPipelineCount();

            LOGGER.debug("DirectX 11 Shader Pipeline Stats - Active: {}, Total: {}", activePipelines, totalPipelines);
        }
    }
}
