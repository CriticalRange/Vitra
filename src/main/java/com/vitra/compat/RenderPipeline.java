package com.vitra.compat;

/**
 * Compatibility class for RenderPipeline which may not exist in all Minecraft versions
 * This provides the correct interface for RenderPipeline functionality
 */
public class RenderPipeline {
    private String name;
    private String vertexShader;
    private String fragmentShader;
    private boolean valid = false;

    public RenderPipeline() {
        this.valid = false;
    }

    public RenderPipeline(String name) {
        this.name = name;
        this.valid = name != null && !name.isEmpty();
    }

    public RenderPipeline(String name, String vertexShader, String fragmentShader) {
        this.name = name;
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
        this.valid = name != null && !name.isEmpty();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.valid = name != null && !name.isEmpty();
    }

    public String getVertexShader() {
        return vertexShader;
    }

    public void setVertexShader(String vertexShader) {
        this.vertexShader = vertexShader;
    }

    public String getFragmentShader() {
        return fragmentShader;
    }

    public void setFragmentShader(String fragmentShader) {
        this.fragmentShader = fragmentShader;
    }

    public boolean isValid() {
        return valid;
    }

    public void invalidate() {
        this.valid = false;
    }

    @Override
    public String toString() {
        return String.format("RenderPipeline[name=%s, vertex=%s, fragment=%s, valid=%s]",
                           name, vertexShader, fragmentShader, valid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RenderPipeline that = (RenderPipeline) obj;
        return name != null ? name.equals(that.name) : that.name == null;
    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }
}