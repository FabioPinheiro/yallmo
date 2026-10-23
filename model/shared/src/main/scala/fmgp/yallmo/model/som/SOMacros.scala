package fmgp.yallmo.model.som

import scala.language.dynamics
import scala.quoted.*
import zio.json.{JsonDecoder, JsonEncoder, jsonField}

/** System One primitive shared by a question field and its corresponding answer field. */
enum SOPrimitive {
  case Choice, Noul, Score
}

/** One field in a typed System One schema. `id` is the request-question and response-answer map key. */
final case class SOField(id: String, primitive: SOPrimitive)

/** Reflection helpers shared by the two macro implementations in this file. */
private object SOMacroSupport {

  /** Returns the JSON map key for a case field, honoring zio-json's `@jsonField` override when present. */
  def wireFieldId(using quotes: Quotes)(field: quotes.reflect.Symbol): String = {
    import quotes.reflect.*

    // Scala records a parameter annotation on the primary-constructor parameter on some declarations and on the
    // accessor field on others, so inspect both symbols.
    val constructorParameterAnnotations = field.owner.primaryConstructor.paramSymss.flatten
      .filter(_.name == field.name)
      .flatMap(_.annotations)
    (field.annotations ++ constructorParameterAnnotations)
      .collectFirst {
        case annotation @ Apply(_, List(Literal(StringConstant(value))))
            if annotation.tpe.typeSymbol == TypeRepr.of[jsonField].typeSymbol =>
          value
      }
      .getOrElse(field.name)
  }
}

/** Validates a System One response against a case-class question schema and constructs its typed answer view.
  *
  * This is a type class rather than an answer value: an [[SOAnswer]] needs a real response at runtime, whereas this
  * schema is derived once from `Q` at compile time. A question case class opts in with:
  *
  * {{ final case class RoutingQuestions( worker: SystemOneQuestion.Choice, needsReview: SystemOneQuestion.Noul )
  * derives SOSchema }}
  */
trait SOSchema[Q] {

  /** Validates that `response.answers` has every field and answer subtype required by `Q`. */
  def decodeAnswer(response: SystemOneResponse): Either[String, SOAnswer[Q]]

  /** Decodes and validates a complete System One response JSON envelope. */
  def decodeAnswer(json: String): Either[String, SOAnswer[Q]] =
    JsonDecoder[SystemOneResponse].decodeJson(json).flatMap(decodeAnswer)

  /** Encodes the complete, validated System One response envelope retained by `answer`. */
  def encodeAnswer(answer: SOAnswer[Q]): String =
    JsonEncoder[SystemOneResponse].encodeJson(answer.response).toString

  /** Decodes a typed System One request whose question schema is `Q`. */
  def decodeRequest[S](json: String)(using JsonDecoder[S], JsonDecoder[Q]): Either[String, SORequest[S, Q]] =
    JsonDecoder[SORequest[S, Q]].decodeJson(json)

  /** Encodes a typed System One request using the System One wire format. */
  def encodeRequest[S](request: SORequest[S, Q])(using JsonEncoder[S], JsonEncoder[Q]): String =
    JsonEncoder[SORequest[S, Q]].encodeJson(request).toString

  /** Field IDs and shared System One primitives in case-class declaration order. */
  def fields: Seq[SOField]
}

object SOSchema {

  /** Entry point used by Scala's `derives SOSchema` syntax.
    *
    * `inline` means this call is expanded at the call site. `${ ... }` is a macro *splice*: while compiling the caller,
    * Scala runs [[derivedImpl]] and replaces the splice with the generated `SOSchema[Q]` code.
    */
  inline def derived[Q]: SOSchema[Q] =
    ${ derivedImpl[Q] }

  /** Compile-time implementation of [[derived]].
    *
    * `Type[Q]` is evidence that allows the macro to inspect the caller's concrete type argument. `Quotes` enables
    * Scala's quoted-code and reflection APIs. The result is an `Expr[SOSchema[Q]]`: syntax/code to insert into the
    * caller's program, not an `SOSchema` instance created while compiling.
    *
    * Macro implementations must be callable from the separately compiled call site, so this method is public and is
    * defined in an object (a static location from the compiler's perspective).
    */
  def derivedImpl[Q: Type](using Quotes): Expr[SOSchema[Q]] = {
    import quotes.reflect.*

    // `TypeRepr` is Scala reflection's representation of the concrete Q type at the derivation call site.
    val questionType = TypeRepr.of[Q]
    val questionSymbol = questionType.typeSymbol

    // `caseFields` alone is not an adequate contract: a non-case class can expose fields too. Require the schema to
    // have the predictable, closed constructor/field shape of a case class.
    if !questionSymbol.flags.is(Flags.Case) then report.errorAndAbort(s"${questionType.show} must be a case class")

    // This entire block runs at compile time. It turns the Scala field names/types into a small description used to
    // generate runtime response checks below. A field name is also the current System One question ID on the wire.
    val checks = questionSymbol.caseFields.map { field =>
      // `memberType` resolves the field type in Q's context; `dealias` follows any user-defined type aliases before
      // comparing it to the three supported concrete question types.
      val fieldType = questionType.memberType(field).dealias
      val primitive =
        if fieldType <:< TypeRepr.of[SystemOneQuestion.Choice] then SOPrimitive.Choice
        else if fieldType <:< TypeRepr.of[SystemOneQuestion.Score] then SOPrimitive.Score
        else if fieldType <:< TypeRepr.of[SystemOneQuestion.Noul] then SOPrimitive.Noul
        else
          // This stops compilation of `case class Questions(..., accidental: Int)` rather than deferring the mistake
          // until a request is sent.
          report.errorAndAbort(
            s"${field.name} in ${questionType.show} has type ${fieldType.show}; expected Choice, Score, or Noul"
          )
      (SOMacroSupport.wireFieldId(field), primitive)
    }

    /** Generates one runtime check for one Q field.
      *
      * `Expr[A]` means “code which evaluates to `A`”. The `'{ ... }` quote below does not execute its body now; it
      * builds the body to run later, when the application's `decode` method is called. `$response` and `$id` splice
      * already-built expressions into that generated code.
      */
    def validateField(
        response: Expr[SystemOneResponse],
        questionId: String,
        primitive: SOPrimitive
    ): Expr[Option[String]] = {
      // Lift the compile-time String into generated code as a String literal expression.
      val id = Expr(questionId)
      primitive match {
        case SOPrimitive.Choice =>
          '{
            // Everything in this quote runs at application runtime, not during macro expansion.
            $response.answers.get($id) match {
              case Some(_: SystemOneAnswer.Choice) => None
              case Some(answer)                    =>
                Some("answer '" + $id + "' has type " + answer.getClass.getSimpleName + "; expected choice")
              case None => Some("missing answer '" + $id + "'")
            }
          }
        case SOPrimitive.Score =>
          '{
            $response.answers.get($id) match {
              case Some(_: SystemOneAnswer.Score) => None
              case Some(answer)                   =>
                Some("answer '" + $id + "' has type " + answer.getClass.getSimpleName + "; expected score")
              case None => Some("missing answer '" + $id + "'")
            }
          }
        case SOPrimitive.Noul =>
          '{
            $response.answers.get($id) match {
              case Some(_: SystemOneAnswer.Noul) => None
              case Some(answer)                  =>
                Some("answer '" + $id + "' has type " + answer.getClass.getSimpleName + "; expected noul")
              case None => Some("missing answer '" + $id + "'")
            }
          }
      }
    }

    /** Generates the implementations of `SOSchema.decodeAnswer` and `SOSchema.fields`.
      *
      * `Expr.ofList` combines the individually quoted checks into quoted code that constructs a `List`. The final quote
      * inserts that List expression, returns the first validation error, or constructs the typed answer view.
      */
    def decodeBody(response: Expr[SystemOneResponse]): Expr[Either[String, SOAnswer[Q]]] = {
      val errors = Expr.ofList(checks.map { case (questionId, primitive) =>
        validateField(response, questionId, primitive)
      })
      '{
        $errors.collectFirst { case Some(error) => error } match {
          case Some(error) => Left(error)
          case None        => Right(SOAnswer.unsafeFrom[Q]($response))
        }
      }
    }

    /** Emits a `Seq[SOField]` containing the compile-time field metadata. */
    def fieldsBody: Expr[Seq[SOField]] = {
      def primitiveExpression(primitive: SOPrimitive): Expr[SOPrimitive] = primitive match {
        case SOPrimitive.Choice => '{ SOPrimitive.Choice }
        case SOPrimitive.Noul   => '{ SOPrimitive.Noul }
        case SOPrimitive.Score  => '{ SOPrimitive.Score }
      }

      val fields = Expr.ofList(checks.map { case (questionId, primitive) =>
        '{ SOField(${ Expr(questionId) }, ${ primitiveExpression(primitive) }) }
      })
      '{ $fields.toSeq }
    }

    // Generate an ordinary anonymous type-class implementation in the caller's compiled code. `'response` captures
    // the runtime method parameter as an expression, and the nested `${ ... }` splices in its generated method body.
    '{
      new SOSchema[Q] {
        def decodeAnswer(response: SystemOneResponse): Either[String, SOAnswer[Q]] =
          ${ decodeBody('response) }

        def fields: Seq[SOField] = ${ fieldsBody }
      }
    }
  }
}

/** Typed System One request whose question object is a caller-defined case class.
  *
  * Its JSON representation remains the System One `questions` object: the fields of `Q` become the question IDs.
  */
final case class SORequest[S, Q](
    state: S,
    model: String,
    questions: Q
) derives JsonEncoder,
      JsonDecoder {

  /** Validates a decoded wire response and gives it the answer-field types derived from this request's question schema.
    *
    * The `using` parameter is normally supplied automatically by `Q derives SOSchema`.
    */
  def answers(response: SystemOneResponse)(using schema: SOSchema[Q]): Either[String, SOAnswer[Q]] =
    schema.decodeAnswer(response)

  /** Decodes and validates a JSON wire response using this request's question schema. */
  def answersFromJson(jsonResponse: String)(using schema: SOSchema[Q]): Either[String, SOAnswer[Q]] =
    schema.decodeAnswer(jsonResponse)
}

/** Typed view over answers to a case-class question schema.
  *
  * `SOAnswer[Q]` deliberately retains the provider's complete raw response. Its answer fields are not known when this
  * generic class is compiled. Instead, `scala.Dynamic` forwards a selection such as `answers.worker` to
  * [[selectDynamic]], whose transparent inline macro sees the concrete `Q` at the *use site*.
  *
  * For example, if `Q.worker` is `SystemOneQuestion.Choice`, `answers.worker` expands approximately to:
  *
  * {{ answers.unsafeAnswer("worker").asInstanceOf[SystemOneAnswer.Choice] }}
  *
  * Callers should obtain this value through [[SORequest.answers]], which first validates the runtime response before
  * these generated casts can run.
  */
final case class SOAnswer[Q] private[som] (private[som] val response: SystemOneResponse) extends Dynamic {

  /** Runtime lookup used by code generated at external macro call sites.
    *
    * This must be public: a macro expands into the caller's package, where a package-private method would not be
    * accessible. It is named `unsafe` because it does no subtype validation; [[SOSchema.decodeAnswer]] provides that
    * validation before normal use.
    */
  def unsafeAnswer(questionId: String): SystemOneAnswer = response.answers(questionId)

  /** Handles dynamic member selections such as `answers.worker`.
    *
    * `inline questionId` preserves the literal field name for the macro. `transparent inline` lets the compiler use the
    * concrete type of the generated expression (for example `SystemOneAnswer.Choice`) instead of widening it to
    * `SystemOneAnswer`. `${ ... }` runs [[SOAnswer.selectDynamicImpl]] during compilation.
    */
  transparent inline def selectDynamic(inline questionId: String) =
    ${ SOAnswer.selectDynamicImpl[Q]('this, 'questionId) }
}

object SOAnswer {

  /** Wraps a response without validation.
    *
    * Generated schema code can live in another package, so this factory must be public. Application code should use
    * [[SOSchema.decodeAnswer]] or [[SORequest.answers]] instead.
    */
  def unsafeFrom[Q](response: SystemOneResponse): SOAnswer[Q] =
    new SOAnswer(response)

  /** Compile-time implementation of [[SOAnswer.selectDynamic]].
    *
    * The return type is `Expr[Any]` because the macro can emit one of several concrete answer types. Because the caller
    * is `transparent inline`, the type of the emitted expression is retained at the call site.
    */
  def selectDynamicImpl[Q: Type](
      self: Expr[SOAnswer[Q]],
      questionId: Expr[String]
  )(using Quotes): Expr[Any] = {
    import quotes.reflect.*

    // Dynamic selection is only type-safe when the name is a compile-time literal. `valueOrAbort` enforces that
    // requirement, so `answers.worker` works while an arbitrary runtime String cannot pretend to be a typed field.
    val id = questionId.valueOrAbort
    val questionType = TypeRepr.of[Q]
    val field = questionType.typeSymbol.caseFields.find(_.name == id).getOrElse {
      report.errorAndAbort(s"$id is not a field of ${questionType.show}")
    }
    val fieldType = questionType.memberType(field).dealias
    val wireFieldId = SOMacroSupport.wireFieldId(field)

    // Each branch returns quoted runtime code with a different static result type. The schema derivation guarantees
    // the cast matches a validated response; this macro additionally prevents selecting an unknown/unsupported field.
    if fieldType <:< TypeRepr.of[SystemOneQuestion.Choice] then
      '{ $self.unsafeAnswer(${ Expr(wireFieldId) }).asInstanceOf[SystemOneAnswer.Choice] }
    else if fieldType <:< TypeRepr.of[SystemOneQuestion.Score] then
      '{ $self.unsafeAnswer(${ Expr(wireFieldId) }).asInstanceOf[SystemOneAnswer.Score] }
    else if fieldType <:< TypeRepr.of[SystemOneQuestion.Noul] then
      '{ $self.unsafeAnswer(${ Expr(wireFieldId) }).asInstanceOf[SystemOneAnswer.Noul] }
    else
      report.errorAndAbort(
        s"$id in ${questionType.show} has unsupported question type ${fieldType.show}; expected Choice, Score, or Noul"
      )
  }
}
