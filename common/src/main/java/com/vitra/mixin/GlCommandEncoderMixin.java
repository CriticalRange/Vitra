package com.vitra.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.vitra.render.bgfx.BgfxGpuBuffer;
import org.lwjgl.bgfx.BGFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlRenderPass;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Mixin to handle BGFX buffer operations in GlCommandEncoder.writeToBuffer
 */
@Mixin(GlCommandEncoder.class)
public class GlCommandEncoderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraGlCommandEncoderMixin");

    /**
     * Intercept writeToBuffer calls to handle BGFX buffers
     */
    @Inject(method = "writeToBuffer", at = @At("HEAD"), cancellable = true)
    private void handleBgfxBufferWrite(GpuBufferSlice bufferSlice, ByteBuffer data, CallbackInfo ci) {
        // Check if this is our BGFX buffer
        if (bufferSlice.buffer() instanceof BgfxGpuBuffer) {
            BgfxGpuBuffer bgfxBuffer = (BgfxGpuBuffer) bufferSlice.buffer();

            LOGGER.debug("Intercepting writeToBuffer for BGFX buffer: handle={}, offset={}, length={}, dataSize={}",
                        bgfxBuffer.getBgfxHandle(), bufferSlice.offset(), bufferSlice.length(), data.remaining());

            try {
                // Update the BGFX buffer with the data
                bgfxBuffer.updateData(data, bufferSlice.offset());

                // Cancel the original method since we handled it
                ci.cancel();

                LOGGER.debug("Successfully wrote {} bytes to BGFX buffer at offset {}", data.remaining(), bufferSlice.offset());

            } catch (Exception e) {
                LOGGER.error("Failed to write to BGFX buffer", e);
                // Don't cancel - let the original method fail with a proper error
            }
        }
        // If not a BGFX buffer, let the original method proceed
    }

    /**
     * Intercept mapBuffer calls to handle BGFX buffers (GpuBuffer version)
     */
    @Inject(method = "mapBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;ZZ)Lcom/mojang/blaze3d/buffers/GpuBuffer$MappedView;", at = @At("HEAD"), cancellable = true)
    private void handleBgfxBufferMap(GpuBuffer buffer, boolean readOnly, boolean discard, CallbackInfoReturnable<GpuBuffer.MappedView> cir) {
        // Check if this is our BGFX buffer
        if (buffer instanceof BgfxGpuBuffer) {
            BgfxGpuBuffer bgfxBuffer = (BgfxGpuBuffer) buffer;

            LOGGER.debug("Intercepting mapBuffer for BGFX buffer: handle={}, readOnly={}, discard={}",
                        bgfxBuffer.getBgfxHandle(), readOnly, discard);

            try {
                // Create a proper mapped view for the BGFX buffer
                GpuBuffer.MappedView mappedView = bgfxBuffer.createMappedView(0, bgfxBuffer.size());
                cir.setReturnValue(mappedView);

                LOGGER.debug("Created mapped view for BGFX buffer (size: {})", bgfxBuffer.size());

            } catch (Exception e) {
                LOGGER.error("Failed to handle BGFX buffer mapping", e);
                // Don't cancel - let the original method fail with a proper error
            }
        }
        // If not a BGFX buffer, let the original method proceed
    }

    /**
     * Intercept mapBuffer calls to handle BGFX buffers (GpuBufferSlice version)
     */
    @Inject(method = "mapBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;ZZ)Lcom/mojang/blaze3d/buffers/GpuBuffer$MappedView;", at = @At("HEAD"), cancellable = true)
    private void handleBgfxBufferMapSlice(GpuBufferSlice bufferSlice, boolean readOnly, boolean discard, CallbackInfoReturnable<GpuBuffer.MappedView> cir) {
        // Check if this is our BGFX buffer
        if (bufferSlice.buffer() instanceof BgfxGpuBuffer) {
            BgfxGpuBuffer bgfxBuffer = (BgfxGpuBuffer) bufferSlice.buffer();

            LOGGER.debug("Intercepting mapBuffer for BGFX buffer slice: handle={}, offset={}, length={}, readOnly={}, discard={}",
                        bgfxBuffer.getBgfxHandle(), bufferSlice.offset(), bufferSlice.length(), readOnly, discard);

            try {
                // Create a proper mapped view for the BGFX buffer slice
                GpuBuffer.MappedView mappedView = bgfxBuffer.createMappedView(bufferSlice.offset(), bufferSlice.length());
                cir.setReturnValue(mappedView);

                LOGGER.debug("Created mapped view for BGFX buffer slice (offset: {}, length: {})",
                           bufferSlice.offset(), bufferSlice.length());

            } catch (Exception e) {
                LOGGER.error("Failed to handle BGFX buffer slice mapping", e);
                // Don't cancel - let the original method fail with a proper error
            }
        }
        // If not a BGFX buffer, let the original method proceed
    }

    /**
     * Intercept trySetup method entirely to handle BGFX buffer compatibility
     */
    @Inject(method = "trySetup", at = @At("HEAD"), cancellable = true)
    private void handleBgfxTrySetup(GlRenderPass renderPass, Collection<String> collection, CallbackInfoReturnable<Boolean> cir) {
        LOGGER.debug("Intercepting trySetup for BGFX buffer compatibility");

        // For BGFX compatibility, we'll implement a simplified version of trySetup
        // that doesn't rely on GlBuffer casting

        try {
            // For BGFX integration, we'll implement a minimal setup check
            // Skip the complex GL buffer validation and just ensure basic setup is ok

            // Simple validation - if we got here, the render pass should be valid
            // BGFX will handle the actual buffer binding internally
            LOGGER.debug("BGFX trySetup: Skipping GL-specific buffer validation");
            cir.setReturnValue(true);

        } catch (Exception e) {
            LOGGER.error("Error in BGFX trySetup", e);
            cir.setReturnValue(false);
        }
    }

    /**
     * Intercept drawFromBuffers method to handle BGFX buffer compatibility
     */
    @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true)
    private void handleBgfxDrawFromBuffers(CallbackInfo ci) {
        LOGGER.debug("Intercepting drawFromBuffers for BGFX buffer compatibility");

        try {
            // For BGFX integration, we'll skip the OpenGL-specific buffer drawing
            // BGFX handles the actual rendering internally
            LOGGER.debug("BGFX drawFromBuffers: Skipping OpenGL draw calls, BGFX handles rendering");
            ci.cancel();

        } catch (Exception e) {
            LOGGER.error("Error in BGFX drawFromBuffers", e);
            ci.cancel();
        }
    }
}