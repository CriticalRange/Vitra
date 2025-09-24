package com.vitra.core.config;

/**
 * Supported rendering backends for Vitra
 */
public enum RendererType {
    /**
     * OpenGL backend - default, maximum compatibility
     */
    OPENGL("OpenGL", true, true, true),

    /**
     * DirectX 12 backend - Windows only, modern features
     */
    DIRECTX12("DirectX 12", true, false, false),

    /**
     * Vulkan backend - cross-platform, maximum performance
     */
    VULKAN("Vulkan", true, true, true),

    /**
     * Software renderer - fallback, CPU-based
     */
    SOFTWARE("Software", true, true, true);

    private final String displayName;
    private final boolean supportsWindows;
    private final boolean supportsLinux;
    private final boolean supportsMacOS;

    RendererType(String displayName, boolean supportsWindows, boolean supportsLinux, boolean supportsMacOS) {
        this.displayName = displayName;
        this.supportsWindows = supportsWindows;
        this.supportsLinux = supportsLinux;
        this.supportsMacOS = supportsMacOS;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSupported() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return supportsWindows;
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return supportsLinux;
        } else if (os.contains("mac")) {
            return supportsMacOS;
        }

        return false; // Unknown OS
    }

    /**
     * Get the best available renderer for the current platform
     */
    public static RendererType getBestAvailable() {
        // Prefer Vulkan if available, fallback to OpenGL, then others
        for (RendererType type : new RendererType[]{VULKAN, OPENGL, DIRECTX12, SOFTWARE}) {
            if (type.isSupported()) {
                return type;
            }
        }
        return OPENGL; // Fallback
    }

    public boolean supportsWindows() { return supportsWindows; }
    public boolean supportsLinux() { return supportsLinux; }
    public boolean supportsMacOS() { return supportsMacOS; }
}