package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.SessionId
import zio.Chunk

/** Validates the whole chain before either backend starts publishing any of its messages. */
private[storage] object StoreageUtils {
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
