package com.vitra.render.d3d11;

import com.vitra.render.jni.VitraD3D11Renderer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * D3D11 Graphics Pipeline.
 * Manages vertex shader, pixel shader, input layout, and constant buffers.
 */
public class D3D11Pipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(D3D11Pipeline.class);

    private final String name;
    private final long vertexShaderHandle;
    private final long pixelShaderHandle;
    private final long pipelineHandle;
    private final VertexFormat vertexFormat;

    // Constant buffer handles (b0-b3)
    private final Map<Integer, Long> constantBuffers = new HashMap<>();

    private boolean bound = false;

    public D3D11Pipeline(String name, long vertexShaderHandle, long pixelShaderHandle,
                           long pipelineHandle, VertexFormat vertexFormat) {
        this.name = name;
        this.vertexShaderHandle = vertexShaderHandle;
        this.pixelShaderHandle = pixelShaderHandle;
        this.pipelineHandle = pipelineHandle;
        this.vertexFormat = vertexFormat;
    }

    /**
     * Bind this pipeline to the rendering context.
     * CRITICAL FIX: Also set input layout from vertex format to prevent linkage errors.
     *
     * VulkanMod Pattern: Apply depth state based on shader type when binding pipeline.
     * UI shaders should render with depth testing disabled to ensure they appear on top.
     */
    public void bind() {
        if (pipelineHandle != 0) {
            LOGGER.info("[PIPELINE_BIND] Binding pipeline '{}' with vertex format: {}", name, formatToString(vertexFormat));
            LOGGER.debug("Binding pipeline '{}' with handle: 0x{}", name, Long.toHexString(pipelineHandle));

            // VulkanMod Pattern: Configure depth state and blend state based on shader name
            // UI shaders render with depth test DISABLED and blending ENABLED
            // 3D world shaders render with depth test ENABLED and blending based on material
            //
            // PERFORMANCE FIX: Use D3D11RenderState for tracking, then apply immediately
            boolean isUIShader = isUIShader(name);
            if (isUIShader) {
                LOGGER.debug("[DEPTH_STATE] Configuring UI shader '{}': disabling depth test AND depth writes", name);

                // CRITICAL FIX: Disable BOTH depth test AND depth writes for UI
                // VulkanMod approach: UI renders with depthTestEnable=false, depthWriteMask=false
                // This prevents UI from being occluded by panorama depth values
                D3D11RenderState.disableDepthTest();
                D3D11RenderState.depthMask(false);  // CRITICAL: Also disable depth WRITES

                LOGGER.debug("[BLEND_STATE] Enabling alpha blending for UI shader '{}'", name);
                D3D11RenderState.enableBlend();
            } else {
                LOGGER.debug("[DEPTH_STATE] Enabling depth test for 3D shader '{}'", name);
                D3D11RenderState.enableDepthTest();
                D3D11RenderState.depthMask(true);  // Re-enable depth writes for 3D
                // Blending for 3D is managed by RenderSystem state
            }

            // CRITICAL: Apply state changes immediately after setting them
            // This ensures state is active before binding shaders
            D3D11RenderState.applyAllState();

            // CRITICAL: Set input layout from pipeline's vertex format BEFORE binding shaders
            // This fixes the "TEXCOORD0 linkage error" when draw() is called without vertex format
            if (vertexFormat != null) {
                int[] vertexFormatDesc = encodeVertexFormat(vertexFormat);
                VitraD3D11Renderer.setInputLayoutFromVertexFormat(vertexShaderHandle, vertexFormatDesc);
            }

            VitraD3D11Renderer.bindShaderPipeline(pipelineHandle);
            bound = true;

            // FIX #3: Upload uniforms AFTER pipeline binding (VulkanMod pattern)
            // This ensures constant buffers are populated with correct data for THIS shader
            // VulkanMod does: renderer.uploadAndBindUBOs(pipeline) after bindGraphicsPipeline()
            updateAndBindConstantBuffers();
        } else {
            LOGGER.error("Cannot bind pipeline '{}': invalid pipeline handle", name);
        }
    }

    /**
     * FIX #3: Update and bind constant buffers (VulkanMod pattern)
     * Called after pipeline binding to upload uniforms for this specific shader
     *
     * VulkanMod does this in:
     * - Pipeline.DescriptorSets.updateUniforms() → copies CPU data to GPU
     * - Pipeline.DescriptorSets.bindSets() → binds descriptor sets
     */
    private void updateAndBindConstantBuffers() {
        try {
            // Get renderer instance
            com.vitra.render.VitraRenderer renderer = com.vitra.render.VitraRenderer.getInstance();
            if (renderer == null) {
                LOGGER.warn("Renderer not initialized, skipping uniform upload");
                return;
            }

            // Call the public wrapper for uniform upload
            // This syncs VRenderSystem from RenderSystem and uploads to GPU
            renderer.uploadConstantBuffersPublic();

        } catch (Exception e) {
            LOGGER.error("Failed to update constant buffers for pipeline '{}'", name, e);
        }
    }

    /**
     * Check if a shader is a UI shader that should render with depth testing disabled.
     * Based on VulkanMod's shader classification.
     */
    private static boolean isUIShader(String shaderName) {
        return shaderName.equals("position") ||
               shaderName.equals("position_color") ||
               shaderName.equals("position_tex") ||
               shaderName.equals("position_color_tex") ||
               shaderName.equals("position_tex_color") ||
               shaderName.equals("rendertype_gui") ||
               shaderName.equals("rendertype_gui_overlay") ||
               shaderName.equals("rendertype_gui_ghost_recipe_overlay") ||
               shaderName.equals("rendertype_text") ||
               shaderName.equals("rendertype_text_background") ||
               shaderName.equals("rendertype_text_see_through") ||
               shaderName.equals("blit_screen");  // Panorama also uses depth test disabled
    }

    /**
     * Encode Minecraft VertexFormat to int array for JNI.
     * Format: [elementCount, usage1, type1, count1, offset1, usage2, type2, count2, offset2, ...]
     */
    private static int[] encodeVertexFormat(VertexFormat format) {
        var elements = format.getElements();
        int[] desc = new int[1 + elements.size() * 4];
        desc[0] = elements.size();

        int currentOffset = 0;
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i);
            desc[1 + i * 4] = element.usage().ordinal();
            desc[1 + i * 4 + 1] = element.type().ordinal();
            desc[1 + i * 4 + 2] = element.count();
            desc[1 + i * 4 + 3] = currentOffset;

            // Calculate offset for next element
            currentOffset += getElementByteSize(element.type(), element.count());
        }

        return desc;
    }

    /**
     * Calculate byte size of a vertex element based on type and count.
     * Matches VulkanMod's approach of manually calculating sizes.
     */
    private static int getElementByteSize(VertexFormatElement.Type type, int count) {
        return switch (type) {
            case FLOAT -> 4 * count;   // 4 bytes per float
            case UBYTE -> 1 * count;   // 1 byte per ubyte
            case BYTE -> 1 * count;    // 1 byte per byte
            case USHORT -> 2 * count;  // 2 bytes per ushort
            case SHORT -> 2 * count;   // 2 bytes per short
            case UINT -> 4 * count;    // 4 bytes per uint
            case INT -> 4 * count;     // 4 bytes per int
        };
    }

    /**
     * Check if this pipeline is currently bound.
     */
    public boolean isBound() {
        return bound;
    }

    /**
     * DEPRECATED: Upload constant buffer data to GPU.
     *
     * This method is NO LONGER USED - constant buffers are now managed by the UBO system
     * in VitraRenderer.uploadConstantBuffers() which is called from beginFrame().
     *
     * The old D3D11UniformBuffer system that called this method has been disabled.
     * Keeping this method would cause 240-byte constant buffers to be created,
     * conflicting with the new 256-byte UBO system.
     *
     * @deprecated Use VitraRenderer.uploadConstantBuffers() instead
     */
    @Deprecated
    public void uploadConstantBuffer(int slot, byte[] data) {
        LOGGER.warn("uploadConstantBuffer() called but is DEPRECATED - constant buffers are managed by UBO system");
        // DO NOTHING - this method should not be called anymore
        // If you see this warning, there's a code path still using the old uniform system
    }

    /**
     * Get the vertex shader handle.
     */
    public long getVertexShaderHandle() {
        return vertexShaderHandle;
    }

    /**
     * Get the pixel shader handle.
     */
    public long getPixelShaderHandle() {
        return pixelShaderHandle;
    }

    /**
     * Get the pipeline handle.
     */
    public long getPipelineHandle() {
        return pipelineHandle;
    }

    /**
     * Get the vertex format.
     */
    public VertexFormat getVertexFormat() {
        return vertexFormat;
    }

    /**
     * Get the pipeline name.
     */
    public String getName() {
        return name;
    }

    /**
     * Convert vertex format to string for logging.
     */
    private static String formatToString(VertexFormat format) {
        if (format == null) return "null";

        StringBuilder sb = new StringBuilder();
        sb.append("size=").append(format.getVertexSize()).append("B [");
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
     * Clean up pipeline resources.
     */
    public void cleanUp() {
        // Clean up constant buffers
        for (Long bufferHandle : constantBuffers.values()) {
            if (bufferHandle != 0) {
                VitraD3D11Renderer.destroyResource(bufferHandle);
            }
        }
        constantBuffers.clear();

        // Clean up pipeline
        if (pipelineHandle != 0) {
            VitraD3D11Renderer.destroyShaderPipeline(pipelineHandle);
        }

        bound = false;
    }
}
