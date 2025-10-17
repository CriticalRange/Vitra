package com.vitra.compat;

import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.Nullable;

/**
 * Compatibility class for ShaderInstance which exists in Minecraft 1.21.1
 * This provides the correct interface for the ShaderInstance class
 */
public class ShaderInstance {
    private final String name;
    private final VertexFormat format;
    private boolean valid;

    public ShaderInstance(String name, VertexFormat format) {
        this.name = name;
        this.format = format;
        this.valid = true;
    }

    public ShaderInstance(String name) {
        this.name = name;
        this.format = null;
        this.valid = true;
    }

    public String getName() {
        return name;
    }

    public VertexFormat getVertexFormat() {
        return format;
    }

    public boolean isValid() {
        return valid;
    }

    public void invalidate() {
        this.valid = false;
    }

    /**
     * Compatibility method to get the real ShaderInstance
     */
    @Nullable
    public Object getRealShaderInstance() {
        // This would return the actual net.minecraft.client.renderer.ShaderInstance
        // if we had access to it
        return null;
    }

    @Override
    public String toString() {
        return String.format("ShaderInstance[name=%s, format=%s, valid=%s]",
                           name, format, valid);
    }
}