package com.vitra.mixin.vertex;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Vec3i;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.mixin.matrix.PoseAccessor;
import com.vitra.render.util.MathUtil;
import com.vitra.render.vertex.format.I32_SNorm;
import com.vitra.render.util.ColorUtil;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;

/**
 * BufferBuilder mixin that intercepts vertex data at the memory level
 * Based on VulkanMod's approach but adapted for DirectX 11 backend
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexConsumer {

    @Shadow private boolean fastFormat;
    @Shadow private boolean fullFormat;
    @Shadow private VertexFormat format;

    @Shadow protected abstract long beginVertex();

    @Shadow private int elementsToFill;
    @Shadow @Final private int initialElementsToFill;

    @Shadow protected abstract long beginElement(VertexFormatElement vertexFormatElement);

    private long ptr;

    /**
     * @author Vitra
     * @reason Intercept vertex submission for DirectX 11
     */
    @Overwrite
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        if (GLInterceptor.isActive()) {
            if (this.fastFormat) {
                long ptr = this.beginVertex();
                MemoryUtil.memPutFloat(ptr + 0, x);
                MemoryUtil.memPutFloat(ptr + 4, y);
                MemoryUtil.memPutFloat(ptr + 8, z);

                MemoryUtil.memPutInt(ptr + 12, color);

                MemoryUtil.memPutFloat(ptr + 16, u);
                MemoryUtil.memPutFloat(ptr + 20, v);

                byte i;
                if (this.fullFormat) {
                    MemoryUtil.memPutInt(ptr + 24, overlay);
                    i = 28;
                } else {
                    i = 24;
                }

                MemoryUtil.memPutInt(ptr + i, light);

                int temp = I32_SNorm.packNormal(normalX, normalY, normalZ);
                MemoryUtil.memPutInt(ptr + i + 4, temp);

                // Notify DirectX 11 backend of vertex data
                GLInterceptor.onVertexData(ptr, this.format);
            } else {
                // Use standard vertex processing for complex formats
                addVertexFallback(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
            }
        } else {
            // Fallback to original behavior
            addVertexFallback(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    @Unique
    private void addVertexFallback(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        // Fallback implementation when DirectX 11 interceptor is not active
        VertexConsumer.super.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
    }

    /**
     * @author Vitra
     * @reason Optimized vertex method for common format
     */
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        if (GLInterceptor.isActive()) {
            this.ptr = this.beginVertex();

            if (this.format == DefaultVertexFormat.NEW_ENTITY) {
                MemoryUtil.memPutFloat(ptr + 0, x);
                MemoryUtil.memPutFloat(ptr + 4, y);
                MemoryUtil.memPutFloat(ptr + 8, z);

                MemoryUtil.memPutInt(ptr + 12, packedColor);

                MemoryUtil.memPutFloat(ptr + 16, u);
                MemoryUtil.memPutFloat(ptr + 20, v);

                MemoryUtil.memPutInt(ptr + 24, overlay);

                MemoryUtil.memPutInt(ptr + 28, light);
                MemoryUtil.memPutInt(ptr + 32, packedNormal);

                // Notify DirectX 11 backend
                GLInterceptor.onVertexData(ptr, this.format);
            } else {
                this.elementsToFill = this.initialElementsToFill;
                position(x, y, z);
                fastColor(packedColor);
                fastUv(u, v);
                fastOverlay(overlay);
                light(light);
                fastNormal(packedNormal);
            }
        } else {
            // Fallback
            vertexFallback(x, y, z, packedColor, u, v, overlay, light, packedNormal);
        }
    }

    /**
     * @author Vitra
     * @reason Simplified vertex method for GUI rendering
     */
    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        if (GLInterceptor.isActive()) {
            this.ptr = this.beginVertex();

            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            MemoryUtil.memPutFloat(ptr + 12, u);
            MemoryUtil.memPutFloat(ptr + 16, v);

            MemoryUtil.memPutInt(ptr + 20, packedColor);

            MemoryUtil.memPutInt(ptr + 24, light);

            // Notify DirectX 11 backend
            GLInterceptor.onVertexData(ptr, this.format);
        } else {
            // Fallback
            vertexFallback(x, y, z, u, v, packedColor, light);
        }
    }

    @Unique
    private void vertexFallback(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        // Fallback implementation
        this.elementsToFill = this.initialElementsToFill;
        position(x, y, z);
        fastColor(packedColor);
        fastUv(u, v);
        fastOverlay(overlay);
        light(light);
        fastNormal(packedNormal);
    }

    @Unique
    private void vertexFallback(float x, float y, float z, float u, float v, int packedColor, int light) {
        // Fallback implementation
        position(x, y, z);
        fastColor(packedColor);
        fastUv(u, v);
        light(light);
    }

    public void position(float x, float y, float z) {
        if (GLInterceptor.isActive()) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
        }
    }

    public void fastColor(int packedColor) {
        if (GLInterceptor.isActive()) {
            long ptr = this.beginElement(VertexFormatElement.COLOR);
            if (ptr != -1L) {
                MemoryUtil.memPutInt(ptr, packedColor);
            }
        }
    }

    public void fastUv(float u, float v) {
        if (GLInterceptor.isActive()) {
            long ptr = this.beginElement(VertexFormatElement.UV0);
            if (ptr != -1L) {
                MemoryUtil.memPutFloat(ptr, u);
                MemoryUtil.memPutFloat(ptr + 4, v);
            }
        }
    }

    public void fastOverlay(int o) {
        if (GLInterceptor.isActive()) {
            long ptr = this.beginElement(VertexFormatElement.UV1);
            if (ptr != -1L) {
                MemoryUtil.memPutInt(ptr, o);
            }
        }
    }

    public void light(int l) {
        if (GLInterceptor.isActive()) {
            long ptr = this.beginElement(VertexFormatElement.UV2);
            if (ptr != -1L) {
                MemoryUtil.memPutInt(ptr, l);
            }
        }
    }

    public void fastNormal(int packedNormal) {
        if (GLInterceptor.isActive()) {
            long ptr = this.beginElement(VertexFormatElement.NORMAL);
            if (ptr != -1L) {
                MemoryUtil.memPutInt(ptr, packedNormal);
            }
        }
    }

    /**
     * @author Vitra
     * @reason Optimized bulk data handling for quad rendering
     */
    @Override
    public void putBulkData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green,
                            float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        if (GLInterceptor.isActive()) {
            putQuadData(matrixEntry, quad, brightness, red, green, blue, alpha, lights, overlay, useQuadColorData);
        } else {
            // Fallback to standard implementation
            VertexConsumer.super.putBulkData(matrixEntry, quad, brightness, red, green, blue, alpha, lights, overlay, useQuadColorData);
        }
    }

    @Unique
    private void putQuadData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        int[] quadData = quad.vertices();
        Vec3i vec3i = quad.direction().getUnitVec3i();
        Matrix4f matrix4f = matrixEntry.pose();

        boolean trustedNormals = ((PoseAccessor)(Object)matrixEntry).trustedNormals();
        int normal = MathUtil.packTransformedNorm(matrixEntry.normal(), trustedNormals, vec3i.getX(), vec3i.getY(), vec3i.getZ());

        for (int k = 0; k < 4; ++k) {
            float r, g, b;

            float quadR, quadG, quadB;

            int i = k * 8;
            float x = Float.intBitsToFloat(quadData[i]);
            float y = Float.intBitsToFloat(quadData[i + 1]);
            float z = Float.intBitsToFloat(quadData[i + 2]);

            float tx = MathUtil.transformX(matrix4f, x, y, z);
            float ty = MathUtil.transformY(matrix4f, x, y, z);
            float tz = MathUtil.transformZ(matrix4f, x, y, z);

            if (useQuadColorData) {
                int color = quadData[i + 3];
                quadR = ColorUtil.unpackR(color);
                quadG = ColorUtil.unpackG(color);
                quadB = ColorUtil.unpackB(color);
                r = quadR * brightness[k] * red;
                g = quadG * brightness[k] * green;
                b = quadB * brightness[k] * blue;
            } else {
                r = brightness[k] * red;
                g = brightness[k] * green;
                b = brightness[k] * blue;
            }

            int color = ColorUtil.pack(r, g, b, alpha);

            int light = lights[k];
            float u = Float.intBitsToFloat(quadData[i + 4]);
            float v = Float.intBitsToFloat(quadData[i + 5]);

            this.vertex(tx, ty, tz, color, u, v, overlay, light, normal);
        }
    }
}