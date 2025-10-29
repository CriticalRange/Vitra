package com.vitra.config;

/**
 * Supported rendering backends for Vitra
 */
public enum RendererType {
    /**
     * DirectX backend - Windows only
     */
    DIRECTX11("DirectX", true, false, false),

    /**
     * DirectX 12 backend - Windows 10+ only, more modern and stable
     * Supports Ray Tracing, Variable Rate Shading, Mesh Shaders, Sampler Feedback
     */
    DIRECTX12("DirectX 12", true, false, false);

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
        // Prefer DirectX 12, fallback to DirectX 11
        if (DIRECTX12.isSupported()) {
            return DIRECTX12;
        }
        if (DIRECTX11.isSupported()) {
            return DIRECTX11;
        }
        throw new RuntimeException("No supported DirectX renderer found. Vitra requires Windows with DirectX 11 or 12.");
    }

    public boolean supportsWindows() { return supportsWindows; }
    public boolean supportsLinux() { return supportsLinux; }
    public boolean supportsMacOS() { return supportsMacOS; }
}