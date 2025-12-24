package com.vitra.mixin.compatibility;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.D3D11ShaderManager;
import com.vitra.VitraMod;
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
 * D3D11 EffectInstance compatibility mixin
 *
 * Based on VulkanMod's EffectInstanceM but adapted for D3D11 pipeline.
 * Handles post-processing effect shaders (bloom, motion blur, etc.) using
 * precompiled HLSL bytecode (.cso files) instead of runtime GLSL compilation.
 *
 * Key responsibilities:
 * - Load precompiled D3D11 effect shaders from resources
 * - Manage D3D11 graphics pipeline state for effects
 * - Handle shader uniform/constant buffer bindings
 * - Coordinate texture sampling for post-processing passes
 * - Integrate with Vitra's D3D11ShaderManager for caching
 *
 * Architecture:
 * 1. Intercept Minecraft's EffectInstance constructor
 * 2. Load corresponding precompiled HLSL shaders from /shaders/compiled/
 * 3. Create D3D11 pipeline with D3D11ShaderManager
 * 4. Map OpenGL uniforms to D3D11 constant buffers
 * 5. Override apply()/clear() to use D3D11 rendering
 *
 * Shader loading strategy:
 * - Primary: Use precompiled .cso files (fast, runtime-independent)
 * - Future: GLSL→HLSL converter for mod compatibility (commented architecture below)
 *
 * D3D11 pipeline components:
 * - Vertex Shader: HLSL compiled to shader model 5.0 (vs_5_0)
 * - Pixel Shader: HLSL compiled to shader model 5.0 (ps_5_0)
 * - Constant Buffers: UBOs mapped to b0-b3 register slots
 * - Samplers: Texture bindings mapped to s0-s15 register slots
 * - Blend State: Configured from Minecraft's BlendMode
 */
@Mixin(EffectInstance.class)
public class EffectInstanceMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/EffectInstanceM");

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

    // Shader type constants (renderer-agnostic)
    private static final int SHADER_TYPE_VERTEX = 0;
    private static final int SHADER_TYPE_PIXEL = 1;

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

    // D3D11 pipeline management
    @Unique private static long lastPipelineHandle = 0;
    @Unique private long pipelineHandle = 0;
    @Unique private long vertexShaderHandle = 0;
    @Unique private long pixelShaderHandle = 0;

    // Constant buffer management for uniforms
    @Unique private long constantBufferHandle = 0;
    @Unique private ByteBuffer constantBufferData;
    @Unique private Map<String, Integer> uniformOffsets;

    /**
     * Inject after uniform locations are updated to initialize D3D11 pipeline
     */
    @Inject(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EffectInstance;updateLocations()V",
                    shift = At.Shift.AFTER),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void initD3D11Pipeline(ResourceProvider resourceProvider, String string, CallbackInfo ci,
                                       ResourceLocation resourceLocation, Resource resource, Reader reader,
                                       JsonObject jsonObject, String string2, String string3) {
        createDirectX11Shaders(resourceProvider, string2, string3);
    }

    /**
     * Redirect shader creation to prevent OpenGL shader compilation
     * We use precompiled D3D11 shaders instead
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EffectInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/EffectProgram;"))
    private EffectProgram redirectShaderCreation(ResourceProvider resourceProvider, Program.Type type, String string) {
        // Return null - we handle shader loading in createDirectX11Shaders()
        return null;
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL effect cleanup with D3D11 pipeline cleanup
     */
    @Overwrite
    public void close() {
        // Close all uniforms
        for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
            uniform.close();
        }

        // Schedule D3D11 pipeline cleanup (only for D3D11, skip for D3D12)
        VitraRenderer renderer = getRenderer();
        if (renderer != null) {
            if (pipelineHandle != 0) {
                renderer.destroyResource(pipelineHandle);
                pipelineHandle = 0;
            }

            if (vertexShaderHandle != 0) {
                renderer.destroyResource(vertexShaderHandle);
                vertexShaderHandle = 0;
            }

            if (pixelShaderHandle != 0) {
                renderer.destroyResource(pixelShaderHandle);
                pixelShaderHandle = 0;
            }

            if (constantBufferHandle != 0) {
                renderer.destroyResource(constantBufferHandle);
                constantBufferHandle = 0;
            }

            LOGGER.debug("Closed D3D11 effect instance: {}", name);
        }
        // For D3D12 or not initialized: skip cleanup (D3D12 has its own cleanup)
    }

    /**
     * Create D3D11 shaders using runtime HLSL compilation
     *
     * NEW APPROACH: Runtime compilation from HLSL source (like ShaderInstanceMixin)
     * This replaces the old .cso file loading system for post-processing effects.
     *
     * @param resourceManager Minecraft resource provider
     * @param vertexShaderName Vertex shader name (e.g., "blit_screen")
     * @param fragShaderName Fragment/pixel shader name (e.g., "blit_screen")
     */
    @Unique
    private void createDirectX11Shaders(ResourceProvider resourceManager, String vertexShaderName, String fragShaderName) {
        try {
            // Only for D3D11, skip for D3D12
            VitraRenderer renderer = getRenderer();
            if (renderer == null) {
                // D3D12 or not initialized: skip shader loading (D3D12 has its own shader system)
                LOGGER.debug("Skipping D3D11 shader loading (using D3D12 or not initialized)");
                return;
            }

            // Extract shader base name from Minecraft resource location format
            String[] vshPathInfo = this.decompose(vertexShaderName, ':');
            String vshBaseName = vshPathInfo[1]; // e.g., "blit_screen" or "blur"

            String[] fshPathInfo = this.decompose(fragShaderName, ':');
            String fshBaseName = fshPathInfo[1]; // e.g., "blit_screen" or "blur"

            LOGGER.info("Loading effect shaders: vertex={}, fragment={}", vshBaseName, fshBaseName);

            // Load HLSL source files from vitra:shaders/hlsl/
            String vshSource = loadEffectShaderSource(resourceManager, vshBaseName, "vsh");
            String fshSource = loadEffectShaderSource(resourceManager, fshBaseName, "fsh");

            if (vshSource == null || fshSource == null) {
                LOGGER.error("Failed to load HLSL source for effect: {}", name);
                return;
            }

            // Load and inline cbuffer_common.hlsli
            String cbufferCommon = loadCBufferCommon(resourceManager);
            if (cbufferCommon != null) {
                vshSource = vshSource.replace("#include \"cbuffer_common.hlsli\"", cbufferCommon);
                fshSource = fshSource.replace("#include \"cbuffer_common.hlsli\"", cbufferCommon);
            }

            // Compile HLSL to D3D11 bytecode at runtime
            vertexShaderHandle = compileAndCreateShader(vshSource, vshBaseName + "_vs", "vs_5_0", SHADER_TYPE_VERTEX);
            pixelShaderHandle = compileAndCreateShader(fshSource, fshBaseName + "_ps", "ps_5_0", SHADER_TYPE_PIXEL);

            if (vertexShaderHandle == 0 || pixelShaderHandle == 0) {
                LOGGER.error("Failed to compile shaders for effect: {}", name);
                return;
            }

            // Create D3D11 graphics pipeline
            pipelineHandle = renderer.createShaderPipeline(vertexShaderHandle, pixelShaderHandle);

            if (pipelineHandle == 0) {
                LOGGER.error("Failed to create D3D11 pipeline for effect: {}", name);
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

            // Compile HLSL to D3D11 bytecode at runtime
            byte[] vshBytecode = compileHlslShader(vshHlslSource, "vs_5_0", "main");
            byte[] fshBytecode = compileHlslShader(fshHlslSource, "ps_5_0", "main");

            vertexShaderHandle = getRenderer().createGLProgramShader(vshBytecode, vshBytecode.length, SHADER_TYPE_VERTEX);
            pixelShaderHandle = getRenderer().createGLProgramShader(fshBytecode, fshBytecode.length, SHADER_TYPE_PIXEL);
            */

            // Initialize constant buffer for uniforms
            // D3D11 uses constant buffers (CBs) instead of individual uniforms
            // TODO: Implement constant buffer initialization when createConstantBuffer is available
            // initializeConstantBuffer();

            LOGGER.info("Created D3D11 effect pipeline: {} (VS: 0x{}, PS: 0x{}, Pipeline: 0x{})",
                name,
                Long.toHexString(vertexShaderHandle),
                Long.toHexString(pixelShaderHandle),
                Long.toHexString(pipelineHandle));

        } catch (Exception e) {
            LOGGER.error("Exception creating D3D11 shaders for effect: {}", name, e);
        }
    }

    /**
     * Initialize D3D11 constant buffer for shader uniforms
     *
     * Maps Minecraft's individual uniforms to a structured constant buffer.
     * D3D11 requires 16-byte alignment for constant buffer elements.
     */
    @Unique
    private void initializeConstantBuffer() {
        // Calculate required constant buffer size
        // Each uniform needs appropriate alignment (float4 = 16 bytes)
        int bufferSize = calculateConstantBufferSize();

        if (bufferSize == 0) {
            return;
        }

        // Create D3D11 constant buffer
        // TODO: Implement when createConstantBuffer is available
        // constantBufferHandle = getRenderer().createConstantBuffer(bufferSize);

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
     * Calculate constant buffer size with D3D11 alignment rules
     */
    @Unique
    private int calculateConstantBufferSize() {
        int size = 0;

        for (com.mojang.blaze3d.shaders.Uniform uniform : uniforms) {
            int uniformSize = getUniformSize(uniform.getType());
            // D3D11 requires 16-byte alignment
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
     * Load HLSL effect shader source from resources
     */
    @Unique
    private String loadEffectShaderSource(ResourceProvider resourceProvider, String shaderName, String extension) {
        try {
            // Try vitra:shaders/hlsl/ first (our custom effect shaders)
            String classpathPath = "/assets/vitra/shaders/hlsl/" + shaderName + "." + extension;
            InputStream stream = getClass().getResourceAsStream(classpathPath);

            if (stream != null) {
                String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                stream.close();
                LOGGER.info("Loaded effect shader from classpath: {}", classpathPath);
                return source;
            }

            // Fallback: Try loading from ResourceProvider
            ResourceLocation location = ResourceLocation.tryParse("vitra:shaders/hlsl/" + shaderName + "." + extension);
            if (location != null) {
                try {
                    Resource resource = resourceProvider.getResourceOrThrow(location);
                    String source = IOUtils.toString(resource.open(), StandardCharsets.UTF_8);
                    LOGGER.info("Loaded effect shader from ResourceProvider: {}", location);
                    return source;
                } catch (IOException e) {
                    LOGGER.debug("Could not load from ResourceProvider: {}", location);
                }
            }

            LOGGER.error("Effect shader not found: {}.{}", shaderName, extension);
            return null;
        } catch (Exception e) {
            LOGGER.error("Error loading effect shader: {}.{}", shaderName, extension, e);
            return null;
        }
    }

    /**
     * Load cbuffer_common.hlsli include file
     */
    @Unique
    private String loadCBufferCommon(ResourceProvider resourceProvider) {
        try {
            String classpathPath = "/assets/vitra/shaders/hlsl/cbuffer_common.hlsli";
            InputStream stream = getClass().getResourceAsStream(classpathPath);

            if (stream != null) {
                String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                stream.close();
                return source;
            }

            ResourceLocation location = ResourceLocation.tryParse("vitra:shaders/hlsl/cbuffer_common.hlsli");
            if (location != null) {
                Resource resource = resourceProvider.getResourceOrThrow(location);
                return IOUtils.toString(resource.open(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load cbuffer_common.hlsli: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Compile HLSL shader and create D3D11 shader object
     * Uses the same approach as ShaderInstanceMixin for runtime compilation
     */
    @Unique
    private long compileAndCreateShader(String hlslSource, String debugName, String profile, int shaderType) {
        try {
            // Compile HLSL to bytecode using D3DCompile
            byte[] bytecode = compileHLSLToBytes(hlslSource, profile, debugName);

            if (bytecode == null || bytecode.length == 0) {
                LOGGER.error("Failed to compile HLSL shader: {}", debugName);
                return 0;
            }

            // Create D3D11 shader from bytecode
            VitraRenderer renderer = getRenderer();
            if (renderer == null) return 0;

            long handle = renderer.createGLProgramShader(bytecode, bytecode.length, shaderType);

            if (handle != 0) {
                LOGGER.info("Compiled effect shader '{}' ({} bytes)", debugName, bytecode.length);
            } else {
                LOGGER.error("Failed to create D3D11 shader: {}", debugName);
            }

            return handle;
        } catch (Exception e) {
            LOGGER.error("Exception compiling shader: {}", debugName, e);
            return 0;
        }
    }

    /**
     * Compile HLSL source code to bytecode using D3DCompile (runtime compilation).
     * Same implementation as ShaderInstanceMixin.
     *
     * @param hlslSource HLSL shader source code
     * @param target Shader target (e.g., "vs_5_0", "ps_5_0")
     * @param debugName Debug name for error messages
     * @return Compiled shader bytecode, or null if compilation failed
     */
    @Unique
    private byte[] compileHLSLToBytes(String hlslSource, String target, String debugName) {
        try {
            LOGGER.info("[EFFECT_COMPILE] Compiling effect shader: {} (target: {})", debugName, target);

            // Convert HLSL string to bytes (UTF-8)
            byte[] sourceBytes = hlslSource.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Call JNI method to compile using D3DCompile
            // This returns a handle to the compiled shader blob
            long blobHandle = com.vitra.render.jni.VitraD3D11Renderer.compileShaderBYTEARRAY(
                sourceBytes, sourceBytes.length, target, debugName);

            if (blobHandle == 0) {
                String error = com.vitra.render.jni.VitraD3D11Renderer.getLastShaderError();
                LOGGER.error("Failed to compile {} effect shader '{}': {}", target, debugName, error);
                return null;
            }

            // Get bytecode from the blob handle
            byte[] bytecode = com.vitra.render.jni.VitraD3D11Renderer.getBlobBytecode(blobHandle);

            if (bytecode == null) {
                LOGGER.error("Failed to get bytecode from blob for effect shader: {}", debugName);
                return null;
            }

            LOGGER.info("Successfully compiled {} effect shader '{}' ({} bytes)", target, debugName, bytecode.length);
            return bytecode;

        } catch (Exception e) {
            LOGGER.error("Exception while compiling effect shader '{}': {}", debugName, e.getMessage(), e);
            return null;
        }
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
     * @reason Replace OpenGL effect application with D3D11 pipeline binding
     */
    @Overwrite
    public void apply() {
        this.dirty = false;

        // Apply blend mode using D3D11 blend state
        this.blend.apply();

        // Maintain OpenGL program ID for compatibility
        ProgramManager.glUseProgram(this.programId);

        // Bind D3D11 graphics pipeline if changed (only for D3D11, skip for D3D12)
        VitraRenderer renderer = getRenderer();
        if (renderer != null) {
            if (this.pipelineHandle != lastPipelineHandle) {
                renderer.setShaderPipeline(pipelineHandle);
                lastPipelineHandle = this.pipelineHandle;
            }
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

        // Upload and bind constant buffers to D3D11 pipeline (only for D3D11)
        if (renderer != null) {
            renderer.uploadAndBindUBOs();
            LOGGER.trace("Applied D3D11 effect: {}", name);
        }
        // For D3D12 or not initialized: skip (D3D12 has its own effect system)
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL effect clear with D3D11 state reset
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

        LOGGER.trace("Cleared D3D11 effect: {}", name);
    }
}
