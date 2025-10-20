package com.vitra.render.dx11;

import com.vitra.render.jni.VitraNativeRenderer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * DirectX 11 Graphics Pipeline.
 * Manages vertex shader, pixel shader, input layout, and constant buffers.
 */
public class DirectX11Pipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectX11Pipeline.class);

    private final String name;
    private final long vertexShaderHandle;
    private final long pixelShaderHandle;
    private final long pipelineHandle;
    private final VertexFormat vertexFormat;

    // Constant buffer handles (b0-b3)
    private final Map<Integer, Long> constantBuffers = new HashMap<>();

    private boolean bound = false;

    public DirectX11Pipeline(String name, long vertexShaderHandle, long pixelShaderHandle,
                           long pipelineHandle, VertexFormat vertexFormat) {
        this.name = name;
        this.vertexShaderHandle = vertexShaderHandle;
        this.pixelShaderHandle = pixelShaderHandle;
        this.pipelineHandle = pipelineHandle;
        this.vertexFormat = vertexFormat;
    }

    /**
     * Bind this pipeline to the rendering context.
     */
    public void bind() {
        if (pipelineHandle != 0) {
            LOGGER.debug("Binding pipeline '{}' with handle: 0x{}", name, Long.toHexString(pipelineHandle));
            VitraNativeRenderer.bindShaderPipeline(pipelineHandle);
            bound = true;
        } else {
            LOGGER.error("Cannot bind pipeline '{}': invalid pipeline handle", name);
        }
    }

    /**
     * Check if this pipeline is currently bound.
     */
    public boolean isBound() {
        return bound;
    }

    /**
     * Upload constant buffer data to GPU.
     * @param slot Constant buffer slot (0-3 for b0-b3)
     * @param data Buffer data to upload
     */
    public void uploadConstantBuffer(int slot, byte[] data) {
        if (slot < 0 || slot > 3) {
            LOGGER.error("Invalid constant buffer slot: {}. Must be 0-3.", slot);
            return;
        }

        Long bufferHandle = constantBuffers.get(slot);
        if (bufferHandle == null) {
            // Create new constant buffer
            bufferHandle = VitraNativeRenderer.createConstantBuffer(data.length);
            if (bufferHandle == 0) {
                LOGGER.error("Failed to create constant buffer for slot {}", slot);
                return;
            }
            constantBuffers.put(slot, bufferHandle);
        }

        // Update buffer data
        VitraNativeRenderer.updateConstantBuffer(bufferHandle, data);

        // Bind to both vertex and pixel shader stages
        VitraNativeRenderer.bindConstantBufferVS(slot, bufferHandle);
        VitraNativeRenderer.bindConstantBufferPS(slot, bufferHandle);
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
     * Clean up pipeline resources.
     */
    public void cleanUp() {
        // Clean up constant buffers
        for (Long bufferHandle : constantBuffers.values()) {
            if (bufferHandle != 0) {
                VitraNativeRenderer.destroyResource(bufferHandle);
            }
        }
        constantBuffers.clear();

        // Clean up pipeline
        if (pipelineHandle != 0) {
            VitraNativeRenderer.destroyShaderPipeline(pipelineHandle);
        }

        bound = false;
    }
}
