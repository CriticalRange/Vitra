package com.vitra.mixin.render.pipeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * RenderPipelines mixin for Minecraft 26.1.
 * 
 * MC 26.1 uses RenderPipelines class with 100+ predefined pipelines for all rendering:
 * - Blocks (solid, cutout, translucent)
 * - Entities (solid, cutout, translucent, various effects)
 * - GUI elements
 * - Particles
 * - Text
 * - Post-processing
 * 
 * Each pipeline specifies:
 * - Vertex/Fragment shaders
 * - Blend mode
 * - Depth test/write
 * - Cull mode
 * - Vertex format
 * - Required uniforms (Projection, Fog, Globals, Lighting, DynamicTransforms)
 * - Required samplers (textures)
 * 
 * For D3D11, we need to:
 * 1. Track all registered pipelines
 * 2. Create corresponding HLSL shaders
 * 3. Create D3D11 Pipeline State Objects (blend/depth/rasterizer states)
 */
@Mixin(RenderPipelines.class)
public class RenderPipelinesMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/RenderPipelines");
    private static boolean loggedPipelines = false;
    
    /**
     * Intercept getStaticPipelines() to capture all registered pipelines.
     * This is called by ShaderManager.apply() during resource loading.
     */
    @Inject(method = "getStaticPipelines", at = @At("RETURN"))
    private static void onGetStaticPipelines(CallbackInfoReturnable<List<RenderPipeline>> cir) {
        if (!loggedPipelines) {
            List<RenderPipeline> pipelines = cir.getReturnValue();
            LOGGER.info("[PIPELINES] RenderPipelines.getStaticPipelines() returned {} pipelines", 
                pipelines.size());
            
            // Log all pipelines with their shader and state info
            int index = 0;
            for (RenderPipeline pipeline : pipelines) {
                String location = pipeline.getLocation().toString();
                String vertexShader = pipeline.getVertexShader().toString();
                String fragmentShader = pipeline.getFragmentShader().toString();
                
                // Log first 20 pipelines in detail
                if (index < 20) {
                    LOGGER.info("[PIPELINE {}] {} | VS: {} | FS: {} | Format: {} elems | Cull: {} | DepthWrite: {}",
                        index,
                        location,
                        vertexShader,
                        fragmentShader,
                        pipeline.getVertexFormat().getElements().size(),
                        pipeline.isCull(),
                        pipeline.isWriteDepth());
                    
                    // Log uniforms and samplers
                    LOGGER.debug("  Uniforms: {}", pipeline.getUniforms());
                    LOGGER.debug("  Samplers: {}", pipeline.getSamplers());
                }
                index++;
            }
            
            if (pipelines.size() > 20) {
                LOGGER.info("[PIPELINE] ... and {} more pipelines", pipelines.size() - 20);
            }
            
            loggedPipelines = true;
            
            // Categorize pipelines for D3D11 state object creation
            long guiPipelines = pipelines.stream()
                .filter(p -> p.getLocation().toString().contains("gui"))
                .count();
            long entityPipelines = pipelines.stream()
                .filter(p -> p.getLocation().toString().contains("entity"))
                .count();
            long terrainPipelines = pipelines.stream()
                .filter(p -> p.getLocation().toString().contains("terrain") || 
                            p.getLocation().toString().contains("block"))
                .count();
            
            LOGGER.info("[PIPELINES] Categories: GUI={}, Entity={}, Terrain={}, Other={}",
                guiPipelines, entityPipelines, terrainPipelines,
                pipelines.size() - guiPipelines - entityPipelines - terrainPipelines);
        }
    }
}
