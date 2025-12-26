package com.vitra.render.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * DirectX 11 implementation of RenderPass.
 * Represents an active render pass with bound render targets.
 */
public class VitraRenderPass implements RenderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/RenderPass");
    private static int drawLogCount = 0;
    
    private final VitraCommandEncoder encoder;
    private final String name;
    private final GpuTextureView colorTarget;
    private final GpuTextureView depthTarget;
    private final OptionalInt clearColor;
    private final OptionalDouble clearDepth;
    private boolean closed = false;
    
    // Current state
    private RenderPipeline currentPipeline;
    private GpuBuffer currentIndexBuffer;
    private VertexFormat.IndexType currentIndexType;
    
    public VitraRenderPass(VitraCommandEncoder encoder, String name,
                           GpuTextureView colorTarget, GpuTextureView depthTarget,
                           OptionalInt clearColor, OptionalDouble clearDepth) {
        this.encoder = encoder;
        this.name = name;
        this.colorTarget = colorTarget;
        this.depthTarget = depthTarget;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;
        
        begin();
    }
    
    private void begin() {
        // CRITICAL: Bind render target from colorTarget
        // This is what the GL backend does in createRenderPass (line 102-103 of GlCommandEncoder)
        if (colorTarget instanceof VitraGpuTextureView vitraColorTarget) {
            long colorHandle = vitraColorTarget.getNativeHandle();
            if (colorHandle != 0) {
                // Set this texture as the render target
                VitraD3D11Renderer.setRenderTarget(colorHandle);
                LOGGER.info("[RENDER_PASS] Set render target: handle=0x{}", Long.toHexString(colorHandle));
            } else {
                // If no specific render target, use the swapchain's back buffer
                VitraD3D11Renderer.setRenderTargetToBackBuffer();
                LOGGER.info("[RENDER_PASS] Set render target to back buffer (colorTarget handle was 0)");
            }
        } else {
            // Fall back to back buffer
            VitraD3D11Renderer.setRenderTargetToBackBuffer();
            LOGGER.info("[RENDER_PASS] Set render target to back buffer (colorTarget is not VitraGpuTextureView)");
        }
        
        // Set viewport (like line 123 of GlCommandEncoder)
        if (colorTarget != null) {
            int width = colorTarget.getWidth(0);
            int height = colorTarget.getHeight(0);
            VitraD3D11Renderer.setViewport(0, 0, width, height);
            LOGGER.info("[RENDER_PASS] Set viewport: {}x{}", width, height);
        }
        
        // Clear if requested
        if (clearColor.isPresent()) {
            int color = clearColor.getAsInt();
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;
            float a = ((color >> 24) & 0xFF) / 255.0f;
            VitraD3D11Renderer.setClearColor(r, g, b, a);
            VitraD3D11Renderer.clear(0x4000);
        }
        
        if (clearDepth.isPresent()) {
            VitraD3D11Renderer.clear(0x100);
        }
    }
    
    @Override
    public void pushDebugGroup(Supplier<String> nameSupplier) {
        // TODO: Implement D3D11 debug markers
    }
    
    @Override
    public void popDebugGroup() {
        // TODO: Implement D3D11 debug markers
    }
    
    @Override
    public void setPipeline(RenderPipeline pipeline) {
        this.currentPipeline = pipeline;
        
        // CRITICAL: Actually bind the shader pipeline!
        // Get or compile the pipeline and bind it
        try {
            VitraCompiledPipeline compiledPipeline = new VitraCompiledPipeline(
                pipeline.hashCode(), pipeline);
            
            if (compiledPipeline.isValid()) {
                compiledPipeline.bind();
                LOGGER.debug("Bound pipeline '{}' (handle: 0x{})", 
                    compiledPipeline.getPipelineName(), 
                    Long.toHexString(compiledPipeline.getNativeHandle()));
            } else {
                LOGGER.warn("Failed to bind invalid pipeline for '{}'", 
                    pipeline.getLocation().getPath());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to set pipeline: {}", e.getMessage());
        }
    }
    
    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        // Map sampler name to texture slot
        int slot = getSamplerSlot(name);
        
        if (textureView instanceof VitraGpuTextureView vitraView) {
            long handle = vitraView.getNativeHandle();
            if (handle != 0) {
                VitraD3D11Renderer.bindTexture(slot, handle);
                LOGGER.debug("bindTexture '{}' slot={} handle=0x{}", name, slot, Long.toHexString(handle));
            } else {
                LOGGER.debug("bindTexture '{}' slot={} - handle is 0, texture may not be created", name, slot);
            }
        } else if (textureView != null) {
            // CRITICAL: Handle MC's GlTextureView by looking up D3D11Texture via texture ID
            // GlTextureView wraps GlTexture which has an int 'id' field
            try {
                // Get the underlying texture
                var texture = textureView.texture();
                
                // Check if it's a GlTexture (MC's default)
                if (texture instanceof com.mojang.blaze3d.opengl.GlTexture glTexture) {
                    int textureId = glTexture.glId();
                    
                    // Look up in D3D11Texture system (where GlStateManager routes textures)
                    com.vitra.render.D3D11Texture d3dTexture = 
                        com.vitra.render.D3D11Texture.getTexture(textureId);
                    
                    if (d3dTexture != null && d3dTexture.getNativeHandle() != 0) {
                        // Bind using the native D3D11 handle
                        VitraD3D11Renderer.bindTexture(slot, d3dTexture.getNativeHandle());
                        LOGGER.debug("bindTexture '{}' slot={} (GlTexture id={}, d3dHandle=0x{})", 
                            name, slot, textureId, Long.toHexString(d3dTexture.getNativeHandle()));
                    } else {
                        // Use OpenGL-style bind (texture ID directly)
                        VitraD3D11Renderer.bindTexture(slot, textureId);
                        LOGGER.debug("bindTexture '{}' slot={} (GlTexture id={}, via ID)", name, slot, textureId);
                    }
                } else {
                    LOGGER.debug("bindTexture '{}' slot={} - non-Vitra texture: {}", 
                        name, slot, textureView.getClass().getSimpleName());
                }
            } catch (Exception e) {
                LOGGER.warn("bindTexture '{}' slot={} - failed to extract texture ID: {}", 
                    name, slot, e.getMessage());
            }
        }
        
        // TODO: Bind sampler if provided - needs bindSampler JNI method
        // For now, samplers are set via texture parameters in D3D11
    }
    
    /**
     * Map sampler name to texture slot.
     * Minecraft 26.1 uses sampler names like "Sampler0", "Sampler1", etc.
     * Also handles special names like "DiffuseSampler", "LightmapSampler".
     */
    private int getSamplerSlot(String name) {
        return switch (name) {
            case "Sampler0", "DiffuseSampler", "BlockAtlasSampler" -> 0;
            case "Sampler1", "LightmapSampler" -> 1;
            case "Sampler2", "OverlaySampler" -> 2;
            case "Sampler3" -> 3;
            case "Sampler4" -> 4;
            case "Sampler5" -> 5;
            case "Sampler6" -> 6;
            case "Sampler7" -> 7;
            default -> {
                // Try to extract number from sampler name
                if (name.startsWith("Sampler") && name.length() > 7) {
                    try {
                        yield Integer.parseInt(name.substring(7));
                    } catch (NumberFormatException e) {
                        yield 0;
                    }
                }
                LOGGER.debug("Unknown sampler name '{}', using slot 0", name);
                yield 0;
            }
        };
    }
    
    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            // Ensure the buffer is created if it's a constant buffer
            vitraBuffer.ensureCreated();
            
            long handle = vitraBuffer.getNativeHandle();
            if (handle != 0) {
                // Map uniform name to constant buffer slot (b0-b3 in HLSL)
                int slot = getSlotForUniform(name);
                VitraD3D11Renderer.bindConstantBufferVS(slot, handle);
                // Also bind to pixel shader for uniforms that might be used there
                VitraD3D11Renderer.bindConstantBufferPS(slot, handle);
            } else {
                // Buffer not created yet or creation failed - skip binding
                // This can happen during early initialization
            }
        }
    }
    
    /**
     * Map uniform name to constant buffer slot.
     * HLSL shaders use b0, b1, b2, b3 registers.
     * Minecraft 26.1 uses these uniforms:
     *   - "Projection" -> b0 (projection matrix)
     *   - "Fog" -> b1 (fog parameters)
     *   - "Globals" -> b2 (global settings)
     *   - "Lighting" -> b3 (light directions)
     *   - "DynamicTransforms" -> b4 (model-view, color modulator, etc.)
     */
    private int getSlotForUniform(String name) {
        return switch (name) {
            case "Projection" -> 0;         // b0 - Projection matrix
            case "Fog" -> 1;                // b1 - Fog parameters
            case "Globals" -> 2;            // b2 - Global settings
            case "Lighting" -> 3;           // b3 - Light directions
            case "DynamicTransforms" -> 4;  // b4 - Model-view, color, etc.
            case "ModelView" -> 4;          // b4 - Same as DynamicTransforms
            case "Transforms" -> 4;         // b4 - Same as DynamicTransforms
            default -> {
                // Log unknown uniforms for debugging
                if (uniformLogCount < 10) {
                    LOGGER.info("Unknown uniform '{}' - using slot 0", name);
                    uniformLogCount++;
                }
                yield 0;
            }
        };
    }
    
    private static int uniformLogCount = 0;
    
    @Override
    public void setUniform(String name, GpuBufferSlice bufferSlice) {
        // CRITICAL: We must use the slice's offset and length!
        // OpenGL uses glBindBufferRange(target, binding, buffer, offset, size)
        // D3D11 needs to use VSSetConstantBuffers1/PSSetConstantBuffers1 with offset
        if (bufferSlice.buffer() instanceof VitraGpuBuffer vitraBuffer) {
            vitraBuffer.ensureCreated();
            
            long handle = vitraBuffer.getNativeHandle();
            long offset = bufferSlice.offset();
            long length = bufferSlice.length();
            
            if (handle != 0) {
                int slot = getSlotForUniform(name);
                // Pass offset and length to native for proper D3D11.1 constant buffer binding
                VitraD3D11Renderer.bindConstantBufferRangeVS(slot, handle, offset, length);
                VitraD3D11Renderer.bindConstantBufferRangePS(slot, handle, offset, length);
                
                if (uniformLogCount < 5) {
                    LOGGER.debug("setUniform (slice) '{}' slot={} handle=0x{} offset={} length={}", 
                        name, slot, Long.toHexString(handle), offset, length);
                }
            }
        } else {
            // Fallback for non-Vitra buffers
            setUniform(name, bufferSlice.buffer());
        }
    }
    
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        VitraD3D11Renderer.setScissor(x, y, width, height);
        // Scissor is enabled when setScissor is called with valid values
    }
    
    @Override
    public void disableScissor() {
        // Reset scissor to full viewport (effectively disables it)
        VitraD3D11Renderer.setScissor(0, 0, 16384, 16384);
    }
    private GpuBuffer currentVertexBuffer; // Track current vertex buffer
    
    @Override
    public void setVertexBuffer(int slot, GpuBuffer buffer) {
        this.currentVertexBuffer = buffer;
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            long handle = vitraBuffer.getNativeHandle();
            // Log first few to diagnose the issue
            LOGGER.info("setVertexBuffer[{}] slot={}, name='{}', handle=0x{}", 
                drawLogCount, slot, vitraBuffer.getName(), Long.toHexString(handle));
        } else if (buffer != null) {
            // Not a VitraGpuBuffer - this is the problem!
            LOGGER.warn("setVertexBuffer: buffer is NOT VitraGpuBuffer, type={}", buffer.getClass().getName());
        } else {
            LOGGER.warn("setVertexBuffer: buffer is null!");
        }
    }
    
    @Override
    public void setIndexBuffer(GpuBuffer buffer, VertexFormat.IndexType indexType) {
        this.currentIndexBuffer = buffer;
        this.currentIndexType = indexType;
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            long handle = vitraBuffer.getNativeHandle();
            if (handle != 0) {
                LOGGER.debug("setIndexBuffer handle=0x{}, type={}", Long.toHexString(handle), indexType);
            }
        }
    }
    
    @Override
    public void drawIndexed(int arg0, int arg1, int arg2, int arg3) {
        // Log raw parameters to understand what MC is actually passing
        // Parameters might be: (baseVertex, firstIndex, indexCount, instanceCount) 
        // OR: (indexCount, instanceCount, firstIndex, baseVertex)
        // We need to figure out which!
        
        if (drawLogCount < 10) {
            LOGGER.info("[DRAW_PARAMS {}] arg0={}, arg1={}, arg2={}, arg3={}", 
                drawLogCount, arg0, arg1, arg2, arg3);
        }
        
        // Get handles for currently bound buffers
        long vbHandle = 0;
        long ibHandle = 0;
        
        if (currentVertexBuffer instanceof VitraGpuBuffer vitraVB) {
            vbHandle = vitraVB.getNativeHandle();
        }
        if (currentIndexBuffer instanceof VitraGpuBuffer vitraIB) {
            ibHandle = vitraIB.getNativeHandle();
        }
        
        // Based on MC source, the order appears to be:
        // executeDraw(pass, baseVertex, firstIndex, drawCount, indexType, instanceCount)
        // So if drawIndexed maps directly:
        // arg0 = baseVertex (or indexCount?)
        // arg1 = firstIndex (or instanceCount?)
        // arg2 = drawCount/indexCount (or firstIndex?)
        // arg3 = instanceCount (or baseVertex?)
        
        // Try treating arg2 as indexCount (following GL convention)
        int indexCount = arg2;  // Hypothesis: 3rd param is the actual count
        int firstIndex = arg1;
        int baseVertex = arg0;
        int instanceCount = arg3;
        
        if (vbHandle != 0 && ibHandle != 0 && indexCount > 0) {
            // Issue the draw call
            VitraD3D11Renderer.draw(vbHandle, ibHandle, baseVertex, firstIndex, indexCount, instanceCount);
            if (drawLogCount < 10) {
                LOGGER.info("[DRAW_OK {}] vb=0x{}, ib=0x{}, count={}, first={}, base={}, inst={}",
                    drawLogCount, Long.toHexString(vbHandle), Long.toHexString(ibHandle),
                    indexCount, firstIndex, baseVertex, instanceCount);
                drawLogCount++;
            }
        } else if (drawLogCount < 10) {
            LOGGER.info("[DRAW_SKIP {}] vb=0x{}, ib=0x{}, indexCount={} (skipped)",
                drawLogCount, Long.toHexString(vbHandle), Long.toHexString(ibHandle), indexCount);
            drawLogCount++;
        }
    }
    
    @Override
    public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer,
                                         VertexFormat.IndexType indexType,
                                         Collection<String> uniforms, T uniformData) {
        // Each draw has its own vertex buffer and can use the shared index buffer
        for (Draw<T> draw : draws) {
            // Set vertex buffer from draw
            setVertexBuffer(draw.slot(), draw.vertexBuffer());
            // Set index buffer (use the per-draw one if available, otherwise use shared)
            GpuBuffer ib = draw.indexBuffer() != null ? draw.indexBuffer() : indexBuffer;
            VertexFormat.IndexType it = draw.indexType() != null ? draw.indexType() : indexType;
            setIndexBuffer(ib, it);
            // Execute draw (no baseVertex in Draw record, using 0)
            drawIndexed(draw.indexCount(), 1, draw.firstIndex(), 0);
        }
    }
    
    @Override
    public void draw(int vertexCount, int instanceCount) {
        // Non-indexed draw
        long vbHandle = 0;
        
        if (currentVertexBuffer instanceof VitraGpuBuffer vitraVB) {
            vbHandle = vitraVB.getNativeHandle();
        }
        
        if (vbHandle != 0 && vertexCount > 0) {
            // Non-indexed draw: pass 0 for indexBuffer
            VitraD3D11Renderer.draw(vbHandle, 0, 0, 0, vertexCount, instanceCount);
            LOGGER.debug("draw vb=0x{}, vertexCount={}, inst={}",
                Long.toHexString(vbHandle), vertexCount, instanceCount);
        } else {
            LOGGER.debug("draw skipped - vb=0x{}, count={}", Long.toHexString(vbHandle), vertexCount);
        }
    }
    
    @Override
    public void close() {
        if (!closed) {
            closed = true;
        }
    }
}
