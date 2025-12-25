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
        if (textureView instanceof VitraGpuTextureView vitraView) {
            // TODO: Map name to slot and bind texture
            VitraD3D11Renderer.bindTexture(0, vitraView.getNativeHandle());
        }
    }
    
    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        if (buffer instanceof VitraGpuBuffer vitraBuffer) {
            // Ensure the buffer is created if it's a constant buffer
            vitraBuffer.ensureCreated();
            
            long handle = vitraBuffer.getNativeHandle();
            if (handle != 0) {
                // TODO: Map name to slot - for now using slot 0
                VitraD3D11Renderer.bindConstantBufferVS(0, handle);
            } else {
                // Buffer not created yet or creation failed - skip binding
                // This can happen during early initialization
            }
        }
    }
    
    @Override
    public void setUniform(String name, GpuBufferSlice bufferSlice) {
        setUniform(name, bufferSlice.buffer());
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
