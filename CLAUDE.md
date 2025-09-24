# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Vitra is a high-performance optimization and multi-backend rendering mod for Minecraft Java Edition. It provides significant performance improvements through advanced rendering techniques and supports multiple graphics APIs including OpenGL, DirectX 12, and Vulkan. Built using Architectury API for multi-platform support (Fabric and NeoForge).

## Architecture

The project follows a three-layer architecture:

1. **Core Layer**: Performance optimizations and system management (`com.vitra.core`)
2. **Render Abstraction Layer (RAL)**: Backend-independent rendering interface (`com.vitra.render`)
3. **Backend Layer**: Specific implementations for different graphics APIs (`com.vitra.render.backend`)

### Module Structure
- **common/**: Shared code containing main optimization and rendering systems
- **fabric/**: Fabric-specific implementation and mod loader integration
- **neoforge/**: NeoForge-specific implementation and mod loader integration

Key classes:
- `VitraMod` - Main mod entry point and lifecycle management
- `VitraCore` - Core optimization and management systems
- `VitraRenderer` - Multi-backend rendering system management
- `RenderContext` - Backend-agnostic rendering interface

## Build System

Uses Gradle with Architectury Loom and additional graphics dependencies:

### Dependencies
- **LWJGL 3.3.3**: Graphics API bindings (OpenGL, BGFX, GLFW)
- **BGFX**: Multi-backend graphics abstraction layer
- **SLF4J 2.0.9**: Logging framework
- Standard Architectury/Fabric/NeoForge dependencies

### Development Commands

```bash
# Build all platforms
./gradlew build

# Run development environment
./gradlew fabric:runClient
./gradlew neoforge:runClient

# Generate IDE files
./gradlew genEclipseRuns
./gradlew genIntellijRuns

# Clean build
./gradlew clean build
```

## Key Systems

### Performance Optimizations
- **FrustumCuller**: Visibility culling for chunks and entities
- **LODManager**: Level-of-detail system based on distance
- **AsyncMeshBuilder**: Background mesh building with worker threads
- **MemoryPool**: Object pooling to reduce GC pressure

### Rendering System
- **Multi-backend support**: OpenGL, DirectX 12, Vulkan, Software
- **BGFX integration**: Unified graphics API abstraction
- **Shader system**: Cross-platform shader management and compilation
- **Demo system**: Example rendering implementations

### Configuration
- File-based configuration with hot-reload (`config/vitra.properties`)
- Runtime backend switching support
- Comprehensive optimization toggles
- Debug and performance monitoring options

## Development Guidelines

### Code Organization
- Core systems go in `common/src/main/java/com/vitra/core/`
- Rendering code goes in `common/src/main/java/com/vitra/render/`
- Platform-specific code only in respective platform modules
- Follow existing package structure and naming conventions

### Testing
- Test changes on both Fabric and NeoForge platforms
- Use `renderer.debug=true` in config for detailed logging
- Monitor optimization statistics during testing
- Test different backend types when working on rendering code

### Performance Considerations
- All rendering code should go through the RenderContext interface
- Optimization modules should be toggleable via configuration
- Memory allocations should use the MemoryPool when possible
- Background processing should use AsyncMeshBuilder patterns

## Key Configuration Files

- **gradle.properties**: Version management and dependency versions
- **common/build.gradle**: LWJGL and BGFX dependency configuration
- **fabric/src/main/resources/fabric.mod.json**: Fabric mod metadata
- **neoforge/src/main/resources/META-INF/neoforge.mods.toml**: NeoForge mod metadata

## Important Notes

- Always test rendering changes with different backends (OpenGL, DirectX, Vulkan)
- Platform-specific code should be minimal - most logic belongs in common/
- Use proper resource cleanup for all rendering resources
- Configuration changes should apply at runtime where possible
- Background threads must be properly managed and shut down
- The mod ID is "vitra" across all platforms

## Common Development Tasks

- **Adding optimizations**: Implement in `core/optimization/`, register with OptimizationManager
- **Extending rendering**: Implement through RenderContext interface
- **Backend support**: Extend BgfxRenderContext or create new implementation
- **Configuration**: Add to VitraConfig with proper defaults and validation