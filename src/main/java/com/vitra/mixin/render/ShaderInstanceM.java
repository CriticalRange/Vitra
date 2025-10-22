package com.vitra.mixin.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.interfaces.ShaderMixed;
import com.vitra.render.dx11.D3D11ProgramRegistry;
import com.vitra.render.dx11.DirectX11Pipeline;
import com.vitra.render.dx11.DirectX11UniformBuffer;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.render.shader.HLSLConverter;
import com.vitra.render.shader.ShaderLoadUtil;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.apache.commons.io.IOUtils;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Complete ShaderInstance mixin for renderer shader pipeline management.
 * Based on VulkanMod's approach but adapted for renderer.
 */
@Mixin(ShaderInstance.class)
public class ShaderInstanceM implements ShaderMixed {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderInstanceM.class);

    @Shadow @Final private Map<String, Uniform> uniformMap;
    @Shadow @Final private String name;

    @Shadow @Final @Nullable public Uniform MODEL_VIEW_MATRIX;
    @Shadow @Final @Nullable public Uniform PROJECTION_MATRIX;
    @Shadow @Final @Nullable public Uniform COLOR_MODULATOR;
    @Shadow @Final @Nullable public Uniform LINE_WIDTH;
    @Shadow @Final @Nullable public Uniform GLINT_ALPHA;
    @Shadow @Final @Nullable public Uniform FOG_START;
    @Shadow @Final @Nullable public Uniform FOG_END;
    @Shadow @Final @Nullable public Uniform FOG_COLOR;
    @Shadow @Final @Nullable public Uniform FOG_SHAPE;
    @Shadow @Final @Nullable public Uniform TEXTURE_MATRIX;
    @Shadow @Final @Nullable public Uniform GAME_TIME;
    @Shadow @Final @Nullable public Uniform SCREEN_SIZE;
    @Shadow @Final @Nullable public Uniform CHUNK_OFFSET;

    @Shadow @Final private Map<String, Object> samplerMap;
    @Shadow @Final private List<Integer> samplerLocations;
    @Shadow @Final private List<String> samplerNames;
    @Shadow @Final private List<Uniform> uniforms;
    @Shadow @Final private VertexFormat vertexFormat;
    @Shadow @Final private int programId;
    @Shadow private static int lastProgramId;

    @Unique private String vsPath;
    @Unique private String fsPath;
    @Unique private DirectX11Pipeline pipeline;
    @Unique private DirectX11UniformBuffer uniformBuffer;
    @Unique private boolean doUniformUpdate = false;

    @Override
    public DirectX11Pipeline getPipeline() {
        return pipeline;
    }

    @Override
    public void setPipeline(DirectX11Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void setDoUniformsUpdate() {
        this.doUniformUpdate = true;
    }

    /**
     * Intercept ShaderInstance constructor to create renderer pipeline.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstructed(ResourceProvider resourceProvider, String shaderLocation, VertexFormat format, CallbackInfo ci) {
        try {
            LOGGER.info("Creating renderer shader pipeline for: {}", this.name);

            // Check for Vitra-specific shader config
            JsonObject config = ShaderLoadUtil.getJsonConfig(resourceProvider, "core", this.name);

            if (config == null) {
                // Legacy GLSL shader - convert to HLSL
                createLegacyShader(resourceProvider, format);
            } else {
                // Modern Vitra shader with JSON config
                createModernShader(resourceProvider, format, config);
            }

            // Register pipeline with programId (VulkanMod pattern)
            // This ensures apply() can find the correct pipeline to bind
            if (this.pipeline != null) {
                D3D11ProgramRegistry.registerPipeline(this.programId, this.pipeline);
                LOGGER.info("Registered pipeline '{}' with programId {}", this.name, this.programId);
            } else {
                LOGGER.error("Pipeline is null after creation for shader: {}", this.name);
            }

            LOGGER.info("Successfully created renderer pipeline for shader: {}", this.name);
        } catch (Exception e) {
            LOGGER.error("Failed to create renderer shader pipeline for: {}", this.name, e);
            throw new RuntimeException("Shader creation failed: " + this.name, e);
        }
    }

    /**
     * Redirect shader loading to capture shader paths.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/ShaderInstance;getOrCreate(Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;"))
    private Program captureShaderPaths(ResourceProvider resourceProvider, Program.Type type, String name) {
        String path;
        if (this.name.contains(String.valueOf(ResourceLocation.NAMESPACE_SEPARATOR))) {
            ResourceLocation location = ResourceLocation.parse(name);
            path = location.withPath("shaders/core/%s".formatted(location.getPath())).toString();
        } else {
            path = "shaders/core/%s".formatted(name);
        }

        switch (type) {
            case VERTEX -> this.vsPath = path;
            case FRAGMENT -> this.fsPath = path;
        }

        // Return null - we handle shader compilation ourselves
        return null;
    }

    /**
     * Redirect glBindAttribLocation - we don't use OpenGL attributes.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
              target = "Lcom/mojang/blaze3d/shaders/Uniform;glBindAttribLocation(IILjava/lang/CharSequence;)V"))
    private void skipBindAttr(int program, int index, CharSequence name) {
        // No-op for renderer
    }

    /**
     * Redirect updateLocations - skip OpenGL uniform location queries.
     * renderer uniforms are managed via constant buffers.
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V"))
    private void skipUpdateLocations(ShaderInstance instance) {
        // Skip - renderer uniforms are managed via constant buffers
        // Uniform locations are not needed for renderer
        LOGGER.debug("Skipped updateLocations() for shader: {}", this.name);
    }

    /**
     * Overwrite close() to clean up renderer resources.
     *
     * @author Vitra
     */
    @Overwrite
    public void close() {
        // Unregister pipeline from registry
        D3D11ProgramRegistry.unregisterPipeline(this.programId);

        if (this.pipeline != null) {
            this.pipeline.cleanUp();
        }

        if (this.uniformBuffer != null) {
            this.uniformBuffer.cleanup();
        }
    }

    /**
     * Overwrite apply() to bind renderer pipeline and update uniforms.
     *
     * @author Vitra
     */
    @Overwrite
    public void apply() {
        // Update uniforms if needed
        if (this.doUniformUpdate) {
            updateUniforms();
        }

        // Update OpenGL program ID for compatibility
        if (this.programId != lastProgramId) {
            ProgramManager.glUseProgram(this.programId);
            lastProgramId = this.programId;
        }

        // Bind renderer pipeline
        bindPipeline();
    }

    /**
     * Overwrite setDefaultUniforms() to update uniform buffer.
     *
     * @author Vitra
     */
    @Overwrite
    public void setDefaultUniforms(VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection, Window window) {
        if (!this.doUniformUpdate) {
            return;
        }

        // Update standard Minecraft uniforms
        if (this.MODEL_VIEW_MATRIX != null) {
            this.MODEL_VIEW_MATRIX.set(modelView);
        }

        if (this.PROJECTION_MATRIX != null) {
            this.PROJECTION_MATRIX.set(projection);
        }

        if (this.COLOR_MODULATOR != null) {
            this.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }

        if (this.GLINT_ALPHA != null) {
            this.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }

        if (this.FOG_START != null) {
            this.FOG_START.set(RenderSystem.getShaderFogStart());
        }

        if (this.FOG_END != null) {
            this.FOG_END.set(RenderSystem.getShaderFogEnd());
        }

        if (this.FOG_COLOR != null) {
            this.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }

        if (this.FOG_SHAPE != null) {
            this.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }

        if (this.TEXTURE_MATRIX != null) {
            this.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }

        if (this.GAME_TIME != null) {
            this.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }

        if (this.SCREEN_SIZE != null) {
            this.SCREEN_SIZE.set((float)window.getWidth(), (float)window.getHeight());
        }

        if (this.LINE_WIDTH != null && (mode == VertexFormat.Mode.LINES || mode == VertexFormat.Mode.LINE_STRIP)) {
            this.LINE_WIDTH.set(RenderSystem.getShaderLineWidth());
        }

        RenderSystem.setupShaderLights((ShaderInstance)(Object)this);
    }

    /**
     * Overwrite clear() - not needed for renderer.
     *
     * @author Vitra
     */
    @Overwrite
    public void clear() {
        // No-op for renderer
    }

    /**
     * Bind renderer pipeline and upload uniforms.
     * Uses VulkanMod-style direct pipeline binding.
     */
    @Unique
    private void bindPipeline() {
        // Use instance pipeline directly (VulkanMod pattern)
        // The programId registration is for tracking only, not for retrieval
        if (this.pipeline == null) {
            LOGGER.error("No pipeline initialized for shader: {}", this.name);
            throw new NullPointerException("Shader %s has no initialized pipeline".formatted(this.name));
        }

        // Bind the pipeline
        this.pipeline.bind();

        // Bind textures/samplers
        bindShaderTextures();

        // Upload uniforms if dirty
        if (this.uniformBuffer != null) {
            this.uniformBuffer.uploadIfDirty(this.pipeline);
        }
    }

    /**
     * Bind shader textures and samplers.
     */
    @Unique
    private void bindShaderTextures() {
        int activeTexture = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();

        for (int j = 0; j < this.samplerLocations.size(); j++) {
            String samplerName = this.samplerNames.get(j);
            if (this.samplerMap.get(samplerName) != null) {
                RenderSystem.activeTexture(33984 + j);
                Object textureObj = this.samplerMap.get(samplerName);
                int texId = -1;

                if (textureObj instanceof RenderTarget) {
                    texId = ((RenderTarget)textureObj).getColorTextureId();
                } else if (textureObj instanceof AbstractTexture) {
                    texId = ((AbstractTexture)textureObj).getId();
                } else if (textureObj instanceof Integer) {
                    texId = (Integer)textureObj;
                }

                if (texId != -1) {
                    RenderSystem.bindTexture(texId);
                    RenderSystem.setShaderTexture(j, texId);
                }
            }
        }

        com.mojang.blaze3d.platform.GlStateManager._activeTexture(activeTexture);
    }

    /**
     * Update uniforms from Minecraft's Uniform objects.
     */
    @Unique
    private void updateUniforms() {
        // Upload all Minecraft uniforms
        for (Uniform uniform : this.uniforms) {
            uniform.upload();
        }

        // Update our uniform buffer from suppliers
        if (this.uniformBuffer != null) {
            this.uniformBuffer.updateAllFromSuppliers();
        }
    }

    /**
     * Setup uniform suppliers for uniform buffer.
     */
    @Unique
    private void setupUniformSuppliers() {
        if (this.uniformBuffer == null) {
            return;
        }

        for (DirectX11UniformBuffer.UniformEntry entry : this.uniformBuffer.getUniforms()) {
            Uniform mcUniform = this.uniformMap.get(entry.name);
            if (mcUniform == null) {
                LOGGER.warn("Uniform '{}' not found in Minecraft uniform map", entry.name);
                continue;
            }

            // Create supplier that reads from Minecraft uniform
            entry.setSupplier(() -> {
                if (mcUniform.getType() <= 3) {
                    // Integer types
                    return MemoryUtil.memByteBuffer(mcUniform.getIntBuffer());
                } else if (mcUniform.getType() <= 10) {
                    // Float types
                    return MemoryUtil.memByteBuffer(mcUniform.getFloatBuffer());
                } else {
                    throw new RuntimeException("Unsupported uniform type for: " + mcUniform);
                }
            });
        }
    }

    /**
     * Create pipeline for legacy GLSL shader.
     */
    @Unique
    private void createLegacyShader(ResourceProvider resourceProvider, VertexFormat format) {
        try {
            // VulkanMod approach: Create shaders on-demand, no pre-loading
            // This avoids timing issues where ShaderInstance is created before renderer initialization

            // Load vertex shader source
            String vertPath = this.vsPath + ".vsh";
            ResourceLocation vertLocation = ResourceLocation.parse(vertPath);
            String vshSource = ShaderLoadUtil.loadShaderSource(resourceProvider, vertLocation);

            // Load fragment shader source
            String fragPath = this.fsPath + ".fsh";
            ResourceLocation fragLocation = ResourceLocation.parse(fragPath);
            String fshSource = ShaderLoadUtil.loadShaderSource(resourceProvider, fragLocation);

            // Note: We use precompiled .cso shaders, GLSL->HLSL conversion is done offline
            // Parse GLSL only to extract uniform information
            HLSLConverter converter = new HLSLConverter();
            // converter.process(vshSource, fshSource);  // Disabled - using precompiled shaders

            LOGGER.debug("Loaded GLSL metadata for: {}", this.name);

            // Load precompiled shader bytecode (.cso files)
            // These are compiled offline by the Gradle task
            String vsPath = "/shaders/compiled/" + this.name + "_vs.cso";
            String psPath = "/shaders/compiled/" + this.name + "_ps.cso";

            byte[] vsBytecode = loadShaderBytecode(vsPath);
            byte[] psBytecode = loadShaderBytecode(psPath);

            // Fallback to a basic shader if specific shader is not available
            if (vsBytecode == null || psBytecode == null) {
                LOGGER.warn("Precompiled shaders not found for '{}', falling back to position_tex_color shader", this.name);
                vsBytecode = loadShaderBytecode("/shaders/compiled/position_tex_color_vs.cso");
                psBytecode = loadShaderBytecode("/shaders/compiled/position_tex_color_ps.cso");

                if (vsBytecode == null || psBytecode == null) {
                    throw new RuntimeException("Failed to load fallback shaders for: " + this.name);
                }
            }

            // Get the active renderer type and create shaders accordingly (renderer-agnostic)
            com.vitra.config.RendererType rendererType = getActiveRendererType();
            LOGGER.debug("Creating shader for renderer type: {}", rendererType);

            long vsHandle, psHandle, pipelineHandle;

            if (rendererType == com.vitra.config.RendererType.DIRECTX12 ||
                rendererType == com.vitra.config.RendererType.DIRECTX12_ULTIMATE) {
                // DirectX 12 path
                vsHandle = com.vitra.render.jni.VitraD3D12Native.createShader(vsBytecode, 0); // 0 = vertex shader
                psHandle = com.vitra.render.jni.VitraD3D12Native.createShader(psBytecode, 1); // 1 = pixel shader

                if (vsHandle == 0 || psHandle == 0) {
                    throw new RuntimeException("Failed to create DirectX 12 shader objects for: " + this.name);
                }

                // DirectX 12 requires Pipeline State Objects (PSO) - for now use simplified approach
                pipelineHandle = vsHandle; // Placeholder
            } else {
                // renderer path (default)
                vsHandle = VitraNativeRenderer.createGLProgramShader(vsBytecode, vsBytecode.length, 0); // 0 = vertex shader
                psHandle = VitraNativeRenderer.createGLProgramShader(psBytecode, psBytecode.length, 1); // 1 = pixel shader

                if (vsHandle == 0 || psHandle == 0) {
                    throw new RuntimeException("Failed to create renderer shader objects for: " + this.name);
                }

                // Create pipeline from shader handles
                pipelineHandle = VitraNativeRenderer.createShaderPipeline(vsHandle, psHandle);
                if (pipelineHandle == 0) {
                    throw new RuntimeException("Failed to create renderer shader pipeline for: " + this.name);
                }
            }

            LOGGER.info("Created {} pipeline for '{}': handle=0x{}", rendererType, this.name, Long.toHexString(pipelineHandle));

            this.pipeline = new DirectX11Pipeline(this.name, vsHandle, psHandle, pipelineHandle, format);

            // Create uniform buffer based on parsed uniforms
            int uniformBufferSize = calculateUniformBufferSize(converter.getUniforms());
            if (uniformBufferSize > 0) {
                this.uniformBuffer = new DirectX11UniformBuffer(0, uniformBufferSize);

                // Add uniforms to buffer
                for (HLSLConverter.UniformInfo uniformInfo : converter.getUniforms()) {
                    this.uniformBuffer.addUniform(uniformInfo);
                }

                setupUniformSuppliers();
            }

            this.doUniformUpdate = true;

        } catch (Exception e) {
            LOGGER.error("Error creating legacy shader pipeline for: {}", this.name, e);
            throw new RuntimeException("Legacy shader creation failed", e);
        }
    }

    /**
     * Create pipeline for modern Vitra shader with JSON config.
     */
    @Unique
    private void createModernShader(ResourceProvider resourceProvider, VertexFormat format, JsonObject config) {
        // TODO: Implement modern shader pipeline creation
        // For now, fall back to legacy
        LOGGER.info("Modern shader support not yet implemented, falling back to legacy for: {}", this.name);
        createLegacyShader(resourceProvider, format);
    }

    /**
     * Load precompiled shader bytecode from resources.
     * Following VulkanMod's pattern of using precompiled shaders.
     */
    @Unique
    private byte[] loadShaderBytecode(String resourcePath) {
        try {
            InputStream stream = getClass().getResourceAsStream(resourcePath);
            if (stream == null) {
                LOGGER.warn("Shader bytecode not found: {}", resourcePath);
                return null;
            }
            return IOUtils.toByteArray(stream);
        } catch (Exception e) {
            LOGGER.error("Failed to load shader bytecode: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * Calculate total size needed for uniform buffer.
     */
    @Unique
    private int calculateUniformBufferSize(List<HLSLConverter.UniformInfo> uniforms) {
        int totalSize = 0;
        for (HLSLConverter.UniformInfo uniform : uniforms) {
            totalSize += uniform.size;
        }
        // Align to 16 bytes
        return (totalSize + 15) & ~15;
    }

    /**
     * Get the active renderer type in a renderer-agnostic way.
     */
    @Unique
    private com.vitra.config.RendererType getActiveRendererType() {
        try {
            com.vitra.render.IVitraRenderer renderer = com.vitra.VitraMod.getRenderer();
            if (renderer != null) {
                return renderer.getRendererType();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get active renderer type: {}", e.getMessage());
        }
        // Default to renderer
        return com.vitra.config.RendererType.DIRECTX11;
    }
}
