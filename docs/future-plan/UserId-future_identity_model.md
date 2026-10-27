# UserId — future identity model

Status: future plan; not implemented.

## Model

- `UserId` identifies the user and owns one JSON-RPC connection.
- A user can own multiple `AgentId`s representing different actors.
- Each request explicitly selects an acting `AgentId` owned by the connected user.
- The acting identity and recipient agent are separate:
  - Chat acts as `user-fabio`.
  - Website/harness features act as `web-user-fabio`, invoking recipients such as `Transcriber`.
- The server validates ownership of the acting `AgentId`; messages retain that identity as their author.

Both actors share the same connection. Switching actors does not require reconnecting or creating a session.

## High-level plan

1. Introduce `UserId` and user-to-agent ownership mappings.
2. Bind JSON-RPC initialization to `UserId`, rather than a single author `AgentId`.
3. Add explicit acting-agent selection to requests, keeping recipient selection separate.
4. Validate ownership and message authorship server-side.
5. Update chat and website/harness features to select their respective actors over the shared connection.

This identity change does not introduce implicit sessions or replace recipient capability checks. Authentication remains a separate concern.
