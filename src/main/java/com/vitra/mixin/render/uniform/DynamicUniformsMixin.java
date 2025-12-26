package com.vitra.mixin.render.uniform;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DynamicUniforms mixin for Minecraft 26.1.
 * 
 * Intercepts uniform buffer writes to ensure D3D11 constant buffers are
 * properly updated with transform data (ModelView, ColorModulator, etc.)
 * 
 * MC 26.1 uses DynamicUniforms for:
 * - writeTransform(): Model-View matrix, color modulator, model offset, texture matrix
 * - writeChunkSections(): Per-chunk rendering data for terrain
 */
@Mixin(DynamicUniforms.class)
public class DynamicUniformsMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/DynamicUniforms");
    private static int transformWriteCount = 0;
    private static int chunkSectionWriteCount = 0;
    
    /**
     * Intercept writeTransform to log and potentially modify transform data.
     * This is called for every entity/item/etc. that needs rendering.
     */
    @Inject(method = "writeTransform", at = @At("HEAD"))
    private void onWriteTransform(Matrix4fc modelView, Vector4fc colorModulator, 
                                   Vector3fc modelOffset, Matrix4fc textureMatrix,
                                   CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (transformWriteCount < 5) {
            LOGGER.debug("[TRANSFORM {}] writeTransform: modelView[0,0]={:.3f}, color=[{:.2f},{:.2f},{:.2f},{:.2f}], offset=[{:.2f},{:.2f},{:.2f}]",
                transformWriteCount,
                modelView.m00(),
                colorModulator.x(), colorModulator.y(), colorModulator.z(), colorModulator.w(),
                modelOffset.x(), modelOffset.y(), modelOffset.z());
            transformWriteCount++;
        }
        
        // The actual buffer write happens in DynamicUniformStorage.writeUniform()
        // which uses VitraMappedView -> VitraGpuBuffer -> D3D11 constant buffer
        // So we don't need to do anything extra here - just ensure the data flows correctly
    }
    
    /**
     * Intercept writeChunkSections for debugging terrain uniform data.
     * This is called for chunk/terrain rendering.
     */
    @Inject(method = "writeChunkSections", at = @At("HEAD"))
    private void onWriteChunkSections(DynamicUniforms.ChunkSectionInfo[] infos,
                                       CallbackInfoReturnable<GpuBufferSlice[]> cir) {
        if (chunkSectionWriteCount < 3) {
            LOGGER.debug("[CHUNK_SECTION {}] writeChunkSections: {} sections", 
                chunkSectionWriteCount, infos.length);
            
            for (int i = 0; i < Math.min(2, infos.length); i++) {
                DynamicUniforms.ChunkSectionInfo info = infos[i];
                LOGGER.debug("  Section[{}]: pos=({},{},{}), visibility={:.2f}, atlasSize={}x{}",
                    i, info.x(), info.y(), info.z(), 
                    info.visibility(), info.textureAtlasWidth(), info.textureAtlasHeight());
            }
            chunkSectionWriteCount++;
        }
    }
    
    /**
     * Intercept reset() to track frame boundaries.
     * Called by RenderSystem.flipFrame() at the end of each frame.
     */
    @Inject(method = "reset", at = @At("HEAD"))
    private void onReset(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Frame boundary - reset per-frame counters if needed
        // LOGGER.debug("[DYNAMIC_UNIFORMS] reset() called - end of frame");
    }
}
