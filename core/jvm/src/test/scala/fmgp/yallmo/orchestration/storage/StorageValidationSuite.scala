package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*

final class StorageValidationSuite extends FunSuite {
  test("hash failures identify the physical record and stored/computed IDs without disclosing message content") {
    directory { root =>
      val first = CanonicalArtifactHash.store(message("first"))
      val original = CanonicalArtifactHash.store(message("original", Some(first.id)))
      val changed = original.copy(value = original.value.copy(payload = MessagePayload.User("PRIVATE_SECRET")))
      val later = CanonicalArtifactHash.store(message("later"))
      val alsoChanged = later.copy(value = later.value.copy(payload = MessagePayload.User("another change")))
      val contents = s"\n${envelope(first)}\n\n${envelope(changed)}\n${envelope(alsoChanged)}\n"
      Files.writeString(root.resolve("objects.jsonl"), contents)

      val error = run(StorageFS.open(root).either).swap.toOption.get
      assert(error.isInstanceOf[YallmoError.StorageFailure])
      assert(error.getMessage.contains(s"objects.jsonl record 4, message ${MessageId.value(original.id)}"))
      assert(error.getMessage.contains(s"stored ID ${MessageId.value(original.id)}"))
      assert(error.getMessage.contains(s"computed ID ${MessageId.value(MessageId.from(changed.value))}"))
      assert(error.getMessage.contains("hash mismatch"))
      assert(!error.getMessage.contains(MessageId.value(later.id)), "loading still stops at the first invalid record")
      assert(!error.toString.contains("PRIVATE_SECRET"))
      assert(!error.getCause.toString.contains("PRIVATE_SECRET"))
      assertEquals(Files.readString(root.resolve("objects.jsonl")), contents)
    }
  }

  test("missing parents identify the child record, child ID and missing parent ID") {
    directory { root =>
      val missing = MessageId.sha256("missing-parent")
      val child = CanonicalArtifactHash.store(message("PRIVATE_SECRET", Some(missing)))
      val contents = envelope(child) + "\n"
      Files.writeString(root.resolve("objects.jsonl"), contents)

      val error = run(StorageFS.open(root).either).swap.toOption.get
      assert(error.getMessage.contains(s"objects.jsonl record 1, message ${MessageId.value(child.id)}"))
      assert(error.getMessage.contains(s"message parent $missing is missing"))
      assert(!error.toString.contains("PRIVATE_SECRET"))
      assertEquals(Files.readString(root.resolve("objects.jsonl")), contents)
    }
  }

  test("malformed records report the physical line without exposing decoder input") {
    directory { root =>
      val contents = "\nPRIVATE_SECRET invalid JSON\n"
      Files.writeString(root.resolve("objects.jsonl"), contents)
      val error = run(StorageFS.open(root).either).swap.toOption.get
      assertEquals(
        error.getMessage,
        "objects.jsonl record 2: invalid envelope JSON; expected id, kind and value fields"
      )
      assert(!error.getCause.toString.contains("PRIVATE_SECRET"))
      assertEquals(Files.readString(root.resolve("objects.jsonl")), contents)
    }
  }

  test("schema failures retain a valid message ID but do not echo unsupported kinds or payload values") {
    val stored = CanonicalArtifactHash.store(message("PRIVATE_SECRET"))
    val valid = envelope(stored)
    val badKind = valid.replace("\"kind\":\"msg\"", "\"kind\":\"PRIVATE_SECRET\"")
    val badTimestamp = valid.replace("2026-01-01T00:00:00Z", "PRIVATE_SECRET")
    val kindError = StorageJson.decodeEnvelope(badKind).swap.toOption.get
    val dataError = StorageJson.decodeEnvelope(badTimestamp).swap.toOption.get
    assertEquals(kindError, "unsupported artifact kind; expected 'msg'")
    assert(dataError.contains(s"message ${MessageId.value(stored.id)}: invalid message data"))
    assert(!dataError.contains("PRIVATE_SECRET"))
  }

  test("legacy tool results fail with explicit compatibility guidance without modifying storage or exposing content") {
    directory { root =>
      val legacyData =
        """{"agentId":"author","timestamp":"2026-01-01T00:00:00Z","payload":{"ToolResult":{"functionKey":"clock","text":"PRIVATE_SECRET"}}}"""
      val legacyId = MessageId.sha256(legacyData)
      val contents = s"""{"id":"${MessageId.value(legacyId)}","kind":"msg","value":$legacyData}""" + "\n"
      Files.writeString(root.resolve("objects.jsonl"), contents)

      val error = run(StorageFS.open(root).either).swap.toOption.get
      assert(error.getMessage.contains("objects.jsonl record 1"))
      assert(error.getMessage.contains("legacy ToolResult payload is unsupported"))
      assert(error.getMessage.contains("No automatic migration"))
      assert(!error.toString.contains("PRIVATE_SECRET"))
      assert(!error.getCause.toString.contains("PRIVATE_SECRET"))
      assertEquals(Files.readString(root.resolve("objects.jsonl")), contents)
    }
  }

  private def message(text: String, parent: Option[MessageId] = None): MessageData =
    MessageData(AgentId("author"), parent, Instant.parse("2026-01-01T00:00:00Z"), MessagePayload.User(text))

  private def envelope(stored: StoredArtifact[MessageData]): String =
    StorageJson.envelopeJson(DecodedArtifact.Msg(stored)).toOption.get

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def directory(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-storage-validation-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
