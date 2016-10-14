package utils

import controllers.api.ApiException
import play.api.libs.json.{JsReadable, Reads}
import play.api.mvc.Results
import scala.concurrent.Future
import scala.language.implicitConversions

object Implicits {
	/**
	  * Implicitly wraps a value in a Future.
	  *
	  * @param value the value to be wrapped in the future
	  * @tparam T the type of the value
	  * @return a successfully resolved future containing the value
	  */
	implicit def futureWrapper[T](value: T): Future[T] = Future.successful(value)

	/**
	  * Implicitly wraps a value in an Option.
	  *
	  * @param value the value to be wrapped in the option
	  * @tparam T the type of the value
	  * @return a filled option containing the value
	  */
	implicit def optionWrapper[T](value: T): Option[T] = Option(value)

	implicit class safeJsReadTyping(private val js: JsReadable) extends AnyVal {
		def to[T: Reads]: T = to('UNPROCESSABLE_ENTITY)
		def to[T: Reads](sym: Symbol, status: Results#Status = Results.UnprocessableEntity): T = {
			to[T](ApiException(sym, status))
		}
		def to[T: Reads](e: ApiException): T = js.asOpt[T].getOrElse(throw e)
	}
}
