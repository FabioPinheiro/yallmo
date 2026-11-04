package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.SessionId
import zio.{Chunk, IO, ZIO}

/** Validates the whole chain before either backend starts publishing any of its messages. */
private[orchestration] object StoreageUtils {
  def ancestry(inventory: Map[MessageId, Message], head: Option[MessageId]): IO[YallmoError, Chunk[Message]] = {
    def loop(next: Option[MessageId], seen: Set[MessageId], chain: List[Message]): IO[YallmoError, Chunk[Message]] =
      ZIO.suspendSucceed {
        next match {
          case None     => ZIO.succeed(Chunk.fromIterable(chain))
          case Some(id) =>
            inventory.get(id) match {
              case Some(message) if !seen(id) && message.id == MessageId.from(message.data) =>
                loop(message.parentId, seen + id, message :: chain)
              case _ => ZIO.fail(YallmoError.InvalidMessage("Missing, cyclic or invalid message ancestry"))
            }
        }
      }
    loop(head, Set.empty, Nil)
  }

  def validateMessageBatch(
      id: SessionId,
      expectedHead: Option[MessageId],
      actualHead: Option[MessageId],
      messages: Chunk[Message]
  ): Either[YallmoError, Unit] =
    if actualHead != expectedHead then Left(YallmoError.SessionHeadConflict(id, expectedHead, actualHead))
    else if messages.isEmpty then Left(YallmoError.EmptyAppend(id))
    else
      messages
        .foldLeft[Either[YallmoError, Option[MessageId]]](Right(expectedHead)) { (previous, message) =>
          previous.flatMap { parent =>
            if message.id != MessageId.from(message.data) then
              Left(YallmoError.InvalidMessage("Message id does not match data"))
            else if message.parentId != parent then
              Left(YallmoError.InvalidMessage("Message batch does not extend the session head as a contiguous chain"))
            else Right(Some(message.id))
          }
        }
        .map(_ => ())
}
