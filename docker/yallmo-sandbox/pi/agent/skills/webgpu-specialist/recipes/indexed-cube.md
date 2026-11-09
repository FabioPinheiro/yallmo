# Indexed, depth-tested rotating cube

[Skill index](../SKILL.md) · [Required browser host](browser-host.md) · [Camera/mesh explanation](../references/models-rendering.md)

Replace the triangle's `scene.js` with this entire block; keep the same host. Includes all geometry, column-major matrix math, WGSL, resources, and drawing. The eight shared vertices provide smooth interpolated colors, not face normals or lighting. Outward CCW indices allow back-face culling. No external math library or asset fetch is required.

## `scene.js`

```js
// Column-major matrices, column vectors: multiply(a, b) returns a * b.
function multiply(a, b) {
  const out = new Float32Array(16);
  for (let col = 0; col < 4; col++) {
    for (let row = 0; row < 4; row++) {
      for (let k = 0; k < 4; k++) {
        out[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
      }
    }
  }
  return out;
}
function perspective(fovY, aspect, near, far) {
  // Right-handed view, looking -Z; NDC depth 0..1.
  const f = 1 / Math.tan(fovY / 2);
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, far / (near - far), -1,
    0, 0, (near * far) / (near - far), 0,
  ]);
}
function rotationX(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([
    1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1,
  ]);
}
function rotationY(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([
    c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1,
  ]);
}

export async function createScene({ device, format, checkedShader }) {
  const module = await checkedShader(device, `
    struct Uniforms { mvp: mat4x4<f32>, };
    @group(0) @binding(0) var<uniform> uniforms: Uniforms;
    struct VertexOutput {
      @builtin(position) position: vec4<f32>,
      @location(0) color: vec3<f32>,
    };
    @vertex fn vs(
      @location(0) position: vec3<f32>,
      @location(1) color: vec3<f32>
    ) -> VertexOutput {
      var out: VertexOutput;
      out.position = uniforms.mvp * vec4<f32>(position, 1.0);
      out.color = color;
      return out;
    }
    @fragment fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
      return vec4<f32>(in.color, 1.0);
    }
  `, "cube shader");
  const depthFormat = "depth24plus";
  const pipeline = await device.createRenderPipelineAsync({
    label: "indexed cube pipeline",
    layout: "auto",
    vertex: {
      module, entryPoint: "vs",
      buffers: [{
        arrayStride: 24,
        attributes: [
          { shaderLocation: 0, offset: 0, format: "float32x3" },
          { shaderLocation: 1, offset: 12, format: "float32x3" },
        ],
      }],
    },
    fragment: { module, entryPoint: "fs", targets: [{ format }] },
    primitive: { topology: "triangle-list", frontFace: "ccw", cullMode: "back" },
    depthStencil: {
      format: depthFormat, depthWriteEnabled: true, depthCompare: "less",
    },
  });

  // Each row: x, y, z, r, g, b. CPU data can be regenerated on device restart.
  const vertices = new Float32Array([
    -1, -1, -1, 0.2, 0.2, 0.3, // 0
     1, -1, -1, 1.0, 0.2, 0.3, // 1
     1,  1, -1, 1.0, 1.0, 0.3, // 2
    -1,  1, -1, 0.2, 1.0, 0.3, // 3
    -1, -1,  1, 0.2, 0.2, 1.0, // 4
     1, -1,  1, 1.0, 0.2, 1.0, // 5
     1,  1,  1, 1.0, 1.0, 1.0, // 6
    -1,  1,  1, 0.2, 1.0, 1.0, // 7
  ]);
  const indices = new Uint16Array([
    4, 5, 6, 4, 6, 7, // +Z
    1, 0, 3, 1, 3, 2, // -Z
    0, 4, 7, 0, 7, 3, // -X
    5, 1, 2, 5, 2, 6, // +X
    3, 7, 6, 3, 6, 2, // +Y
    0, 1, 5, 0, 5, 4, // -Y
  ]);
  function upload(data, usage, label) {
    const buffer = device.createBuffer({
      label, size: data.byteLength, usage: usage | GPUBufferUsage.COPY_DST,
    });
    device.queue.writeBuffer(buffer, 0, data);
    return buffer;
  }
  const vertexBuffer = upload(vertices, GPUBufferUsage.VERTEX, "cube vertices");
  const indexBuffer = upload(indices, GPUBufferUsage.INDEX, "cube indices");
  const uniformBuffer = device.createBuffer({
    label: "cube MVP", size: 64,
    usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
  });
  const bindGroup = device.createBindGroup({
    label: "cube object bindings",
    layout: pipeline.getBindGroupLayout(0),
    entries: [{ binding: 0, resource: { buffer: uniformBuffer, size: 64 } }],
  });
  const view = new Float32Array([
    1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -4, 1,
  ]);
  let depthTexture = null;
  let depthView = null;
  let destroyed = false;
  return {
    resize(width, height) {
      // Host calls before first draw and only when positive backing size changes.
      depthTexture?.destroy();
      depthTexture = device.createTexture({
        label: "cube depth", size: [width, height],
        format: depthFormat, usage: GPUTextureUsage.RENDER_ATTACHMENT,
      });
      depthView = depthTexture.createView();
    },
    render(context, time, aspect) {
      const model = multiply(rotationY(time * 0.7), rotationX(0.45 + time * 0.3));
      const projection = perspective(Math.PI / 3, aspect, 0.1, 100);
      const mvp = multiply(projection, multiply(view, model));
      device.queue.writeBuffer(uniformBuffer, 0, mvp);
      const encoder = device.createCommandEncoder({ label: "cube frame" });
      const pass = encoder.beginRenderPass({
        colorAttachments: [{
          view: context.getCurrentTexture().createView(),
          clearValue: { r: 0.02, g: 0.03, b: 0.06, a: 1 },
          loadOp: "clear", storeOp: "store",
        }],
        depthStencilAttachment: {
          view: depthView,
          depthClearValue: 1, depthLoadOp: "clear", depthStoreOp: "discard",
        },
      });
      pass.setPipeline(pipeline);
      pass.setBindGroup(0, bindGroup);
      pass.setVertexBuffer(0, vertexBuffer);
      pass.setIndexBuffer(indexBuffer, "uint16");
      pass.drawIndexed(indices.length);
      pass.end();
      device.queue.submit([encoder.finish()]);
    },
    destroy() {
      if (destroyed) return;
      destroyed = true;
      depthTexture?.destroy();
      vertexBuffer.destroy();
      indexBuffer.destroy();
      uniformBuffer.destroy();
      depthTexture = null;
      depthView = null;
    },
  };
}
```

Expected: rotating colored cube with nearer surfaces hiding farther ones, preserved camera proportions after resizing, and a static view when reduced motion or pause is active. Narrow canvases can crop the cube horizontally; production viewers should fit the camera distance/FOV to model bounds and viewport aspect. The small temporary matrix allocations keep the math copyable; reuse arrays if profiling shows allocation pressure. Resize allocates only the depth texture, not a pipeline or mesh buffer.

Integration points: replace CPU geometry after validating attribute/index layout; replace the fixed view/model transforms with application camera/object state; update the HTML label/caption. When adding normals or UVs, update **both** vertex data/stride and shader locations; when adding uniforms, recalculate WGSL alignment and binding size.
