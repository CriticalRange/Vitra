package com.vitra.interfaces;

import com.vitra.render.dx11.DirectX11Pipeline;

/**
 * Interface for ShaderInstance mixin to manage DirectX 11 pipeline binding.
 * This allows ShaderInstance to hold and manage DirectX 11 shader pipelines.
 */
public interface ShaderMixed {
    /**
     * Get the DirectX 11 graphics pipeline associated with this shader.
     * @return The DirectX 11 pipeline instance
     */
    DirectX11Pipeline getPipeline();

    /**
     * Set the DirectX 11 graphics pipeline for this shader.
     * @param pipeline The DirectX 11 pipeline to bind
     */
    void setPipeline(DirectX11Pipeline pipeline);

    /**
     * Enable uniform updates for this shader.
     * When enabled, the shader will update uniforms to DirectX 11 constant buffers.
     */
    void setDoUniformsUpdate();
}
