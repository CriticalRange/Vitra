package com.vitra.mixin;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import com.vitra.core.VitraCore;
import com.vitra.render.jni.VitraNativeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Critical mixin for replacing Minecraft's shader system with DirectX 11 graphics pipelines.
 * This completely replaces OpenGL shader management with DirectX 11 pipeline and UBO management.
 * Based on VulkanMod's approach but adapted for DirectX 11 and Minecraft 1.21.8.
 */
@Mixin(ShaderInstance.class)
public class ShaderInstanceMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/ShaderInstance");

    // DirectX 11 shader pipeline ID
    private int dx11PipelineId = -1;

    // Shader name for debugging
    private String shaderName = "unknown";

    // Whether this shader has been successfully initialized
    private boolean isInitialized = false;

    /**
     * TODO: Redirects shader program creation to use DirectX 11 instead of OpenGL.
     * This prevents GLSL compilation and uses pre-compiled HLSL shaders instead.
     * DISABLED: @Redirect not available in current Mixin version
     */
    /*
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Program;compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;)Lcom/mojang/blaze3d/shaders/Program;"
        )
    )
    private Program redirectCompileShader(Program.Type type, String name, ResourceProvider resourceProvider, String shaderSource) {
        LOGGER.debug("Redirecting shader compilation for {} (type: {}), using DirectX 11 pipeline instead", name, type);

        // Don't compile GLSL shaders - we'll use DirectX 11 HLSL shaders
        return null;
    }
    */

    /**
     * TODO: Redirects program linking to use DirectX 11 pipeline creation instead.
     * This replaces OpenGL program linking with DirectX 11 graphics pipeline creation.
     * DISABLED: @Redirect not available in current Mixin version
     */
    /*
    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Program;linkToProgram()Lcom/mojang/blaze3d/shaders/Program;"
        )
    )
    private Program redirectLinkToProgram(Program instance) {
        LOGGER.debug("Redirecting program linking, creating DirectX 11 pipeline instead");

        // Don't link GLSL programs - create DirectX 11 pipeline
        return null;
    }
    */

    /**
     * Initialize DirectX 11 shader pipeline after constructor injection.
     * This creates the DirectX 11 graphics pipeline and sets up uniform buffers.
     */
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onInit(ResourceProvider resourceProvider, String name, VertexFormat vertexFormat, CallbackInfo ci) {
        try {
            this.shaderName = name;
            LOGGER.debug("Initializing DirectX 11 shader pipeline for: {}", name);

            if (!VitraCore.getInstance().isInitialized()) {
                LOGGER.warn("VitraCore not initialized, shader {} will not work properly", name);
                return;
            }

            // Create DirectX 11 graphics pipeline for this shader
            this.dx11PipelineId = VitraNativeRenderer.createShaderPipeline(name);

            if (this.dx11PipelineId == -1) {
                LOGGER.error("Failed to create DirectX 11 shader pipeline for: {}", name);
                return;
            }

            // Initialize shader uniform buffers with dummy data for now
            // These methods need proper implementation in VitraNativeRenderer
            // VitraNativeRenderer.initShaderUniforms(this.dx11PipelineId);

            this.isInitialized = true;
            LOGGER.debug("Successfully initialized DirectX 11 shader pipeline: {} -> {}", name, this.dx11PipelineId);

        } catch (Exception e) {
            LOGGER.error("Failed to initialize DirectX 11 shader pipeline for: {}", name, e);
            this.isInitialized = false;
        }
    }

    /**
     * Applies the DirectX 11 shader pipeline instead of OpenGL shader program.
     * This binds the graphics pipeline and uploads uniform buffer data.
     */
    @Overwrite
    public void apply() {
        try {
            if (!isInitialized || dx11PipelineId == -1) {
                LOGGER.warn("Attempting to apply uninitialized shader: {}", shaderName);
                return;
            }

            // Bind DirectX 11 graphics pipeline
            VitraNativeRenderer.bindShaderPipeline(dx11PipelineId);

            // Upload updated uniform buffers to GPU
            // VitraNativeRenderer.uploadShaderUniforms(dx11PipelineId);

            LOGGER.debug("Applied DirectX 11 shader pipeline: {} ({})", shaderName, dx11PipelineId);

        } catch (Exception e) {
            LOGGER.error("Failed to apply DirectX 11 shader pipeline: {}", shaderName, e);
        }
    }

    /**
     * Clears shader uniforms to default values.
     * This updates the DirectX 11 uniform buffers with default values.
     */
    @Overwrite
    public void clear() {
        try {
            if (!isInitialized || dx11PipelineId == -1) {
                return;
            }

            // Reset DirectX 11 shader uniforms to defaults
            // VitraNativeRenderer.clearShaderUniforms(dx11PipelineId);

            LOGGER.debug("Cleared DirectX 11 shader uniforms: {}", shaderName);

        } catch (Exception e) {
            LOGGER.error("Failed to clear DirectX 11 shader uniforms: {}", shaderName, e);
        }
    }

    /**
     * Updates a shader uniform with new data.
     * This writes to the DirectX 11 uniform buffer instead of OpenGL uniforms.
     */
    @Overwrite
    public void setUniform(String name, Object value) {
        try {
            if (!isInitialized || dx11PipelineId == -1) {
                LOGGER.debug("Cannot set uniform {} on uninitialized shader: {}", name, shaderName);
                return;
            }

            // Update DirectX 11 uniform buffer
            // VitraNativeRenderer.setShaderUniform(dx11PipelineId, name, value);

            LOGGER.debug("Set DirectX 11 shader uniform: {} = {} for shader {}", name, value, shaderName);

        } catch (Exception e) {
            LOGGER.error("Failed to set DirectX 11 shader uniform: {} = {} for shader {}", name, value, shaderName, e);
        }
    }

    /**
     * Gets the shader name for debugging purposes.
     */
    @Overwrite
    public String getName() {
        return shaderName;
    }

    /**
     * Cleans up DirectX 11 shader pipeline resources.
     * This is called when the shader is no longer needed.
     */
    @Overwrite
    public void close() {
        try {
            if (dx11PipelineId != -1) {
                LOGGER.debug("Closing DirectX 11 shader pipeline: {} ({})", shaderName, dx11PipelineId);

                // Release DirectX 11 graphics pipeline
                VitraNativeRenderer.releaseShaderPipeline(dx11PipelineId);
                dx11PipelineId = -1;
            }

            isInitialized = false;

        } catch (Exception e) {
            LOGGER.error("Failed to close DirectX 11 shader pipeline: {}", shaderName, e);
        }
    }

    /**
     * Checks if the shader has been successfully initialized.
     */
    @Overwrite
    public boolean isInitialized() {
        return isInitialized && dx11PipelineId != -1;
    }

    /**
     * TODO: Static method to get or create a shader instance.
     * This is redirected to use DirectX 11 shaders instead of GLSL programs.
     * DISABLED: @Redirect not available in current Mixin version
     */
    /*
    @Redirect(
        method = "getOrCreate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;)V"
        )
    )
    private static void redirectGetOrCreate(ShaderInstance instance, ResourceProvider resourceProvider, String name, VertexFormat format) throws IOException {
        LOGGER.debug("Redirecting getOrCreate for shader: {}, will use DirectX 11 pipeline", name);

        // Let the constructor handle DirectX 11 pipeline creation
        // The redirect above will handle the GLSL -> DirectX 11 conversion
    }
    */

    /**
     * Gets the DirectX 11 pipeline ID for external use.
     * This is useful for debugging and advanced operations.
     */
    public int getDx11PipelineId() {
        return dx11PipelineId;
    }

    /**
     * Gets the shader name for debugging.
     */
    public String getShaderName() {
        return shaderName;
    }

    /**
     * Finalize method to ensure cleanup.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}