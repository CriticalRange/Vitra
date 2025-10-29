package com.vitra.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.vitra.render.VBO;
import com.vitra.render.VitraRenderer;
import com.vitra.render.IVitraRenderer;
import com.vitra.VitraMod;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BufferUploader.class)
public abstract class BufferUploaderMixin {

    // Helper to get renderer instance (with null-safety check)
    // Returns null for D3D12 (which doesn't need GL compatibility layer)
    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer == null) {
            // Not yet initialized - this is expected during early initialization
            return null;
        }

        // If it's already a VitraRenderer (D3D11), return it directly
        if (baseRenderer instanceof VitraRenderer) {
            return (VitraRenderer) baseRenderer;
        }

        // For D3D12, return null (D3D12 doesn't use GL compatibility layer)
        // D3D12 handles rendering directly without going through GL emulation
        return null;
    }

    /**
     * @author
     */
    @Overwrite
    public static void reset() {}

    /**
     * @author
     */
    @Overwrite
    public static void drawWithShader(MeshData meshData) {
        RenderSystem.assertOnRenderThread();

        MeshData.DrawState parameters = meshData.drawState();

        if (parameters.vertexCount() > 0) {
            ShaderInstance shaderInstance = RenderSystem.getShader();

            // Prevent drawing if formats don't match (VulkanMod pattern)
            if (shaderInstance.getVertexFormat() != parameters.format()) {
                meshData.close();
                return;
            }

            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // Set primitive topology (renderer-agnostic)
                renderer.setPrimitiveTopology(parameters.mode().asGLMode);

                // Update shader uniforms
                shaderInstance.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(),
                                                  RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
                shaderInstance.apply();

                // Upload and draw using temporary VBO (immediate mode)
                VBO tempVbo = new VBO(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
                tempVbo.upload(meshData);
                tempVbo.draw();
                tempVbo.close();
            }
            // For D3D12 or not initialized: skip drawing (D3D12 has its own rendering path)
        }

        meshData.close();
    }

    /**
     * @author
     */
    @Overwrite
    public static void draw(MeshData meshData) {
        MeshData.DrawState parameters = meshData.drawState();

        if (parameters.vertexCount() > 0) {
            // VulkanMod does NOT check format here - shader is already bound and trusted
            // Only drawWithShader() checks format when explicitly setting shader

            VitraRenderer renderer = getRenderer();
            if (renderer != null) {
                // Set primitive topology (renderer-agnostic)
                renderer.setPrimitiveTopology(parameters.mode().asGLMode);

                // Upload and draw using temporary VBO (immediate mode)
                VBO tempVbo = new VBO(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
                tempVbo.upload(meshData);
                tempVbo.draw();
                tempVbo.close();
            }
            // For D3D12 or not initialized: skip drawing (D3D12 has its own rendering path)
        }

        meshData.close();
    }

}
