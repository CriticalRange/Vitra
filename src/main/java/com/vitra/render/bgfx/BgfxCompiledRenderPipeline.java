package com.vitra.render.bgfx;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * Simplified BGFX compiled render pipeline implementation using BGFX native functionality directly
 * Replaces the complex VitraCompiledRenderPipeline wrapper class
 */
public class BgfxCompiledRenderPipeline implements CompiledRenderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxCompiledRenderPipeline");

    private final RenderPipeline pipeline;
    private final BiFunction<ResourceLocation, ShaderType, String> shaderResolver;
    private short programHandle = 0;

    /**
     * Create a compiled render pipeline with default shader resolver
     */
    public BgfxCompiledRenderPipeline(RenderPipeline pipeline) {
        this.pipeline = pipeline;
        this.shaderResolver = null;
        compilePipeline();
    }

    /**
     * Create a compiled render pipeline with custom shader resolver
     */
    public BgfxCompiledRenderPipeline(RenderPipeline pipeline, BiFunction<ResourceLocation, ShaderType, String> shaderResolver) {
        this.pipeline = pipeline;
        this.shaderResolver = shaderResolver;
        compilePipeline();
    }

    /**
     * Compile the render pipeline using BGFX native functionality
     */
    private void compilePipeline() {
        try {
            // Load basic shaders for the pipeline using BGFX
            this.programHandle = Util.loadProgram("basic");
            if (this.programHandle != 0) {
                LOGGER.debug("Compiled BGFX render pipeline (program: {})", programHandle);
            } else {
                LOGGER.warn("Failed to compile BGFX render pipeline, using fallback");
            }
        } catch (Exception e) {
            LOGGER.error("Exception compiling render pipeline", e);
            this.programHandle = 0;
        }
    }

    public short getProgramHandle() {
        return programHandle;
    }

    public boolean isValid() {
        // BGFX pipeline is valid if we have a program handle or if BGFX is initialized
        return programHandle != 0 || Util.isInitialized();
    }

    public void close() {
        if (programHandle != 0) {
            BgfxOperations.destroyResource(programHandle, "program");
            programHandle = 0;
            LOGGER.debug("Destroyed BGFX compiled pipeline program");
        }
    }

    @Override
    public String toString() {
        return String.format("BgfxCompiledRenderPipeline{program=%d, valid=%s}", programHandle, isValid());
    }
}