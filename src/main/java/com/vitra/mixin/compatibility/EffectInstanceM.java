package com.vitra.mixin.compatibility;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.D3D11ShaderManager;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * DirectX 11 EffectInstance compatibility mixin
 *
 * Based on VulkanMod's EffectInstanceM but adapted for DirectX 11 pipeline.
 * Handles post-processing effect shaders (bloom, motion blur, etc.) using
 * precompiled HLSL bytecode (.cso files) instead of runtime GLSL compilation.
 *
 * Key responsibilities:
 * - Load precompiled DirectX 11 effect shaders from resources
 * - Manage DirectX 11 graphics pipeline state for effects
 * - Handle shader uniform/constant buffer bindings
 * - Coordinate texture sampling for post-processing passes
 * - Integrate with Vitra's D3D11ShaderManager for caching
 *
 * Architecture:
 * 1. Intercept Minecraft's EffectInstance constructor
 * 2. Load corresponding precompiled HLSL shaders from /shaders/compiled/
 * 3. Create DirectX 11 pipeline with D3D11ShaderManager
 * 4. Map OpenGL uniforms to DirectX 11 constant buffers
 * 5. Override apply()/clear() to use DirectX 11 rendering
 *
 * Shader loading strategy:
 * - Primary: Use precompiled .cso files (fast, runtime-independent)
 * - Future: GLSL→HLSL converter for mod compatibility (commented architecture below)
 *
 * DirectX 11 pipeline components:
 * - Vertex Shader: HLSL compiled to shader model 5.0 (vs_5_0)
 * - Pixel Shader: HLSL compiled to shader model 5.0 (ps_5_0)
 * - Constant Buffers: UBOs mapped to b0-b3 register slots
 * - Samplers: Texture bindings mapped to s0-s15 register slots
 * - Blend State: Configured from Minecraft's BlendMode
 */
@Mixin(EffectInstance.class)
public class EffectInstanceM {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/EffectInstanceM");

    @Shadow @Final private Map<String, com.mojang.blaze3d.shaders.Uniform> uniformMap;
    @Shadow @Final private List<com.mojang.blaze3d.shaders.Uniform> uniforms;

    @Shadow private boolean dirty;
    @Shadow private static EffectInstance lastAppliedEffect;
    @Shadow @Final private BlendMode blend;
    @Shadow private static int lastProgramId;
    @Shadow @Final private int programId;
    @Shadow @Final private List<Integer> samplerLocations;
    @Shadow @Final private List<String> samplerNames;
    @Shadow @Final private Map<String, IntSupplier> samplerMap;

    @Shadow @Final private String name;

    // DirectX 11 pipeline management
    @Unique private static long lastPipelineHandle = 0;
    @Unique private long pipelineHandle = 0;
    @Unique private long vertexShaderHandle = 0;
    @Unique private long pixelShaderHandle = 0;

    // Constant buffer management for uniforms
    @Unique private long constantBufferHandle = 0;
    @Unique private ByteBuffer constantBufferData;
    @Unique private Map<String, Integer> uniformOffsets;

    /**
     * Inject after uniform locations are updated to initialize DirectX 11 pipeline
     */
    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EffectInstance;updateLocations()V",
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void initDirectX11Pipeline(ResourceProvider resourceProvider, String string, CallbackInfo ci,
                                       ResourceLocation resourceLocation, Resource resource, Reader reader,
                                       JsonObject jsonObject, String string2, String string3) {
        createDirectX11Shaders(resourceProvider, string2, string3);
    }

    /**
     * Redirect shader creation to prevent OpenGL shader compilation
     * We use precompiled DirectX 11 shaders instead
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EffectInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/EffectProgram;"))
    private EffectProgram redirectShaderCreation(ResourceProvider resourceProvider, Program.Type type, String string) {
        // Return null - we handle shader loading in createDirectX11Shaders()
        return null;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL effect cleanup with DirectX 11 pipeline cleanup
     */
    @Overwrite
    public void close() {
        // Close all uniforms
        for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
            uniform.close();
        }

        // Schedule DirectX 11 pipeline cleanup
        if (pipelineHandle != 0) {
            VitraNativeRenderer.destroyResource(pipelineHandle);
            pipelineHandle = 0;
        }

        if (vertexShaderHandle != 0) {
            VitraNativeRenderer.destroyResource(vertexShaderHandle);
            vertexShaderHandle = 0;
        }

        if (pixelShaderHandle != 0) {
            VitraNativeRenderer.destroyResource(pixelShaderHandle);
            pixelShaderHandle = 0;
        }

        if (constantBufferHandle != 0) {
            VitraNativeRenderer.destroyResource(constantBufferHandle);
            constantBufferHandle = 0;
        }

        LOGGER.debug("Closed DirectX 11 effect instance: {}", name);
    }

    /**
     * Create DirectX 11 shaders from precompiled HLSL bytecode
     *
     * Shader loading priority:
     * 1. Precompiled .cso files from /shaders/compiled/
     * 2. TODO: GLSL→HLSL runtime conversion (future enhancement)
     *
     * @param resourceManager Minecraft resource provider
     * @param vertexShaderName Vertex shader name (e.g., "blit_screen")
     * @param fragShaderName Fragment/pixel shader name (e.g., "blit_screen")
     */
    @Unique
    private void createDirectX11Shaders(ResourceProvider resourceManager, String vertexShaderName, String fragShaderName) {
        try {
            // Extract shader base name from Minecraft resource location format
            String[] vshPathInfo = this.decompose(vertexShaderName, ':');
            String vshBaseName = vshPathInfo[1]; // e.g., "blit_screen"

            String[] fshPathInfo = this.decompose(fragShaderName, ':');
            String fshBaseName = fshPathInfo[1]; // e.g., "blit_screen"

            // Strategy 1: Load precompiled DirectX 11 shaders (.cso files)
            // These are HLSL compiled with fxc.exe to shader model 5.0
            Object shaderManagerObj = ((VitraRenderer) VitraRenderer.getRenderer()).getShaderManager();
            if (!(shaderManagerObj instanceof D3D11ShaderManager)) {
                LOGGER.error("Shader manager is not D3D11ShaderManager");
                return;
            }
            D3D11ShaderManager shaderManager = (D3D11ShaderManager) shaderManagerObj;

            // Load vertex shader (e.g., /shaders/compiled/blit_screen_vs.cso)
            vertexShaderHandle = shaderManager.loadShader(vshBaseName, VitraNativeRenderer.SHADER_TYPE_VERTEX);

            if (vertexShaderHandle == 0) {
                LOGGER.error("Failed to load vertex shader: {}", vshBaseName);
                return;
            }

            // Load pixel shader (e.g., /shaders/compiled/blit_screen_ps.cso)
            pixelShaderHandle = shaderManager.loadShader(fshBaseName, VitraNativeRenderer.SHADER_TYPE_PIXEL);

            if (pixelShaderHandle == 0) {
                LOGGER.error("Failed to load pixel shader: {}", fshBaseName);
                return;
            }

            // Create DirectX 11 graphics pipeline
            pipelineHandle = VitraNativeRenderer.createShaderPipeline(vertexShaderHandle, pixelShaderHandle);

            if (pipelineHandle == 0) {
                LOGGER.error("Failed to create DirectX 11 pipeline for effect: {}", name);
                return;
            }

            // TODO: Strategy 2 - GLSL→HLSL converter for mod compatibility
            // This would allow loading arbitrary GLSL shaders from mods at runtime
            // Architecture (commented for future implementation):
            /*
            GlslToHlslConverter converter = new GlslToHlslConverter();

            // Read GLSL source from Minecraft resources
            ResourceLocation vshLocation = ResourceLocation.fromNamespaceAndPath(vshPathInfo[0], "shaders/program/" + vshPathInfo[1] + ".vsh");
            Resource resource = resourceManager.getResourceOrThrow(vshLocation);
            String vshGlslSource = IOUtils.toString(resource.open(), StandardCharsets.UTF_8);

            ResourceLocation fshLocation = ResourceLocation.fromNamespaceAndPath(fshPathInfo[0], "shaders/program/" + fshPathInfo[1] + ".fsh");
            resource = resourceManager.getResourceOrThrow(fshLocation);
            String fshGlslSource = IOUtils.toString(resource.open(), StandardCharsets.UTF_8);

            // Convert GLSL to HLSL (disabled - using precompiled shaders)
            // converter.process(vshGlslSource, fshGlslSource);
            // String vshHlslSource = converter.getVertexShaderHlsl();
            // String fshHlslSource = converter.getPixelShaderHlsl();
            String vshHlslSource = ""; // Placeholder
            String fshHlslSource = ""; // Placeholder

            // Compile HLSL to DirectX 11 bytecode at runtime
            byte[] vshBytecode = compileHlslShader(vshHlslSource, "vs_5_0", "main");
            byte[] fshBytecode = compileHlslShader(fshHlslSource, "ps_5_0", "main");

            vertexShaderHandle = VitraNativeRenderer.createGLProgramShader(vshBytecode, vshBytecode.length, VitraNativeRenderer.SHADER_TYPE_VERTEX);
            pixelShaderHandle = VitraNativeRenderer.createGLProgramShader(fshBytecode, fshBytecode.length, VitraNativeRenderer.SHADER_TYPE_PIXEL);
            */

            // Initialize constant buffer for uniforms
            // DirectX 11 uses constant buffers (CBs) instead of individual uniforms
            // TODO: Implement constant buffer initialization when createConstantBuffer is available
            // initializeConstantBuffer();

            LOGGER.info("Created DirectX 11 effect pipeline: {} (VS: 0x{}, PS: 0x{}, Pipeline: 0x{})",
                name,
                Long.toHexString(vertexShaderHandle),
                Long.toHexString(pixelShaderHandle),
                Long.toHexString(pipelineHandle));

        } catch (Exception e) {
            LOGGER.error("Exception creating DirectX 11 shaders for effect: {}", name, e);
        }
    }

    /**
     * Initialize DirectX 11 constant buffer for shader uniforms
     *
     * Maps Minecraft's individual uniforms to a structured constant buffer.
     * DirectX 11 requires 16-byte alignment for constant buffer elements.
     */
    @Unique
    private void initializeConstantBuffer() {
        // Calculate required constant buffer size
        // Each uniform needs appropriate alignment (float4 = 16 bytes)
        int bufferSize = calculateConstantBufferSize();

        if (bufferSize == 0) {
            return;
        }

        // Create DirectX 11 constant buffer
        // TODO: Implement when createConstantBuffer is available
        // constantBufferHandle = VitraNativeRenderer.createConstantBuffer(bufferSize);

        if (constantBufferHandle == 0) {
            LOGGER.error("Failed to create constant buffer for effect: {}", name);
            return;
        }

        // Allocate CPU-side buffer for uniform data
        constantBufferData = MemoryUtil.memAlloc(bufferSize);

        LOGGER.debug("Created constant buffer for effect {}: {} bytes, {} uniforms",
            name, bufferSize, uniforms.size());
    }

    /**
     * Calculate constant buffer size with DirectX 11 alignment rules
     */
    @Unique
    private int calculateConstantBufferSize() {
        int size = 0;

        for (com.mojang.blaze3d.shaders.Uniform uniform : uniforms) {
            int uniformSize = getUniformSize(uniform.getType());
            // DirectX 11 requires 16-byte alignment
            size += ((uniformSize + 15) / 16) * 16;
        }

        return size;
    }

    /**
     * Get uniform data size in bytes
     */
    @Unique
    private int getUniformSize(int type) {
        // Uniform types from Minecraft:
        // 0-3: int types (4 bytes each)
        // 4-10: float types (4 bytes each)
        if (type <= 3) {
            return 4 * (type + 1); // int, int2, int3, int4
        } else if (type <= 10) {
            return 4 * (type - 3); // float, float2, float3, float4, mat2x2, mat3x3, mat4x4
        }
        return 16; // Default to 16 bytes
    }

    /**
     * Decompose Minecraft resource location (namespace:path)
     */
    @Unique
    private String[] decompose(String string, char c) {
        String[] strings = new String[]{"minecraft", string};
        int i = string.indexOf(c);
        if (i >= 0) {
            strings[1] = string.substring(i + 1);
            if (i >= 1) {
                strings[0] = string.substring(0, i);
            }
        }
        return strings;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL effect application with DirectX 11 pipeline binding
     */
    @Overwrite
    public void apply() {
        this.dirty = false;

        // Apply blend mode using DirectX 11 blend state
        this.blend.apply();

        // Maintain OpenGL program ID for compatibility
        ProgramManager.glUseProgram(this.programId);

        // Bind DirectX 11 graphics pipeline if changed
        if (this.pipelineHandle != lastPipelineHandle) {
            VitraNativeRenderer.setShaderPipeline(pipelineHandle);
            lastPipelineHandle = this.pipelineHandle;
        }

        // Bind samplers (texture slots)
        for(int i = 0; i < this.samplerLocations.size(); ++i) {
            String samplerName = this.samplerNames.get(i);
            IntSupplier textureIdSupplier = this.samplerMap.get(samplerName);

            if (textureIdSupplier != null) {
                RenderSystem.activeTexture(GL30.GL_TEXTURE0 + i);
                int textureId = textureIdSupplier.getAsInt();

                if (textureId != -1) {
                    RenderSystem.bindTexture(textureId);
                    com.mojang.blaze3d.shaders.Uniform.uploadInteger(this.samplerLocations.get(i), i);
                }
            }
        }

        // Upload uniforms to constant buffer
        // Note: Individual uniform.upload() calls are intercepted by UniformM
        for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
            uniform.upload();
        }

        // Upload and bind constant buffers to DirectX 11 pipeline
        VitraNativeRenderer.uploadAndBindUBOs();

        LOGGER.trace("Applied DirectX 11 effect: {}", name);
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL effect clear with DirectX 11 state reset
     */
    @Overwrite
    public void clear() {
        RenderSystem.assertOnRenderThread();

        // Reset OpenGL program state
        ProgramManager.glUseProgram(0);
        lastProgramId = -1;
        lastAppliedEffect = null;
        lastPipelineHandle = 0;

        // Unbind all sampler textures
        for(int i = 0; i < this.samplerLocations.size(); ++i) {
            if (this.samplerMap.get(this.samplerNames.get(i)) != null) {
                GlStateManager._activeTexture(GL30.GL_TEXTURE0 + i);
                GlStateManager._bindTexture(0);
            }
        }

        LOGGER.trace("Cleared DirectX 11 effect: {}", name);
    }
}
