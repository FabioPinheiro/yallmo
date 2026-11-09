# WGSL, resources, and pipelines

[Skill index](../SKILL.md) · [Triangle](../recipes/colored-triangle.md) · [Cube](../recipes/indexed-cube.md)

## Shader/pipeline contract

WGSL is not GLSL. A `@vertex` entry point outputs `@builtin(position)` as homogeneous clip-space `vec4<f32>`. Matching `@location(n)` attributes connect vertex inputs, vertex-to-fragment varyings, and fragment outputs. Integer varyings require flat interpolation. Resource `@group(g) @binding(b)` declarations must agree with JavaScript bind-group layouts and resource types. Vertex location numbers are not binding numbers.

Create shader modules and pipelines at initialization; inspect `await module.getCompilationInfo()` for line/column diagnostics. `createRenderPipelineAsync()` rejects on pipeline failure; use `try/catch` in addition to GPU error scopes. Pipeline target format must match the color attachment. Topology, vertex layouts, depth state, and multisample count are pipeline state, not inferred from buffers.

`layout: "auto"` is convenient for a single pipeline: construct its bind group using `pipeline.getBindGroupLayout(0)`. Auto-derived layouts are pipeline-specific; do not assume bind groups can be shared across separately created pipelines. For stable sharing or dynamic offsets, use an explicit layout. This is an alternative to the cube's auto layout, using its same WGSL and a 64-byte matrix:

```js
const objectLayout = device.createBindGroupLayout({
  entries: [{
    binding: 0,
    visibility: GPUShaderStage.VERTEX,
    buffer: { type: "uniform", minBindingSize: 64 },
  }],
});
const pipelineLayout = device.createPipelineLayout({
  bindGroupLayouts: [objectLayout],
});
// Supply layout: pipelineLayout in the pipeline descriptor.
// Supply layout: objectLayout in createBindGroup().
```

## Bytes, typed arrays, and usage

Usage flags are fixed at creation. Common choices: `VERTEX | COPY_DST`, `INDEX | COPY_DST`, `UNIFORM | COPY_DST`. A `Float32Array` element is 4 bytes; a `Uint16Array` element is 2 bytes. Buffer sizes, binding offsets, vertex stride/attribute offsets, and draw index-buffer offsets are bytes. Draw counts are elements, not bytes.

For interleaved position/color `float32x3` attributes, stride is 24 bytes and offsets are 0 and 12. Vertex input packing is governed by vertex formats, not uniform-struct alignment: tightly packed three-float vertex attributes are valid.

`queue.writeBuffer(buffer, destinationByteOffset, data)` requires a destination offset and copied byte count divisible by 4. If optional `dataOffset`/`size` arguments are supplied, they count typed-array elements (bytes for an ArrayBuffer). Avoid mixing these units. Pad a three-index `Uint16Array` upload to 8 bytes, but still `drawIndexed(3)`. The cube's 36 indices already occupy 72 bytes.

For static initialization, `mappedAtCreation: true` also works: choose a size multiple of 4, fill `getMappedRange()`, then `unmap()` before use. Do not access detached mapped ranges afterward. General `mapAsync` ranges have additional alignment rules (offset multiple of 8, size multiple of 4). Readback usually uses a separate `MAP_READ | COPY_DST` staging buffer; do not combine mapping usage with arbitrary rendering usage.

## Uniform and binding alignment

A column-major `mat4x4<f32>` occupies 64 bytes, with 16-byte alignment. `vec3<f32>` has size 12 but alignment 16; a following scalar can occupy its fourth slot, while arrays/matrices/structs introduce their own stride/padding. Uniform address-space requirements are stricter than naive packed arrays. Use deliberate padding and check WGSL layout rules; do not infer uniform layout from the vertex stride. A portable scalar-array uniform can use 16-byte wrapper elements (e.g. `vec4<f32>`) instead of tightly packed scalar arrays.

For multiple objects, either use separate small buffers/bind groups or suballocate an aligned uniform buffer. Dynamic offsets require `hasDynamicOffset: true` in an explicit layout. Compute the object stride as `Math.ceil(64 / alignment) * alignment`, with `alignment = device.limits.minUniformBufferOffsetAlignment`. Bind `{ buffer, offset: 0, size: 64 }`, then call `setBindGroup(0, group, [objectOffset])`. Both static and dynamic offsets must satisfy applicable alignment limits; never assume 256 universally. Avoid overwriting the same uniform region for many draws in one submission: encode different object slices or separate buffers.

Texture/sampler bindings must agree with WGSL types, texture dimension, sample type, and filterability. A sampled texture needs `TEXTURE_BINDING`; uploads often also need `COPY_DST`. Use explicit layouts for unfilterable formats when auto inference is unsuitable. Buffer-to-texture command copies impose 256-byte `bytesPerRow` alignment when that field is required; `queue.writeTexture` has different row-layout rules. Do not blindly pad every texture upload to 256.

Reference: [MDN GPUQueue.writeBuffer](https://developer.mozilla.org/en-US/docs/Web/API/GPUQueue/writeBuffer), [GPUDevice.createRenderPipeline](https://developer.mozilla.org/en-US/docs/Web/API/GPUDevice/createRenderPipeline), [WGSL memory layout specification](https://www.w3.org/TR/WGSL/#memory-layout).
