# JNI Integration for Vitra

This document describes the JNI integration that replaces the BGFX rendering system with native DirectX 11 calls.

## Overview

The JNI integration provides:
- Native DirectX 11 rendering through JNI
- Custom shader and buffer management
- Direct access to DirectX 11 features
- Better performance and control over rendering pipeline

## Architecture

### Java Components
- `VitraNativeRenderer` - Main JNI interface for native operations
- `JniShaderManager` - Manages shader loading and caching
- `JniBufferManager` - Handles vertex and index buffer management
- `VitraRenderer` - Updated to use JNI instead of BGFX

### Native Components
- `vitra_native.h` - Header file with JNI function declarations
- `vitra_native.cpp` - Implementation of DirectX 11 rendering
- Native library: `vitra-native.dll` (Windows)

## Building

### Prerequisites
1. **Windows 10/11** - DirectX 11 support required
2. **Visual Studio 2019/2022** - With C++ build tools
3. **Java 21+** - For Java compilation
4. **Gradle** - Build system

### Build Steps

1. **Build Native Library** (Windows only):
   ```bash
   ./gradlew compileNative
   ```
   This will:
   - Detect Visual Studio installation
   - Compile `vitra_native.cpp` into `vitra-native.dll`
   - Link with DirectX 11 libraries

2. **Build Java Code**:
   ```bash
   ./gradlew build
   ```

3. **Run Tests**:
   ```bash
   ./gradlew test
   ```

### Skip Native Compilation
If you want to build without native compilation (for non-Windows platforms):
```bash
./gradlew build -PskipNative
```

## Usage

### Basic Usage
```java
// Initialize renderer
VitraRenderer renderer = new VitraRenderer();
renderer.initialize();

// Set up window handle (from GLFW)
boolean success = renderer.initializeWithWindowHandle(windowHandle);

// Begin frame
renderer.beginFrame();

// Clear screen
renderer.clear(0.0f, 0.0f, 0.0f, 1.0f);

// Set shaders
long pipeline = renderer.getShaderManager().getPipeline("basic");
VitraNativeRenderer.setShaderPipeline(pipeline);

// Draw geometry
VitraNativeRenderer.draw(vertexBuffer, indexBuffer, vertexCount, indexCount);

// End frame
renderer.endFrame();
```

### Shader Management
```java
JniShaderManager shaderManager = renderer.getShaderManager();

// Create shader pipeline
long pipeline = shaderManager.createPipeline("basic");

// Preload common shaders
shaderManager.preloadShaders();
```

### Buffer Management
```java
JniBufferManager bufferManager = renderer.getBufferManager();

// Create vertex buffer
ByteBuffer vertexData = ...;
long vb = bufferManager.createVertexBuffer(vertexData, 32);

// Create index buffer
ByteBuffer indexData = ...;
long ib = bufferManager.createIndexBuffer(indexData, false); // 16-bit indices
```

## Directory Structure

```
src/main/
├── java/com/vitra/render/jni/
│   ├── VitraNativeRenderer.java     # Main JNI interface
│   ├── JniShaderManager.java        # Shader management
│   └── JniBufferManager.java        # Buffer management
├── cpp/
│   └── vitra_native.cpp              # Native implementation
└── include/
    └── vitra_native.h                # Native header file
```

## Native Library Functions

### Core Functions
- `initializeDirectX()` - Initialize DirectX 11 device and swap chain
- `shutdown()` - Cleanup native resources
- `beginFrame()` / `endFrame()` - Frame management
- `resize()` - Handle window resize
- `clear()` - Clear render target

### Resource Management
- `createVertexBuffer()` / `createIndexBuffer()` - Buffer creation
- `createShader()` / `createShaderPipeline()` - Shader management
- `destroyResource()` - Resource cleanup
- `draw()` - Submit draw calls

## Configuration

### Debug Mode
Enable debug mode in `config/vitra.properties`:
```properties
renderer.debug=true
renderer.verbose=true
```

This will enable DirectX 11 debug layer and verbose logging.

### Platform Support
- **Windows** - Full DirectX 11 support (primary platform)
- **Linux/macOS** - Not supported (DirectX 11 specific)

## Migration from BGFX

### Key Changes
1. **Removed BGFX dependencies** from build.gradle
2. **Updated VitraRenderer** to use JNI calls instead of BGFX
3. **Added native compilation** task for DirectX 11 library
4. **Created new shader/buffer management** system

### API Compatibility
Most high-level APIs remain the same:
- `VitraRenderer.initialize()` - Same interface
- `beginFrame()` / `endFrame()` - Same interface
- `clear()` - Same interface
- `resize()` - Same interface

Low-level APIs have changed:
- BGFX-specific functions replaced with JNI equivalents
- Different shader loading mechanism
- New buffer management system

## Troubleshooting

### Common Issues

1. **Visual Studio not found**
   - Install Visual Studio 2019/2022 with C++ tools
   - Ensure "Desktop development with C++" workload is installed

2. **Native library not found**
   - Run `./gradlew compileNative` first
   - Check that `vitra-native.dll` is in the correct location

3. **DirectX 11 initialization failed**
   - Ensure Windows 10/11 with DirectX 11 support
   - Update graphics drivers
   - Check Windows Graphics Tools (for debug mode)

4. **Shader compilation errors**
   - Verify HLSL shader files are in `src/main/resources/shaders/dx11/`
   - Check shader syntax compatibility with DirectX 11

### Debug Tips

1. **Enable verbose logging** in config to see detailed native operations
2. **Use Visual Studio debugger** to debug native code
3. **Check Windows Event Viewer** for DirectX errors
4. **Use Graphics tools** like RenderDoc to debug rendering issues

## Performance Notes

- **DirectX 11** provides better performance than BGFX abstractions
- **JNI overhead** is minimal for bulk operations
- **Memory management** is more controlled with native allocation
- **Shader caching** reduces compilation overhead

## Future Improvements

1. **DirectX 12 support** for modern hardware
2. **Multi-threading** support for better CPU utilization
3. **Compute shaders** for advanced effects
4. **Pipeline state objects** for better performance
5. **Cross-platform** support with OpenGL/Vulkan backends