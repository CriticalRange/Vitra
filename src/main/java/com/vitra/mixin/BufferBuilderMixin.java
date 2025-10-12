package com.vitra.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.render.bgfx.BgfxBufferCache;
import com.vitra.render.bgfx.Util;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Intercepts BufferBuilder.end() to create BGFX vertex and index buffers.
 *
 * This mixin acts as a bridge between Minecraft's BufferBuilder and BGFX:
 * 1. Intercept BufferBuilder.end() when MeshData is created
 * 2. Extract vertex and index data from MeshData
 * 3. Create BGFX buffers using Util.createVertexBuffer() and Util.createIndexBuffer()
 * 4. Store buffer handles in BgfxBufferCache for later use in draw calls
 *
 * NO custom vertex format conversion - BGFX handles all format details internally.
 *
 * NOTE: All public accessor methods have been moved to BgfxBufferCache to comply with
 * Mixin framework rules (mixins cannot have non-private static methods).
 */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("BufferBuilderMixin");

    /**
     * Intercept BufferBuilder.buildOrThrow() to create BGFX buffers when MeshData is built.
     *
     * Minecraft 1.21.8 signature: buildOrThrow()Lcom/mojang/blaze3d/vertex/MeshData;
     * Note: "end()" is a Yarn mapping alias, Mojang mapping is "buildOrThrow()"
     *
     * This uses ONLY BGFX native methods via Util:
     * - Util.createVertexBuffer() -> bgfx_create_vertex_buffer()
     * - Util.createIndexBuffer() -> bgfx_create_index_buffer()
     */
    @Inject(method = "buildOrThrow()Lcom/mojang/blaze3d/vertex/MeshData;", at = @At("RETURN"), remap = false)
    private void onEnd(CallbackInfoReturnable<MeshData> cir) {
        MeshData meshData = cir.getReturnValue();
        if (meshData == null) {
            return;
        }

        try {
            // Store BufferBuilder -> MeshData association for endBatch interception
            BgfxBufferCache.putMeshData((BufferBuilder) (Object) this, meshData);

            // Get vertex data from MeshData using reflection
            // Note: MeshData fields are private in 1.21.8, need to access via reflection
            ByteBuffer vertexBuffer = null;
            int vertexCount = 0;
            int indexCount = 0;
            VertexFormat.Mode mode = null;

            try {
                // Access private vertexBuffer field
                java.lang.reflect.Field vertexBufferField = meshData.getClass().getDeclaredField("vertexBuffer");
                vertexBufferField.setAccessible(true);
                Object vertexBufferResult = vertexBufferField.get(meshData);

                // Get ByteBuffer from ByteBufferBuilder$Result
                if (vertexBufferResult != null) {
                    java.lang.reflect.Method byteBufferMethod = vertexBufferResult.getClass().getDeclaredMethod("byteBuffer");
                    vertexBuffer = (ByteBuffer) byteBufferMethod.invoke(vertexBufferResult);
                }

                // Access private drawState field
                java.lang.reflect.Field drawStateField = meshData.getClass().getDeclaredField("drawState");
                drawStateField.setAccessible(true);
                Object drawState = drawStateField.get(meshData);

                // Get draw parameters from DrawState
                if (drawState != null) {
                    java.lang.reflect.Method vertexCountMethod = drawState.getClass().getDeclaredMethod("vertexCount");
                    java.lang.reflect.Method indexCountMethod = drawState.getClass().getDeclaredMethod("indexCount");
                    java.lang.reflect.Method modeMethod = drawState.getClass().getDeclaredMethod("mode");

                    vertexCount = (Integer) vertexCountMethod.invoke(drawState);
                    indexCount = (Integer) indexCountMethod.invoke(drawState);
                    mode = (VertexFormat.Mode) modeMethod.invoke(drawState);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to access MeshData fields via reflection", e);
                return;
            }

            if (vertexBuffer == null || vertexBuffer.remaining() == 0) {
                LOGGER.trace("Empty vertex buffer, skipping BGFX buffer creation");
                return;
            }

            LOGGER.trace("BufferBuilder.end(): vertices={}, indices={}, mode={}",
                vertexCount, indexCount, mode);

            // Create BGFX vertex buffer using native method
            // Util.createVertexBuffer() calls bgfx_create_vertex_buffer() directly
            short vertexBufferHandle = Util.createVertexBuffer(
                vertexBuffer,
                BGFX.BGFX_BUFFER_NONE  // No special flags
            );

            if (!Util.isValidHandle(vertexBufferHandle)) {
                LOGGER.warn("Failed to create BGFX vertex buffer");
                return;
            }

            // Cache vertex buffer handle
            BgfxBufferCache.putVertexBufferHandle(meshData, vertexBufferHandle);

            LOGGER.trace("Created BGFX vertex buffer: handle={} for MeshData hash={}",
                vertexBufferHandle, System.identityHashCode(meshData));

            // Create index buffer if mesh has indices
            if (indexCount > 0) {
                ByteBuffer indexBuffer = null;

                try {
                    // Access private indexBuffer field
                    java.lang.reflect.Field indexBufferField = meshData.getClass().getDeclaredField("indexBuffer");
                    indexBufferField.setAccessible(true);
                    Object indexBufferResult = indexBufferField.get(meshData);

                    // Get ByteBuffer from ByteBufferBuilder$Result
                    if (indexBufferResult != null) {
                        java.lang.reflect.Method byteBufferMethod = indexBufferResult.getClass().getDeclaredMethod("byteBuffer");
                        indexBuffer = (ByteBuffer) byteBufferMethod.invoke(indexBufferResult);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to access index buffer via reflection", e);
                }

                if (indexBuffer == null) {
                    // No sorted buffer, mesh might not have indices
                    LOGGER.trace("No index buffer in MeshData");
                    return;
                }

                // Create BGFX index buffer using native method
                // Util.createIndexBuffer() calls bgfx_create_index_buffer() directly
                short indexBufferHandle = Util.createIndexBuffer(
                    indexBuffer,
                    BGFX.BGFX_BUFFER_NONE  // No special flags
                );

                if (!Util.isValidHandle(indexBufferHandle)) {
                    LOGGER.warn("Failed to create BGFX index buffer");
                    return;
                }

                // Cache index buffer handle
                BgfxBufferCache.putIndexBufferHandle(meshData, indexBufferHandle);

                LOGGER.trace("Created BGFX index buffer: handle={} for MeshData hash={}",
                    indexBufferHandle, System.identityHashCode(meshData));
            }

        } catch (Exception e) {
            LOGGER.error("Exception creating BGFX buffers in BufferBuilder.end()", e);
        }
    }

}
