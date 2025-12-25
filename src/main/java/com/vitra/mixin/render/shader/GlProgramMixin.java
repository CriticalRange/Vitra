package com.vitra.mixin.render.shader;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * GlProgram mixin for Minecraft 26.1 shader system.
 * Intercepts shader compilation/linking to enable DirectX shader creation.
 * 
 * 26.1 Shader Architecture:
 * - RenderPipeline: State configuration (blend, depth, cull, etc.)
 * - GlProgram: Actual shader program with uniforms
 * - GlRenderPipeline: Record pairing RenderPipeline + GlProgram
 * - GlShaderModule: Individual vertex/fragment shader modules
 */
@Mixin(GlProgram.class)
public abstract class GlProgramMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GlProgram");

    @Shadow public abstract int getProgramId();
    @Shadow public abstract Map<String, Uniform> getUniforms();
    @Shadow public abstract String getDebugLabel();

    @Unique private long vitraVsHandle;
    @Unique private long vitraPsHandle;
    @Unique private long vitraPipelineHandle;

    /**
     * Intercept program linking to create DirectX shader pipeline.
     * The link() method compiles and links GLSL shaders - we can intercept
     * to also create DirectX equivalents.
     */
    @Inject(method = "link", at = @At("RETURN"))
    private static void onLink(GlShaderModule vertexShader, GlShaderModule fragmentShader,
                               VertexFormat vertexFormat, String debugLabel,
                               CallbackInfoReturnable<GlProgram> cir) {
        GlProgram program = cir.getReturnValue();
        if (program != null && program != GlProgram.INVALID_PROGRAM) {
            LOGGER.info("[GlProgram] Linked shader '{}' with programId={}, format={}",
                debugLabel, program.getProgramId(), vertexFormat.toString());
            
            // TODO: Create DirectX pipeline here
            // This would involve:
            // 1. Loading HLSL equivalent shaders
            // 2. Compiling with D3DCompile
            // 3. Creating VS/PS and input layout
            // 4. Storing handles in the mixin instance
        }
    }

    /**
     * Intercept uniform setup to track DirectX constant buffer requirements.
     */
    @Inject(method = "setupUniforms", at = @At("RETURN"))
    private void onSetupUniforms(List<RenderPipeline.UniformDescription> uniforms,
                                 List<String> samplers, CallbackInfo ci) {
        LOGGER.info("[GlProgram] Setup uniforms for '{}': {} uniforms, {} samplers",
            getDebugLabel(), uniforms.size(), samplers.size());
        
        // Log uniform details for DirectX constant buffer layout
        for (RenderPipeline.UniformDescription uniform : uniforms) {
            LOGGER.debug("[GlProgram]   Uniform: {}", uniform);
        }
    }

    /**
     * Intercept program close to cleanup DirectX resources.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        if (vitraPipelineHandle != 0) {
            LOGGER.info("[GlProgram] Closing DirectX resources for '{}'", getDebugLabel());
            // TODO: Cleanup DirectX resources
            vitraPipelineHandle = 0;
            vitraVsHandle = 0;
            vitraPsHandle = 0;
        }
    }
}
