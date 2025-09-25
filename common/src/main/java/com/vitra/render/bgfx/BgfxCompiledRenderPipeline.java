package com.vitra.render.bgfx;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * BGFX implementation of CompiledRenderPipeline for shader compilation
 */
public class BgfxCompiledRenderPipeline implements CompiledRenderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxCompiledRenderPipeline");

    private final RenderPipeline pipeline;
    private final BiFunction<ResourceLocation, ShaderType, String> shaderResolver;

    public BgfxCompiledRenderPipeline(RenderPipeline pipeline) {
        this.pipeline = pipeline;
        this.shaderResolver = null;
        LOGGER.debug("Created BgfxCompiledRenderPipeline without shader resolver");
    }

    public BgfxCompiledRenderPipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderResolver) {
        this.pipeline = pipeline;
        this.shaderResolver = shaderResolver;
        LOGGER.debug("Created BgfxCompiledRenderPipeline with shader resolver");
    }

    @Override
    public boolean isValid() {
        // For now, assume all BGFX compiled pipelines are valid
        return true;
    }
}