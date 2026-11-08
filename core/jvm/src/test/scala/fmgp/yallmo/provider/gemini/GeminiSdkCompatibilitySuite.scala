package fmgp.yallmo.provider.gemini

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.genai.types.GenerateContentResponse
import fmgp.yallmo.model.llm.ProviderGenerateOptions
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Offline SDK/Jackson compatibility checks for the security dependency update; no provider credentials or network. */
final class GeminiSdkCompatibilitySuite extends FunSuite {
  test("the SDK decodes model text with its aligned Jackson modules") {
    val response = GenerateContentResponse.fromJson(
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]}}]}"""
    )
    assertEquals(GenAiMappers.textFromResponse(response), Right("Hello"))
  }

  test("SDK function arguments retain nested values through the tool JSON serialization path") {
    val response = GenerateContentResponse.fromJson(
      """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"lookup","args":{"city":"Porto","count":2,"tags":["a","b"]}}}]}}]}"""
    )
    val calls = response.functionCalls().asScala.toVector
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name().orElse(""), "lookup")
    val arguments = ujson.read(ObjectMapper().writeValueAsString(calls.head.args().orElseThrow()))
    assertEquals(arguments("city").str, "Porto")
    assertEquals(arguments("count").num, 2.0)
    assertEquals(arguments("tags").arr.map(_.str).toVector, Vector("a", "b"))
  }

  test("generation options still serialize through the SDK's optional and enum adapters") {
    val options = ProviderGenerateOptions(systemInstruction = Some("Be concise."), temperature = Some(0.25))
    val config = GenAiMappers.toGenerateContentConfig(options).toOption.get
    val json = ujson.read(config.toJson())
    assertEquals(json("temperature").num, 0.25)
    assertEquals(json("systemInstruction")("parts")(0)("text").str, "Be concise.")
  }
}
