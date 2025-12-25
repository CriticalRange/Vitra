package com.vitra.mixin.render.vertex;

import com.mojang.blaze3d.vertex.*;
import com.vitra.interfaces.ExtendedVertexBuilder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;

/**
 * BufferBuilder mixin for optimized vertex building with direct memory access.
 * Based on VulkanMod's approach but adapted for DirectX.
 * 
 * Updated for Minecraft 26.1 - core API compatible.
 * Note: BakedQuad optimization removed due to 26.1 record-based API changes.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexConsumer, ExtendedVertexBuilder {

    @Shadow private boolean fastFormat;
    @Shadow private boolean fullFormat;
    @Shadow private VertexFormat format;
    @Shadow protected abstract long beginVertex();
    @Shadow private int elementsToFill;
    @Shadow @Final private int initialElementsToFill;
    @Shadow protected abstract long beginElement(VertexFormatElement vertexFormatElement);

    @Unique private long ptr;

    @Override
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.ptr = this.beginVertex();

        if (this.format == DefaultVertexFormat.NEW_ENTITY) {
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, packedColor);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, overlay);
            MemoryUtil.memPutInt(ptr + 28, light);
            MemoryUtil.memPutInt(ptr + 32, packedNormal);
        } else if (this.format == DefaultVertexFormat.BLOCK) {
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, packedColor);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);
            MemoryUtil.memPutInt(ptr + 24, light);
            MemoryUtil.memPutInt(ptr + 28, packedNormal);
        } else {
            this.elementsToFill = this.initialElementsToFill;
            writePosition(x, y, z);
            writeColor(packedColor);
            writeUv(u, v);
            writeOverlay(overlay);
            writeLight(light);
            writeNormal(packedNormal);
        }
    }

    @Override
    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        this.ptr = this.beginVertex();
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
        MemoryUtil.memPutFloat(ptr + 12, u);
        MemoryUtil.memPutFloat(ptr + 16, v);
        MemoryUtil.memPutInt(ptr + 20, packedColor);
        MemoryUtil.memPutInt(ptr + 24, light);
    }

    @Unique
    private void writePosition(float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
    }

    @Unique
    private void writeColor(int packedColor) {
        long elementPtr = this.beginElement(VertexFormatElement.COLOR);
        if (elementPtr != -1L) MemoryUtil.memPutInt(elementPtr, packedColor);
    }

    @Unique
    private void writeUv(float u, float v) {
        long elementPtr = this.beginElement(VertexFormatElement.UV0);
        if (elementPtr != -1L) {
            MemoryUtil.memPutFloat(elementPtr, u);
            MemoryUtil.memPutFloat(elementPtr + 4, v);
        }
    }

    @Unique
    private void writeOverlay(int overlay) {
        long elementPtr = this.beginElement(VertexFormatElement.UV1);
        if (elementPtr != -1L) MemoryUtil.memPutInt(elementPtr, overlay);
    }

    @Unique
    private void writeLight(int light) {
        long elementPtr = this.beginElement(VertexFormatElement.UV2);
        if (elementPtr != -1L) MemoryUtil.memPutInt(elementPtr, light);
    }

    @Unique
    private void writeNormal(int packedNormal) {
        long elementPtr = this.beginElement(VertexFormatElement.NORMAL);
        if (elementPtr != -1L) MemoryUtil.memPutInt(elementPtr, packedNormal);
    }

    /**
     * @author Vitra
     * @reason Optimized direct memory vertex building
     */
    @Overwrite
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                         float normalX, float normalY, float normalZ) {
        if (this.fastFormat) {
            long ptr = this.beginVertex();
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
            MemoryUtil.memPutInt(ptr + 12, color);
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            int offset = this.fullFormat ? 28 : 24;
            if (this.fullFormat) MemoryUtil.memPutInt(ptr + 24, overlay);
            MemoryUtil.memPutInt(ptr + offset, light);
            MemoryUtil.memPutInt(ptr + offset + 4, packNormal(normalX, normalY, normalZ));
        } else {
            VertexConsumer.super.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    @Unique
    private static int packNormal(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0) { x /= length; y /= length; z /= length; }
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;
        return nx | (ny << 8) | (nz << 16);
    }
}
