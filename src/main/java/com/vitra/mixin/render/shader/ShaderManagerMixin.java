package com.vitra.mixin.render.shader;

import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * ShaderManager mixin for Minecraft 26.1.
 * 
 * Intercepts shader loading to enable:
 * - GLSL source code capture for HLSL translation
 * - Post-chain (post-processing effects) setup for D3D11
 * - Shader compilation error handling
 * 
 * Key methods:
 * - prepare(): Loads shader source files and post-chain configs
 * - apply(): Compiles shaders and creates GPU resources
 * - getShader(): Returns shader source for a given ID and type
 * - getPostChain(): Loads post-processing effect chains
 */
@Mixin(ShaderManager.class)
public class ShaderManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/ShaderManager");
    private static int applyCount = 0;
    
    /**
     * Intercept apply() to capture all loaded shaders.
     * This is called during resource loading after prepare().
     * 
     * Note: We can't directly access ShaderSourceKey as it's private.
     * We just log counts and let the pipeline creation handle shader loading.
     */
    @Inject(method = "apply", at = @At("HEAD"))
    private void onApply(ShaderManager.Configs preparations, ResourceManager manager, 
                         ProfilerFiller profiler, CallbackInfo ci) {
        if (applyCount < 3) {
            // Access the map size without referencing private key type
            int shaderCount = preparations.shaderSources().size();
            int postChainCount = preparations.postChains().size();
            
            LOGGER.info("[SHADER_APPLY {}] Loading {} shader sources, {} post-chains",
                applyCount, shaderCount, postChainCount);
            
            // Log post-chains (these use public Identifier type)
            int logged = 0;
            for (Identifier postChainId : preparations.postChains().keySet()) {
                if (logged++ < 10) {
                    LOGGER.debug("  PostChain: {}", postChainId);
                }
            }
            
            applyCount++;
        }
        
        // NOTE: The actual shader compilation and pipeline creation happens via
        // GpuDevice.precompilePipeline() which is intercepted by VitraGpuDevice
        // We don't need to do manual HLSL translation here - just observe
    }
    
    /**
     * Intercept getPostChain to track post-processing effect loading.
     * Post-chains include blur, entity outlines, transparency sorting, etc.
     */
    @Inject(method = "getPostChain", at = @At("HEAD"))
    private void onGetPostChain(Identifier id, Set<Identifier> allowedTargets, 
                                CallbackInfoReturnable<?> cir) {
        LOGGER.debug("[POST_CHAIN] Loading post-chain: {} with {} allowed targets", 
            id, allowedTargets.size());
    }
    
    /**
     * Intercept close() to cleanup D3D11 shader resources.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        LOGGER.info("[SHADER_MANAGER] Closing shader manager - cleaning up resources");
        // TODO: Cleanup any cached D3D11 shader pipelines
    }
}
