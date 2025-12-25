package com.vitra.mixin.render;

import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.textures.GpuSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin to fix NPE in SamplerCache.close() when samplers array contains nulls.
 * 
 * The vanilla code iterates over the samplers array but doesn't handle null entries,
 * which can happen if SamplerCache.initialize() wasn't called or failed partway.
 */
@Mixin(SamplerCache.class)
public class SamplerCacheMixin {
    
    @Shadow
    @Final
    private GpuSampler[] samplers;
    
    /**
     * @author Vitra
     * @reason Fix NPE when closing samplers that may be null
     */
    @Overwrite
    public void close() {
        for (GpuSampler sampler : this.samplers) {
            if (sampler != null) {
                sampler.close();
            }
        }
    }
}
