# Models, cameras, and rendering

[Skill index](../SKILL.md) · [Indexed cube recipe](../recipes/indexed-cube.md)

## Coordinates and matrices

WebGPU clips x/y to `[-w, w]` and z to `[0, w]`; after perspective division NDC x/y range from -1 to 1 and depth from 0 to 1. Positive NDC y is up, while canvas pixel coordinates start at the top-left. The API does not impose a world-space handedness; the cube chooses a right-handed world and a camera looking down negative Z. Do not copy an OpenGL -1-to-1 depth projection unchanged.

The cube uses column-major matrices, column vectors, and `projection * view * model * position`. It rotates local vertices, translates the world by -4 on Z (camera at z=4 looking at the origin), and projects with a positive near plane and finite far plane. Keep the object in front of the camera and outside the near plane. Matrices uploaded with `Float32Array` must use the same ordering as the shader; a transpose mismatch can produce plausible but wrong movement.

For an orbit camera, derive eye from yaw/pitch/distance, build a right-handed look-at view matrix, clamp pitch away from the up-vector singularity, and clamp distance above zero. Provide keyboard equivalents and reset. Imported model transforms need a documented axis/unit convention. A general view matrix is the inverse of the camera's world transform.

For pixel-oriented 2D geometry, an orthographic mapping can use `xClip = 2*x/width - 1`, `yClip = 1 - 2*y/height`, and z in `[0,1]`; decide whether width/height are CSS or backing pixels. The triangle directly uses clip-space coordinates.

## Indexed geometry and depth

Vertex buffers contain attributes; index buffers select vertices. Match `setIndexBuffer(..., "uint16" | "uint32")` to the typed array, validate indices are in range, and pass the actual index count to `drawIndexed`. A vertex is the full tuple of position, normal, UV, color, etc. Shared positions at hard edges/UV seams require duplicated vertices. The cube uses eight shared positions/colors intentionally; a face-normal/UV cube typically uses 24 vertices and 36 indices.

Depth testing requires all three:

1. Pipeline `depthStencil` with format, write enable, and compare function.
2. A same-size, matching-sample-count depth texture with `RENDER_ATTACHMENT` usage.
3. Render-pass depth attachment, clear value, and load/store operations.

The cube uses `depth24plus`, clear 1, compare `"less"`, depth writes, and discard after the pass. Use `"store"` if a later pass needs depth. Recreate depth on resize and destroy the retired texture. For MSAA, use matching pipeline/color/depth sample counts and resolve a multisampled color attachment into the single-sample canvas; the recipes use sample count 1.

Validate winding before enabling back-face culling (`frontFace: "ccw"` by default). Negative scales can flip winding. Opaque models can write depth normally; transparent geometry needs an explicit blending/sorting/depth-write strategy rather than just lowering fragment alpha. Lighting requires normals; nonuniform scale generally requires the inverse-transpose normal transform. Recipe colors are unlit and interpolated, not a material/lighting system.

## Frame and asset responsibilities

Per frame: size attachments → update camera/model uniforms → acquire canvas view → create encoder/pass → bind pipeline/resources → draw → end → finish → queue submit. Reuse pipelines, mesh buffers, and bind groups. Do not wait for `onSubmittedWorkDone()` each frame or treat CPU submit time as GPU duration.

WebGPU does not parse OBJ or glTF. An application-specific loader must produce validated geometry, transforms, and material data. Honor accessor offsets/strides/component types, normalize or convert formats, bound asset sizes, and handle CORS/fetch failures. Preserve CPU data or reloadable descriptors for device-loss rebuilds. Start with an untextured known mesh, then add normals/materials/textures; choose a loader based on the app rather than hiding one in a minimal native recipe.

Reference: [MDN GPURenderPassEncoder](https://developer.mozilla.org/en-US/docs/Web/API/GPURenderPassEncoder), [GPUDevice.createTexture](https://developer.mozilla.org/en-US/docs/Web/API/GPUDevice/createTexture).
