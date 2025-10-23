package com.vitra.mixin.render.vertex;

import com.mojang.blaze3d.vertex.*;
import com.vitra.interfaces.ExtendedVertexBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Vec3i;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;

/**
 * Complete BufferBuilder mixin for optimized vertex building with direct memory access.
 * Based on VulkanMod's approach but adapted for DirectX 11.
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

    /**
     * Optimized vertex method for fast format (ExtendedVertexBuilder interface).
     */
    @Override
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.ptr = this.beginVertex();

        if (this.format == DefaultVertexFormat.NEW_ENTITY) {
            // NEW_ENTITY format: Position (12) + Color (4) + UV0 (8) + Overlay (4) + Light (4) + Normal (4) = 36 bytes
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
            // BLOCK format: Position (12) + Color (4) + UV0 (8) + Light (4) + Normal (4) = 32 bytes
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            MemoryUtil.memPutInt(ptr + 12, packedColor);

            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            MemoryUtil.memPutInt(ptr + 24, light);
            MemoryUtil.memPutInt(ptr + 28, packedNormal);

        } else {
            // Generic format - use element-wise approach
            this.elementsToFill = this.initialElementsToFill;

            position(x, y, z);
            fastColor(packedColor);
            fastUv(u, v);
            fastOverlay(overlay);
            light(light);
            fastNormal(packedNormal);
        }
    }

    /**
     * Optimized vertex method for particles (ExtendedVertexBuilder interface).
     */
    @Override
    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        this.ptr = this.beginVertex();

        // PARTICLE format: Position (12) + UV0 (8) + Color (4) + Light (4) = 28 bytes
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);

        MemoryUtil.memPutFloat(ptr + 12, u);
        MemoryUtil.memPutFloat(ptr + 16, v);

        MemoryUtil.memPutInt(ptr + 20, packedColor);
        MemoryUtil.memPutInt(ptr + 24, light);
    }

    /**
     * Fast position write.
     */
    @Unique
    private void position(float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
    }

    /**
     * Fast color write (packed int).
     */
    @Unique
    private void fastColor(int packedColor) {
        long elementPtr = this.beginElement(VertexFormatElement.COLOR);
        if (elementPtr != -1L) {
            MemoryUtil.memPutInt(elementPtr, packedColor);
        }
    }

    /**
     * Fast UV0 write.
     */
    @Unique
    private void fastUv(float u, float v) {
        long elementPtr = this.beginElement(VertexFormatElement.UV0);
        if (elementPtr != -1L) {
            MemoryUtil.memPutFloat(elementPtr, u);
            MemoryUtil.memPutFloat(elementPtr + 4, v);
        }
    }

    /**
     * Fast overlay write.
     */
    @Unique
    private void fastOverlay(int overlay) {
        long elementPtr = this.beginElement(VertexFormatElement.UV1);
        if (elementPtr != -1L) {
            MemoryUtil.memPutInt(elementPtr, overlay);
        }
    }

    /**
     * Fast light write.
     */
    @Unique
    private void light(int light) {
        long elementPtr = this.beginElement(VertexFormatElement.UV2);
        if (elementPtr != -1L) {
            MemoryUtil.memPutInt(elementPtr, light);
        }
    }

    /**
     * Fast normal write (packed int).
     */
    @Unique
    private void fastNormal(int packedNormal) {
        long elementPtr = this.beginElement(VertexFormatElement.NORMAL);
        if (elementPtr != -1L) {
            MemoryUtil.memPutInt(elementPtr, packedNormal);
        }
    }

    /**
     * Overwrite addVertex for optimized vertex building.
     *
     * @author Vitra
     */
    @Overwrite
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                         float normalX, float normalY, float normalZ) {
        if (this.fastFormat) {
            long ptr = this.beginVertex();

            // Position
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            // Color
            MemoryUtil.memPutInt(ptr + 12, color);

            // UV0
            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            int offset;
            if (this.fullFormat) {
                // Overlay (UV1)
                MemoryUtil.memPutInt(ptr + 24, overlay);
                offset = 28;
            } else {
                offset = 24;
            }

            // Light (UV2)
            MemoryUtil.memPutInt(ptr + offset, light);

            // Normal (packed)
            int packedNormal = packNormal(normalX, normalY, normalZ);
            MemoryUtil.memPutInt(ptr + offset + 4, packedNormal);

        } else {
            // Fall back to default implementation
            VertexConsumer.super.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    /**
     * Override putBulkData for optimized BakedQuad rendering.
     */
    @Override
    public void putBulkData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green,
                           float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        putQuadData(matrixEntry, quad, brightness, red, green, blue, alpha, lights, overlay, useQuadColorData);
    }

    /**
     * Optimized BakedQuad rendering with direct memory access.
     */
    @Unique
    private void putQuadData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green,
                            float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        int[] quadData = quad.getVertices();
        Vec3i vec3i = quad.getDirection().getNormal();
        Matrix4f matrix4f = matrixEntry.pose();
        Matrix3f normalMatrix = matrixEntry.normal();

        // Calculate packed normal from quad face direction
        int packedNormal = packTransformedNormal(normalMatrix, vec3i.getX(), vec3i.getY(), vec3i.getZ());

        // Process all 4 vertices of the quad
        for (int k = 0; k < 4; k++) {
            int vertexOffset = k * 8;

            // Extract position
            float x = Float.intBitsToFloat(quadData[vertexOffset]);
            float y = Float.intBitsToFloat(quadData[vertexOffset + 1]);
            float z = Float.intBitsToFloat(quadData[vertexOffset + 2]);

            // Transform position
            float tx = transformX(matrix4f, x, y, z);
            float ty = transformY(matrix4f, x, y, z);
            float tz = transformZ(matrix4f, x, y, z);

            // Extract and process color
            float r, g, b;
            if (useQuadColorData) {
                int quadColor = quadData[vertexOffset + 3];
                float quadR = ((quadColor >> 16) & 0xFF) / 255.0f;
                float quadG = ((quadColor >> 8) & 0xFF) / 255.0f;
                float quadB = (quadColor & 0xFF) / 255.0f;

                r = quadR * brightness[k] * red;
                g = quadG * brightness[k] * green;
                b = quadB * brightness[k] * blue;
            } else {
                r = brightness[k] * red;
                g = brightness[k] * green;
                b = brightness[k] * blue;
            }

            // Pack color
            int packedColor = packColor(r, g, b, alpha);

            // Extract UV
            float u = Float.intBitsToFloat(quadData[vertexOffset + 4]);
            float v = Float.intBitsToFloat(quadData[vertexOffset + 5]);

            // Get light for this vertex
            int light = lights[k];

            // Write vertex using optimized method
            this.vertex(tx, ty, tz, packedColor, u, v, overlay, light, packedNormal);
        }
    }

    /**
     * Pack normal vector to signed normalized int format.
     */
    @Unique
    private static int packNormal(float x, float y, float z) {
        // Normalize the normal vector
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length > 0) {
            x /= length;
            y /= length;
            z /= length;
        }

        // Convert to signed normalized bytes (-127 to 127)
        int nx = (int) (x * 127.0f) & 0xFF;
        int ny = (int) (y * 127.0f) & 0xFF;
        int nz = (int) (z * 127.0f) & 0xFF;

        // Pack into int (RGB format for DirectX 11)
        return nx | (ny << 8) | (nz << 16) | (0 << 24);
    }

    /**
     * Pack transformed normal using normal matrix.
     */
    @Unique
    private static int packTransformedNormal(Matrix3f normalMatrix, float x, float y, float z) {
        // Transform normal
        float tx = normalMatrix.m00() * x + normalMatrix.m01() * y + normalMatrix.m02() * z;
        float ty = normalMatrix.m10() * x + normalMatrix.m11() * y + normalMatrix.m12() * z;
        float tz = normalMatrix.m20() * x + normalMatrix.m21() * y + normalMatrix.m22() * z;

        return packNormal(tx, ty, tz);
    }

    /**
     * Pack RGBA color to int.
     */
    @Unique
    private static int packColor(float r, float g, float b, float a) {
        int ri = (int) (r * 255.0f) & 0xFF;
        int gi = (int) (g * 255.0f) & 0xFF;
        int bi = (int) (b * 255.0f) & 0xFF;
        int ai = (int) (a * 255.0f) & 0xFF;

        // ABGR format for DirectX 11
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    /**
     * Transform X coordinate using matrix.
     */
    @Unique
    private static float transformX(Matrix4f matrix, float x, float y, float z) {
        return matrix.m00() * x + matrix.m01() * y + matrix.m02() * z + matrix.m03();
    }

    /**
     * Transform Y coordinate using matrix.
     */
    @Unique
    private static float transformY(Matrix4f matrix, float x, float y, float z) {
        return matrix.m10() * x + matrix.m11() * y + matrix.m12() * z + matrix.m13();
    }

    /**
     * Transform Z coordinate using matrix.
     */
    @Unique
    private static float transformZ(Matrix4f matrix, float x, float y, float z) {
        return matrix.m20() * x + matrix.m21() * y + matrix.m22() * z + matrix.m23();
    }
}
