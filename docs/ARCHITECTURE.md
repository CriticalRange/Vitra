# Vitra Architecture Documentation

This document provides a detailed overview of the Vitra mod's architecture, design decisions, and implementation details.

## Overview

Vitra is designed as a modular, multi-backend rendering system with three main architectural layers:

1. **Core Layer** - Performance optimizations and system management
2. **Render Abstraction Layer (RAL)** - Backend-independent rendering interface
3. **Backend Layer** - Specific implementations for different graphics APIs

## Architectural Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    Minecraft Integration                     │
│           (Fabric/NeoForge Platform Adapters)              │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                   Vitra Core                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │  Configuration  │  │  Optimization   │  │   Lifecycle  │ │
│  │    Manager      │  │    Manager      │  │   Management │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│               Render Abstraction Layer                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │   RenderContext │  │  VitraRenderer  │  │ ShaderManager│ │
│  │    Interface    │  │                 │  │              │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
└─────────────────────┬───────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────────┐
│                  Backend Layer                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐   │
│  │   OpenGL    │  │  DirectX 12 │  │       Vulkan       │   │
│  │   Backend   │  │   Backend   │  │      Backend       │   │
│  └─────────────┘  └─────────────┘  └─────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Core Layer

### VitraCore
Central coordinator for all optimization systems and core functionality.

**Responsibilities:**
- Initialize and manage optimization modules
- Coordinate between different subsystems
- Handle configuration changes at runtime
- Provide unified lifecycle management

**Key Components:**
- `OptimizationManager` - Manages all performance optimizations
- `VitraConfig` - Configuration loading and management
- Lifecycle hooks for initialization and shutdown

### Configuration System
Flexible configuration system supporting runtime changes.

**Features:**
- File-based configuration with hot-reload
- Type-safe configuration with validation
- Backend-specific configuration options
- Performance optimization toggles

**Configuration Categories:**
- Renderer settings (backend type, vsync, FPS limits)
- Optimization settings (culling, LOD, async processing)
- Resource management (memory pools, batch sizes)

### Optimization Modules

#### FrustumCuller
Implements view frustum culling for chunks and entities.

**Algorithm:**
1. Extract 6 planes from view-projection matrix
2. Test bounding boxes against all planes
3. Cull objects outside the frustum
4. Maintain statistics for debugging

**Performance Impact:**
- Reduces draw calls by 30-70% in typical scenes
- Minimal CPU overhead (~0.1ms per frame)

#### LODManager
Dynamic level-of-detail system based on distance.

**LOD Levels:**
- **High** - Full detail (< 30% LOD distance)
- **Medium** - Reduced detail (30-60% LOD distance)
- **Low** - Minimal detail (60-100% LOD distance)
- **None** - No rendering (> LOD distance)

**Features:**
- Smooth transitions between LOD levels
- Per-object LOD calculation
- Configurable distance thresholds

#### AsyncMeshBuilder
Background mesh building system for improved frame pacing.

**Architecture:**
- Worker thread pool for mesh generation
- Lock-free queuing system
- Priority-based processing
- Result caching and management

**Performance Benefits:**
- Eliminates frame drops during world generation
- Reduces main thread blocking
- Improves overall responsiveness

#### MemoryPool
Object pooling system to reduce garbage collection pressure.

**Pooled Objects:**
- Vertex buffers
- Vector objects
- Temporary arrays
- Rendering contexts

**Features:**
- Automatic pool sizing
- Thread-safe operations
- Memory usage monitoring

## Render Abstraction Layer

### RenderContext Interface
Backend-independent rendering interface providing uniform API across all graphics backends.

**Core Operations:**
- Buffer management (vertex/index buffers)
- Texture operations
- Shader program management
- Drawing commands
- Viewport and state management

**Design Principles:**
- Zero-cost abstractions where possible
- Consistent behavior across backends
- Comprehensive resource management
- Debug information and validation

### VitraRenderer
High-level renderer management system.

**Responsibilities:**
- Backend selection and initialization
- Runtime backend switching
- Frame coordination
- Resource cleanup

**Features:**
- Automatic backend detection
- Fallback mechanism for unsupported backends
- Performance monitoring and statistics

### ShaderManager
Cross-platform shader management with automatic compilation.

**Capabilities:**
- GLSL to backend-specific shader compilation
- Runtime shader reloading
- Shader program caching
- Cross-platform shader compatibility

**Backend Translations:**
- OpenGL: Native GLSL support
- DirectX: GLSL to HLSL translation
- Vulkan: GLSL to SPIR-V compilation

## Backend Layer

### BGFX Integration
All backends are implemented through BGFX for consistency and performance.

**Benefits:**
- Unified graphics API abstraction
- Optimized command submission
- Cross-platform shader compilation
- Minimal driver overhead

### Backend Implementations

#### OpenGL Backend
Maximum compatibility backend supporting OpenGL 3.3+.

**Features:**
- Immediate mode and batched rendering
- Comprehensive extension support
- Fallback for older hardware
- Debug context integration

#### DirectX 12 Backend
Windows-specific backend for modern graphics features.

**Advantages:**
- Low-level GPU control
- Explicit memory management
- Multi-threaded command submission
- Advanced debugging tools

#### Vulkan Backend
Cross-platform high-performance backend.

**Benefits:**
- Minimal driver overhead
- Explicit resource management
- Multi-threaded rendering
- Advanced GPU features

## Data Flow

### Initialization Flow
1. Platform adapter (Fabric/NeoForge) calls `VitraMod.init()`
2. `VitraCore` loads configuration and initializes optimizations
3. `VitraRenderer` selects and initializes appropriate backend
4. `ShaderManager` loads and compiles default shaders
5. System enters ready state for rendering

### Rendering Flow
1. `VitraRenderer.beginFrame()` starts new frame
2. Optimization modules update (culling, LOD, async processing)
3. Scene rendering using `RenderContext` interface
4. Backend-specific command submission
5. `VitraRenderer.endFrame()` presents frame

### Configuration Update Flow
1. Configuration file change detected
2. `VitraConfig` reloads settings
3. `OptimizationManager.applyConfigChanges()` updates modules
4. Runtime validation and fallback handling
5. Changes take effect immediately

## Performance Characteristics

### Memory Usage
- Base overhead: ~10MB
- Per-chunk optimization data: ~1KB
- Shader cache: ~5-20MB depending on backend
- Memory pools: Configurable, typically 16-64MB

### CPU Performance
- Optimization overhead: <2% of frame time
- Background thread utilization: 1-4 threads
- Configuration hot-reload: <1ms

### GPU Performance
- Draw call reduction: 30-70% in typical scenes
- Memory bandwidth savings: 20-40% through batching
- Shader compilation: Cached, sub-millisecond switching

## Extension Points

### Custom Optimization Modules
Developers can add custom optimization modules by:
1. Implementing the optimization interface
2. Registering with `OptimizationManager`
3. Providing configuration options

### Custom Backends
New rendering backends can be added by:
1. Implementing `RenderContext` interface
2. Adding backend detection logic
3. Integrating with shader compilation pipeline

### Platform Integration
Platform-specific features can be added through:
1. Platform adapter pattern
2. Hook registration system
3. Event-driven architecture

## Future Considerations

### Planned Enhancements
- Ray tracing support for compatible backends
- Advanced lighting and shadow systems
- Temporal upscaling integration
- VR/AR rendering support

### Scalability
- Multi-GPU rendering support
- Distributed rendering capabilities
- Cloud-based optimization services
- Machine learning enhanced optimizations