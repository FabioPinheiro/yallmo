# Colored triangle

[Skill index](../SKILL.md) · [Required browser host](browser-host.md) · [Next: indexed cube](indexed-cube.md)

Save this entire block as `scene.js` beside the host's `index.html` and `main.js`. It generates positions/colors with `vertex_index`, so it tests shaders, interpolation, pipeline, pass, and submission without introducing buffer layout problems. It is intentionally static even when motion is enabled.

## `scene.js`

```js
export async function createScene({ device, format, checkedShader }) {
  const module = await checkedShader(device, `
    struct VertexOutput {
      @builtin(position) position: vec4<f32>,
      @location(0) color: vec3<f32>,
    };
    @vertex fn vs(@builtin(vertex_index) i: u32) -> VertexOutput {
      var positions = array<vec2<f32>, 3>(
        vec2<f32>(0.0, 0.7),
        vec2<f32>(-0.7, -0.7),
        vec2<f32>(0.7, -0.7)
      );
      var colors = array<vec3<f32>, 3>(
        vec3<f32>(1.0, 0.2, 0.2),
        vec3<f32>(0.2, 1.0, 0.2),
        vec3<f32>(0.2, 0.4, 1.0)
      );
      var out: VertexOutput;
      out.position = vec4<f32>(positions[i], 0.0, 1.0);
      out.color = colors[i];
      return out;
    }
    @fragment fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
      return vec4<f32>(in.color, 1.0);
    }
  `, "triangle shader");
  const pipeline = await device.createRenderPipelineAsync({
    label: "colored triangle pipeline",
    layout: "auto",
    vertex: { module, entryPoint: "vs" },
    fragment: { module, entryPoint: "fs", targets: [{ format }] },
    primitive: { topology: "triangle-list" },
  });
  return {
    resize() {}, // No size-dependent attachments or camera in this scene.
    render(context) {
      const encoder = device.createCommandEncoder({ label: "triangle frame" });
      const pass = encoder.beginRenderPass({
        colorAttachments: [{
          view: context.getCurrentTexture().createView(),
          clearValue: { r: 0.02, g: 0.03, b: 0.06, a: 1 },
          loadOp: "clear",
          storeOp: "store",
        }],
      });
      pass.setPipeline(pipeline);
      pass.draw(3);
      pass.end();
      device.queue.submit([encoder.finish()]);
    },
    destroy() {}, // Pipeline/modules have no destroy method; release references.
  };
}
```

Expected: a large triangle with red top, green bottom-left, blue bottom-right, and interpolated interior on a dark background. Clip-space geometry stretches with the canvas aspect; use an aspect-aware transform if a fixed geometric shape is required. If only the clear color appears, inspect compilation/validation messages, entry points, and draw count before adding any mesh or camera complexity.
