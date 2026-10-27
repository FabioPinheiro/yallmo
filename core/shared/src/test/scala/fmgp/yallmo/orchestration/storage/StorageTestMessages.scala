package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import zio.*

/** Builds test fixtures outside Storage, retrying only its pre-write compare-and-set rejection. */
private[orchestration] object StorageTestMessages {
  def persistTurn(
      storage: Storage,
      id: SessionId,
      author: AgentId,
      payloads: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    (for {
      session <- storage.loadSession(id)
      head = session.messages.lastOption.map(_.id)
      messages <- ZIO.foldLeft(payloads)(Chunk.empty[Message]) { (batch, payload) =>
        Clock.instant.map(timestamp =>
          batch :+ Message(MessageData(author, batch.lastOption.map(_.id).orElse(head), timestamp, payload))
        )
      }
      saved <- storage.putMessagesAndUpdateSession(id, head, messages)
    } yield saved).catchSome { case _: YallmoError.SessionHeadConflict =>
      ZIO.yieldNow *> persistTurn(storage, id, author, payloads)
    }
}
