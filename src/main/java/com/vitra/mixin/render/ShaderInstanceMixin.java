package com.vitra.mixin.render;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.vitra.interfaces.ShaderMixed;
import com.vitra.render.d3d11.D3D11ProgramRegistry;
import com.vitra.render.d3d11.D3D11Pipeline;
import com.vitra.render.d3d11.D3D11UniformBuffer;
import com.vitra.render.jni.VitraD3D11Renderer;
import com.vitra.render.shader.UniformInfo;
import com.vitra.render.shader.ShaderLoadUtil;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.apache.commons.io.IOUtils;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
public class ShaderInstanceMixin implements ShaderMixed {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderInstanceMixin.class);

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
    @Unique private D3D11Pipeline pipeline;
    @Unique private D3D11UniformBuffer uniformBuffer;
    @Unique private boolean doUniformUpdate = false;  // VulkanMod pattern - set to true after shader creation
    @Unique private static int bindCount = 0;  // Debug counter for texture binding

    @Override
    public D3D11Pipeline getPipeline() {
        return pipeline;
    }

    @Override
    public void setPipeline(D3D11Pipeline pipeline) {
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
                // Legacy GLSL shader - load HLSL directly with shader variant selection
                LOGGER.info("[SHADER_LOAD] Loading shader '{}' with format {}", this.name, formatToString(format));

                // CRITICAL: Use shader variant selection to match shader with vertex format
                // VulkanMod creates input layouts from vertex buffer format, so shader MUST match
                // DirectX 11 requires shader inputs to match buffer data, unused inputs cause warnings
                // Solution: Select shader variant based on actual vertex format capabilities
                int formatFlags = analyzeVertexFormat(format);
                String shaderVariant = selectShaderVariant(this.name, formatFlags);

                if (!shaderVariant.equals(this.name)) {
                    LOGGER.info("[SHADER_VARIANT] Shader '{}' (format={}) redirected to variant '{}'",
                        this.name, formatToString(format), shaderVariant);
                }

                this.vsPath = "vitra:shaders/hlsl/" + shaderVariant;
                this.fsPath = "vitra:shaders/hlsl/" + shaderVariant;

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
     * CRITICAL FIX: Let updateLocations() run to populate uniformMap.
     * VulkanMod approach: Allow Minecraft to query uniform locations (via mocked glGetUniformLocation)
     * so that uniformMap gets populated and MODEL_VIEW_MATRIX, PROJECTION_MATRIX, etc. are not null.
     *
     * The actual OpenGL uniform location query is mocked in UniformMixin.glGetUniformLocation()
     * to return dummy values, since we use DirectX constant buffers instead.
     */
    // REMOVED: @Redirect for skipUpdateLocations - we now let it run to populate uniformMap

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
     * Apply shader program and upload uniforms.
     * Following VulkanMod's pattern - texture binding happens in bindPipeline().
     *
     * @author Vitra (based on VulkanMod)
     * @reason Need to manage D3D11 pipeline and uniform updates
     */
    @Overwrite
    public void apply() {
        RenderSystem.assertOnRenderThread();

        // Upload uniforms if needed (VulkanMod pattern)
        if (this.doUniformUpdate) {
            // Bind textures from samplerMap ONLY (VulkanMod ShaderInstanceM.java:131-153)
            // shaderTextures[] binding happens in bindPipeline() -> bindShaderTextures()
            for (int j = 0; j < this.samplerLocations.size(); ++j) {
                String samplerName = this.samplerNames.get(j);
                if (this.samplerMap.get(samplerName) != null) {
                    RenderSystem.activeTexture(33984 + j);
                    Object samplerObject = this.samplerMap.get(samplerName);
                    int texId = -1;

                    if (samplerObject instanceof RenderTarget) {
                        texId = ((RenderTarget) samplerObject).getColorTextureId();
                    } else if (samplerObject instanceof AbstractTexture) {
                        texId = ((AbstractTexture) samplerObject).getId();
                    } else if (samplerObject instanceof Integer) {
                        texId = (Integer) samplerObject;
                    }

                    if (texId != -1) {
                        RenderSystem.bindTexture(texId);
                        RenderSystem.setShaderTexture(j, texId);
                    }
                }
            }

            // Upload uniform values (VulkanMod ShaderInstanceM.java:155-158)
            for (com.mojang.blaze3d.shaders.Uniform uniform : this.uniforms) {
                uniform.upload();
            }
        }

        // Bind shader program if changed (VulkanMod ShaderInstanceM.java:161-164)
        if (this.programId != lastProgramId) {
            ProgramManager.glUseProgram(this.programId);
            lastProgramId = this.programId;
        }

        // Bind D3D11 pipeline (VulkanMod ShaderInstanceM.java:166)
        // This calls bindShaderTextures() which binds from RenderSystem.shaderTextures[]
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
            LOGGER.debug("[MATRIX_DEBUG] Shader '{}': doUniformUpdate is false, skipping matrix update", this.name);
            return;
        }

        LOGGER.debug("[MATRIX_DEBUG] Shader '{}': Setting matrices - MV exists: {}, Proj exists: {}",
            this.name, (this.MODEL_VIEW_MATRIX != null), (this.PROJECTION_MATRIX != null));

        // FIXED: Remove dual matrix upload - rely solely on beginFrame() → uploadConstantBuffers()
        // VulkanMod pattern: Update Minecraft's uniform objects, which are read by suppliers in VRenderSystem
        // The actual GPU upload happens in VitraRenderer.beginFrame() → uploadConstantBuffers()

        // Update standard Minecraft uniforms - these are read by VRenderSystem suppliers
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
        // CRITICAL: VulkanMod pattern - get textures from RenderSystem, not samplerMap
        // Minecraft sets textures at render time via RenderSystem.setShaderTexture()
        // We must retrieve them via RenderSystem.getShaderTexture()

        int activeTexture = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();

        for (int j = 0; j < this.samplerLocations.size(); j++) {
            String samplerName = this.samplerNames.get(j);

            // CRITICAL FIX: Get texture ID from RenderSystem (VulkanMod line 74)
            // This retrieves the texture that Minecraft set via RenderSystem.setShaderTexture()
            int texId = RenderSystem.getShaderTexture(j);

            // DEBUG: Log first 50 texture bindings to diagnose panorama issue
            if (bindCount < 50) {
                LOGGER.info("[SHADER_TEXTURE_DEBUG {}] Slot={}, TexID={}, Sampler='{}', Shader='{}'",
                    bindCount++, j, texId, samplerName, this.name);
            }

            if (texId != 0) {
                // Bind the texture (this calls our D3D11Texture.bindTexture)
                RenderSystem.activeTexture(33984 + j);
                RenderSystem.bindTexture(texId);
                LOGGER.debug("[SHADER_TEXTURE] Bound texture ID={} to sampler '{}' (slot {})", texId, samplerName, j);
            } else {
                // Fallback: Try samplerMap if RenderSystem doesn't have a texture
                if (this.samplerMap.get(samplerName) != null) {
                    RenderSystem.activeTexture(33984 + j);
                    Object textureObj = this.samplerMap.get(samplerName);
                    int fallbackTexId = -1;

                    if (textureObj instanceof RenderTarget) {
                        fallbackTexId = ((RenderTarget)textureObj).getColorTextureId();
                    } else if (textureObj instanceof AbstractTexture) {
                        fallbackTexId = ((AbstractTexture)textureObj).getId();
                    } else if (textureObj instanceof Integer) {
                        fallbackTexId = (Integer)textureObj;
                    }

                    if (fallbackTexId != -1) {
                        RenderSystem.bindTexture(fallbackTexId);
                        LOGGER.debug("[SHADER_TEXTURE_FALLBACK] Bound texture ID={} from samplerMap to '{}'", fallbackTexId, samplerName);
                    }
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
        LOGGER.info("[UNIFORM_DEBUG] Shader '{}': updateUniforms() called, uniform count: {}, uniformBuffer exists: {}",
            this.name, this.uniforms.size(), (this.uniformBuffer != null));

        // Upload all Minecraft uniforms
        for (Uniform uniform : this.uniforms) {
            uniform.upload();
        }

        // Update our uniform buffer from suppliers
        if (this.uniformBuffer != null) {
            LOGGER.info("[UNIFORM_DEBUG] Shader '{}': Updating uniform buffer from suppliers", this.name);
            this.uniformBuffer.updateAllFromSuppliers();
        } else {
            LOGGER.warn("[UNIFORM_DEBUG] Shader '{}': uniformBuffer is NULL, cannot update uniforms!", this.name);
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

        for (D3D11UniformBuffer.UniformEntry entry : this.uniformBuffer.getUniforms()) {
            Uniform mcUniform = this.uniformMap.get(entry.name);
            if (mcUniform == null) {
                LOGGER.warn("Uniform '{}' not found in Minecraft uniform map - using default value", entry.name);

                // CRITICAL FIX: Set default values for missing uniforms
                // ColorModulator defaults to (1,1,1,1) instead of (0,0,0,0)
                if (entry.name.equals("ColorModulator")) {
                    ByteBuffer defaultColorMod = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
                    defaultColorMod.asFloatBuffer().put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
                    defaultColorMod.flip();
                    entry.setSupplier(() -> defaultColorMod);
                    LOGGER.info("Set default ColorModulator to (1,1,1,1) for shader '{}'", this.name);
                }
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
            // VulkanMod approach: Create shaders on-demand with runtime compilation
            // This avoids shader/vertex format mismatches by compiling shaders dynamically

            // Load vertex shader source
            String vertPath = this.vsPath + ".vsh";
            LOGGER.info("[SHADER_LOAD] Attempting to load vertex shader from path: {}", vertPath);
            ResourceLocation vertLocation = ResourceLocation.parse(vertPath);
            LOGGER.info("[SHADER_LOAD] Parsed ResourceLocation: namespace='{}', path='{}'",
                vertLocation.getNamespace(), vertLocation.getPath());
            String vshSource = ShaderLoadUtil.loadShaderSource(resourceProvider, vertLocation);

            // Load fragment shader source
            String fragPath = this.fsPath + ".fsh";
            LOGGER.info("[SHADER_LOAD] Attempting to load fragment shader from path: {}", fragPath);
            ResourceLocation fragLocation = ResourceLocation.parse(fragPath);
            LOGGER.info("[SHADER_LOAD] Parsed ResourceLocation: namespace='{}', path='{}'",
                fragLocation.getNamespace(), fragLocation.getPath());
            String fshSource = ShaderLoadUtil.loadShaderSource(resourceProvider, fragLocation);

            LOGGER.info("[SHADER_COMPILE] Runtime compiling HLSL shader '{}' for format {}", this.name, formatToString(format));

            // The loaded shaders are ALREADY in HLSL format (from vitra:shaders/hlsl/)
            // Load cbuffer_common.hlsli and inline it to resolve #include directives
            String cbufferCommon = "";
            try {
                ResourceLocation cbufferLocation = ResourceLocation.parse("vitra:shaders/hlsl/cbuffer_common.hlsli");
                cbufferCommon = ShaderLoadUtil.loadShaderSource(resourceProvider, cbufferLocation);
                LOGGER.info("[SHADER_COMPILE] Loaded cbuffer_common.hlsli: {} characters", cbufferCommon.length());
            } catch (Exception e) {
                LOGGER.warn("[SHADER_COMPILE] Could not load cbuffer_common.hlsli, includes may fail: {}", e.getMessage());
            }

            // Replace #include directives with actual content
            vshSource = vshSource.replace("#include \"cbuffer_common.hlsli\"", cbufferCommon);
            fshSource = fshSource.replace("#include \"cbuffer_common.hlsli\"", cbufferCommon);

            LOGGER.info("[SHADER_COMPILE] Loaded HLSL vertex shader: {} characters", vshSource.length());
            LOGGER.info("[SHADER_COMPILE] Loaded HLSL fragment shader: {} characters", fshSource.length());

            // Compile HLSL to bytecode using D3DCompile (runtime compilation)
            byte[] vsBytecode = compileHLSLToBytes(vshSource, "vs_5_0", this.name + "_vs");
            byte[] psBytecode = compileHLSLToBytes(fshSource, "ps_5_0", this.name + "_ps");

            if (vsBytecode == null || psBytecode == null) {
                LOGGER.error("[SHADER_COMPILE] ⚠️ SHADER COMPILATION FAILED FOR: {} (continuing to collect all errors)", this.name);
                return; // Skip shader creation but continue loading other shaders
                //throw new RuntimeException("Failed to compile shaders for: " + this.name);
            }

            LOGGER.info("[SHADER_COMPILE] Successfully compiled shader '{}' (VS: {} bytes, PS: {} bytes)",
                this.name, vsBytecode.length, psBytecode.length);

            // Get the active renderer type and create shaders accordingly (renderer-agnostic)
            com.vitra.config.RendererType rendererType = getActiveRendererType();
            LOGGER.debug("Creating shader for renderer type: {}", rendererType);

            long vsHandle, psHandle, pipelineHandle;

            if (rendererType == com.vitra.config.RendererType.DIRECTX12 ||
                rendererType == com.vitra.config.RendererType.DIRECTX12) {
                // D3D12 path
                vsHandle = com.vitra.render.jni.VitraD3D12Native.createShader(vsBytecode, 0); // 0 = vertex shader
                psHandle = com.vitra.render.jni.VitraD3D12Native.createShader(psBytecode, 1); // 1 = pixel shader

                if (vsHandle == 0 || psHandle == 0) {
                    throw new RuntimeException("Failed to create D3D12 shader objects for: " + this.name);
                }

                // D3D12 requires Pipeline State Objects (PSO) - for now use simplified approach
                pipelineHandle = vsHandle; // Placeholder
            } else {
                // renderer path (default)
                vsHandle = VitraD3D11Renderer.createGLProgramShader(vsBytecode, vsBytecode.length, 0); // 0 = vertex shader
                psHandle = VitraD3D11Renderer.createGLProgramShader(psBytecode, psBytecode.length, 1); // 1 = pixel shader

                if (vsHandle == 0 || psHandle == 0) {
                    throw new RuntimeException("Failed to create renderer shader objects for: " + this.name);
                }

                // Create pipeline from shader handles
                pipelineHandle = VitraD3D11Renderer.createShaderPipeline(vsHandle, psHandle);
                if (pipelineHandle == 0) {
                    throw new RuntimeException("Failed to create renderer shader pipeline for: " + this.name);
                }
            }

            LOGGER.info("Created {} pipeline for '{}': handle=0x{}", rendererType, this.name, Long.toHexString(pipelineHandle));

            this.pipeline = new D3D11Pipeline(this.name, vsHandle, psHandle, pipelineHandle, format);

            // CRITICAL FIX: Create standard uniform buffer for cbuffer b0 (DynamicTransforms)
            // Layout from cbuffer_common.hlsli:
            // - ModelViewMat: mat4 (64 bytes, offset 0)
            // - ColorModulator: vec4 (16 bytes, offset 64)
            // - ModelOffset: vec3 (12 bytes, offset 80) + padding (4 bytes)
            // - TextureMat: mat4 (64 bytes, offset 96)
            // - LineWidth: float (4 bytes, offset 160) + padding (12 bytes)
            // Total: 176 bytes (aligned to 16)

            // However, the actual uniform buffer size should match Vitra's VRenderSystem UBO
            // which uses 208 bytes for vertex UBO (b0)
            this.uniformBuffer = new D3D11UniformBuffer(0, 208);  // b0 register, 208 bytes

            // Add standard Minecraft uniforms to buffer
            // These match the uniforms in cbuffer_common.hlsli
            addStandardUniform("ModelViewMat", "mat4", 0);
            addStandardUniform("ColorModulator", "vec4", 64);
            addStandardUniform("TextureMat", "mat4", 96);
            addStandardUniform("ChunkOffset", "vec3", 80);  // aka ModelOffset
            addStandardUniform("LineWidth", "float", 160);

            setupUniformSuppliers();

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
        // Modern shader with JSON config - still use HLSL shaders with variant selection
        LOGGER.info("[SHADER_LOAD] Loading modern shader '{}' with format {}", this.name, formatToString(format));

        // CRITICAL: Use shader variant selection even for modern shaders
        int formatFlags = analyzeVertexFormat(format);
        String shaderVariant = selectShaderVariant(this.name, formatFlags);

        if (!shaderVariant.equals(this.name)) {
            LOGGER.info("[SHADER_VARIANT] Shader '{}' redirected to variant '{}'", this.name, shaderVariant);
        }

        // Set paths to Vitra HLSL shaders, not Minecraft GLSL shaders
        this.vsPath = "vitra:shaders/hlsl/" + shaderVariant;
        this.fsPath = "vitra:shaders/hlsl/" + shaderVariant;

        // Create shader using legacy path (which loads HLSL)
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
     * Add a standard uniform to the uniform buffer.
     * Helper method for precompiled shaders with known cbuffer layout.
     */
    @Unique
    private void addStandardUniform(String name, String type, int offset) {
        if (this.uniformBuffer == null) {
            LOGGER.warn("Cannot add uniform '{}': uniformBuffer is null", name);
            return;
        }

        int size = getUniformSize(type);
        UniformInfo uniformInfo = new UniformInfo(type, name, 0);

        // Use reflection to set the offset since UniformInfo doesn't expose it
        // Or just create directly in D3D11UniformBuffer
        this.uniformBuffer.addUniform(uniformInfo);

        LOGGER.debug("Added standard uniform: {} (type={}, offset={}, size={})", name, type, offset, size);
    }

    /**
     * Get size in bytes for a uniform type.
     */
    @Unique
    private int getUniformSize(String type) {
        return switch (type) {
            case "mat4" -> 64;  // 4x4 matrix = 16 floats = 64 bytes
            case "mat3" -> 48;  // 3x3 matrix = 9 floats (+padding) = 48 bytes
            case "vec4" -> 16;  // 4 floats = 16 bytes
            case "vec3" -> 16;  // 3 floats (+padding) = 16 bytes
            case "vec2" -> 8;   // 2 floats = 8 bytes
            case "float" -> 4;   // 1 float = 4 bytes
            case "int" -> 4;     // 1 int = 4 bytes
            default -> 16;       // default to vec4 size
        };
    }

    /**
     * Calculate total size needed for uniform buffer.
     */
    @Unique
    private int calculateUniformBufferSize(List<UniformInfo> uniforms) {
        int totalSize = 0;
        for (UniformInfo uniform : uniforms) {
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

    /**
     * Compile HLSL source code to bytecode using D3DCompile (runtime compilation).
     * This is similar to VulkanMod's approach of compiling shaders on-the-fly.
     *
     * @param hlslSource HLSL shader source code
     * @param target Shader target (e.g., "vs_5_0", "ps_5_0")
     * @param debugName Debug name for error messages
     * @return Compiled shader bytecode, or null if compilation failed
     */
    @Unique
    private byte[] compileHLSLToBytes(String hlslSource, String target, String debugName) {
        try {
            // Log what we're about to compile
            LOGGER.info("[MIXIN] compileHLSLToBytes called:");
            LOGGER.info("[MIXIN]   - debugName: '{}'", debugName);
            LOGGER.info("[MIXIN]   - target: '{}'", target);
            LOGGER.info("[MIXIN]   - HLSL source length: {} chars", hlslSource.length());

            // Log relevant lines around common error locations
            String[] lines = hlslSource.split("\n");

            // For rendertype_outline_ps, log around line 114
            if (debugName.contains("rendertype_outline") || debugName.contains("rendertype_text_intensity")) {
                LOGGER.info("[MIXIN] HLSL Lines 110-120 (checking for swizzle errors):");
                for (int i = 109; i < Math.min(120, lines.length); i++) {
                    LOGGER.info("[MIXIN]   Line {}: {}", i + 1, lines[i]);
                }
            } else {
                // Default: log lines 20-26
                LOGGER.info("[MIXIN] HLSL Lines 20-26:");
                for (int i = 19; i < Math.min(26, lines.length); i++) {
                    LOGGER.info("[MIXIN]   Line {}: {}", i + 1, lines[i]);
                }
            }

            // Convert HLSL string to bytes (UTF-8)
            byte[] sourceBytes = hlslSource.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("[MIXIN]   - Byte array length: {}", sourceBytes.length);

            // Call JNI method to compile using D3DCompile
            // This returns a handle to the compiled shader blob
            long blobHandle = 0;
            try {
                LOGGER.info("[JAVA_SIDE] BEFORE compileShaderBYTEARRAY call");
                blobHandle = VitraD3D11Renderer.compileShaderBYTEARRAY(sourceBytes, sourceBytes.length, target, debugName);
                LOGGER.info("[JAVA_SIDE] AFTER compileShaderBYTEARRAY call");
            } catch (UnsatisfiedLinkError e) {
                LOGGER.error("[JAVA_SIDE] UnsatisfiedLinkError calling compileShader: {}", e.getMessage());
                throw e;
            }

            LOGGER.info("[JAVA_SIDE] compileShader returned handle: 0x{} ({})", Long.toHexString(blobHandle), blobHandle);

            if (blobHandle == 0) {
                String error = VitraD3D11Renderer.getLastShaderError();
                LOGGER.error("Failed to compile {} shader '{}': {}", target, debugName, error);
                return null;
            }

            // Get bytecode from the blob handle
            LOGGER.info("[JAVA_SIDE] Calling getBlobBytecode with handle: 0x{} ({})", Long.toHexString(blobHandle), blobHandle);
            byte[] bytecode = VitraD3D11Renderer.getBlobBytecode(blobHandle);

            if (bytecode == null) {
                LOGGER.error("Failed to get bytecode from blob for {} shader '{}' with handle 0x{}", target, debugName, Long.toHexString(blobHandle));
                return null;
            }

            LOGGER.info("Successfully compiled {} shader '{}' ({} bytes)", target, debugName, bytecode.length);
            return bytecode;

        } catch (Exception e) {
            LOGGER.error("Exception while compiling {} shader '{}': {}", target, debugName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Analyze vertex format to determine required shader inputs.
     * Returns a bitmask of shader requirements:
     * - Bit 0 (0x01): Has POSITION (always required)
     * - Bit 1 (0x02): Has COLOR
     * - Bit 2 (0x04): Has UV0 (main texture coordinates)
     * - Bit 3 (0x08): Has UV1 (overlay/lightmap)
     * - Bit 4 (0x10): Has UV2 (secondary lightmap)
     * - Bit 5 (0x20): Has NORMAL
     */
    @Unique
    private int analyzeVertexFormat(VertexFormat format) {
        int flags = 0;

        for (VertexFormatElement element : format.getElements()) {
            // Compare with static VertexFormatElement fields since usage() returns Usage enum
            // which has different values (e.g., Usage.UV vs the element VertexFormatElement.UV0)
            VertexFormatElement.Usage usage = element.usage();

            switch (usage) {
                case POSITION -> flags |= 0x01;
                case COLOR -> flags |= 0x02;
                case UV -> {
                    // UV can be UV0, UV1, or UV2 - check the element index
                    if (element == VertexFormatElement.UV0 || element.equals(VertexFormatElement.UV0)) {
                        flags |= 0x04;
                    } else if (element == VertexFormatElement.UV1 || element.equals(VertexFormatElement.UV1)) {
                        flags |= 0x08;
                    } else if (element == VertexFormatElement.UV2 || element.equals(VertexFormatElement.UV2)) {
                        flags |= 0x10;
                    } else {
                        // Default to UV0 if we can't determine which UV
                        flags |= 0x04;
                    }
                }
                case NORMAL -> flags |= 0x20;
                default -> {}
            }
        }

        return flags;
    }

    /**
     * Select the correct shader variant based on vertex format capabilities.
     * This implements VulkanMod-style shader variant selection to match
     * shader input requirements with available vertex data.
     *
     * Strategy:
     * 1. For shaders with known variants (position, position_color, position_tex, etc.),
     *    select the variant that matches the vertex format
     * 2. For complex shaders, try to find the closest match
     * 3. Fall back to the base shader name if no better match exists
     *
     * @param baseShaderName The shader name Minecraft requested (e.g., "position", "rendertype_solid")
     * @param formatFlags Bitmask of vertex format capabilities from analyzeVertexFormat()
     * @return The shader name to actually load (may include variant suffix)
     */
    @Unique
    private String selectShaderVariant(String baseShaderName, int formatFlags) {
        boolean hasPosition = (formatFlags & 0x01) != 0;
        boolean hasColor = (formatFlags & 0x02) != 0;
        boolean hasUV0 = (formatFlags & 0x04) != 0;
        boolean hasUV1 = (formatFlags & 0x08) != 0;
        boolean hasUV2 = (formatFlags & 0x10) != 0;
        boolean hasNormal = (formatFlags & 0x20) != 0;

        LOGGER.debug("[SHADER_VARIANT] Selecting variant for '{}': flags=0x{} (pos={}, color={}, uv0={}, uv1={}, uv2={}, normal={})",
            baseShaderName, Integer.toHexString(formatFlags), hasPosition, hasColor, hasUV0, hasUV1, hasUV2, hasNormal);

        // Handle basic position-based shaders (most common)
        if (baseShaderName.equals("position")) {
            if (hasUV0 && hasColor) return "position_tex_color";
            if (hasUV0) return "position_tex";
            if (hasColor) return "position_color";
            return "position";  // Position-only
        }

        // Handle shaders that explicitly mention their format in the name
        // If the vertex format doesn't match what the shader name suggests, try to adapt
        if (baseShaderName.startsWith("position_")) {
            // Shader name suggests specific inputs, but vertex format might differ
            // Try to find best match

            if (baseShaderName.contains("tex_color")) {
                if (!hasUV0) {
                    // Shader wants texture but format doesn't have it - downgrade to position_color
                    LOGGER.warn("[SHADER_VARIANT] Shader '{}' expects UV but format lacks it, using position_color", baseShaderName);
                    return "position_color";
                }
                return baseShaderName;  // Good match
            }

            if (baseShaderName.contains("tex_lightmap")) {
                if (!hasUV0 || !hasUV2) {
                    LOGGER.warn("[SHADER_VARIANT] Shader '{}' expects UV0+UV2 but format lacks them", baseShaderName);
                    // Try to find closest match
                    if (hasUV0 && hasColor) return "position_color_tex";
                    if (hasColor) return "position_color";
                    return "position";
                }
                return baseShaderName;
            }

            if (baseShaderName.contains("tex")) {
                if (!hasUV0) {
                    LOGGER.warn("[SHADER_VARIANT] Shader '{}' expects UV but format lacks it", baseShaderName);
                    if (hasColor) return "position_color";
                    return "position";
                }
                return baseShaderName;
            }
        }

        // For rendertype shaders, use the base name - these are typically
        // matched correctly by Minecraft (VulkanMod pattern: shaders match vertex formats)
        if (baseShaderName.startsWith("rendertype_")) {
            // Most rendertype shaders are specific to their geometry
            // Just use the requested shader
            return baseShaderName;
        }

        // For other shaders (particles, entities, etc.), trust Minecraft's selection
        // unless there's an obvious mismatch
        if (baseShaderName.equals("particle") || baseShaderName.startsWith("entity")) {
            // These shaders typically expect position + color + uv
            if (!hasUV0) {
                LOGGER.warn("[SHADER_VARIANT] Shader '{}' likely expects UV but format lacks it", baseShaderName);
                // Don't try to adapt - these shaders are specialized
            }
            return baseShaderName;
        }

        // Default: use the base shader name (VulkanMod approach - no dynamic fallback)
        LOGGER.debug("[SHADER_VARIANT] Using base shader name '{}'", baseShaderName);
        return baseShaderName;
    }

    /**
     * Convert vertex format to string for logging.
     */
    @Unique
    private String formatToString(VertexFormat format) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var element : format.getElements()) {
            if (!first) sb.append(", ");
            sb.append(element.usage().name());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Dump shader source to file for debugging.
     */
    @Unique
    private void dumpShaderToFile(String filename, String source) {
        try {
            // Use "shader_dumps" relative to current directory (which is already "run" when Minecraft runs)
            java.nio.file.Path shaderDumpDir = java.nio.file.Paths.get("shader_dumps");
            java.nio.file.Files.createDirectories(shaderDumpDir);

            java.nio.file.Path filePath = shaderDumpDir.resolve(filename);
            java.nio.file.Files.writeString(filePath, source, java.nio.charset.StandardCharsets.UTF_8);

            LOGGER.debug("[SHADER_DUMP] Written shader to: {}", filePath.toAbsolutePath());
        } catch (java.io.IOException e) {
            LOGGER.error("[SHADER_DUMP] Failed to dump shader to file: {}", filename, e);
        }
    }

}
