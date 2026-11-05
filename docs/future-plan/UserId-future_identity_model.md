# UserId — identity model

Status: implemented. See [the wire contract](../../server/PROTOCOL.md#connection-and-capabilities).

## Model

- `UserId` identifies the user and owns one JSON-RPC connection.
- A user can own multiple `AgentId`s representing different actors.
- Each request explicitly selects an acting `AgentId` owned by the connected user.
- The acting identity and recipient agent are separate:
  - Chat acts as `user-fabio`.
  - Website/harness features act as `web-user-fabio`, invoking recipients such as `Transcriber`.
- The server validates ownership of the acting `AgentId`; messages retain that identity as their author.

Both actors share the same connection. Switching actors does not require reconnecting or creating a session.

## Delivered changes

1. Introduce `UserId` and user-to-agent ownership mappings.
2. Bind JSON-RPC initialization to `UserId`, rather than a single author `AgentId`.
3. Add explicit acting-agent selection to requests, keeping recipient selection separate.
4. Validate ownership and message authorship server-side.
5. Update chat and website/harness features to select their respective actors over the shared connection.

This identity change does not introduce implicit sessions or replace recipient capability checks. Authentication remains a separate concern.

## Implementation notes

- `UserId` is a distinct model type with JSON codecs.
- The trusted-local policy provisions `user-<id>` and `web-user-<id>`; initialization returns their ownership in
  `identity.agentIds`. Mappings are process-local and deterministically reconstructed, not authenticated or durably stored.
- Authoring requests (`turn.submit`, `agents.invoke`) require `actingAgentId`;
  read-only and connection-scoped administrative/blob requests do not author messages.
- Client and server share the current contract; no application version negotiation or implicit-author fallback is provided.
- Ownership/authorship and shared-connection actor switching are covered in `ServerSubmissionSuite`; browser chat,
  voice/scalamode, protocol codecs and connected REPL tests exercise their respective actor selections.

Conversation creation is now an explicit New target of `turn.submit`, not a separate endpoint.
