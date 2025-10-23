package com.vitra.interfaces;

import com.vitra.render.d3d11.D3D11Pipeline;

/**
 * Interface for ShaderInstance mixin to manage D3D11 pipeline binding.
 * This allows ShaderInstance to hold and manage D3D11 shader pipelines.
 */
public interface ShaderMixed {
    /**
     * Get the D3D11 graphics pipeline associated with this shader.
     * @return The D3D11 pipeline instance
     */
    D3D11Pipeline getPipeline();

    /**
     * Set the D3D11 graphics pipeline for this shader.
     * @param pipeline The D3D11 pipeline to bind
     */
    void setPipeline(D3D11Pipeline pipeline);

    /**
     * Enable uniform updates for this shader.
     * When enabled, the shader will update uniforms to D3D11 constant buffers.
     */
    void setDoUniformsUpdate();
}
