package grants.playimpl

import play.api.libs.json._
import oauth2.spec.model._
import oauth2.spec.StatusCodes
import oauthze.model._
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import oauth2.spec.AccessTokenErrors
import oauth2.spec.AuthzErrors
import grants.Dispatcher

object json {
  implicit val AuthzCodeResponseFormat = Json.format[AuthzCodeResponse]
  implicit val ImplicitResponseFormat = Json.format[ImplicitResponse]
  implicit val AccessTokenResponseFormat = Json.format[AccessTokenResponse]
  implicit object ErrorsFormat extends Format[Err] {
    def reads(json: JsValue): JsResult[Err] = Json.reads[Err].reads(json)
    def writes(err: Err): JsValue = Json.writes[Err].writes(err) - "status_code" - "redirect_uri"
  }
  implicit def errToJsValue(err: Err) = Json.toJson(err)
}

trait RenderingUtils extends Controller {
  
  this: OauthConfig =>

  import json._

  implicit def renderAccessTokenResponse(r: AccessTokenResponse): SimpleResult = Ok(Json.toJson(r))
  implicit def renderErrorAsResult(err: Err): SimpleResult = {
    err.status_code match {
      case StatusCodes.Redirect => {
        err.redirect_uri.map { url =>
          val c = Json.toJson(err)
          val params = c.as[Map[String, JsValue]].map(pair => (pair._1, Seq(pair._2.as[String])))
          Redirect(url, params, err.status_code)
        } getOrElse (BadRequest(Json.toJson(err)))
      }
      case StatusCodes.BadRequest => BadRequest(Json.toJson(err))
      case StatusCodes.Unauthorized => Unauthorized(Json.toJson(err))
      case StatusCodes.InternalServerError => InternalServerError(Json.toJson(err))
      case _ => throw new UnsupportedOperationException("Only error status codes should be rendered by the error handler")
    }
  }

  implicit def transformReponse(response: OauthResponse) = response match {
    case r: OauthRedirect => Redirect(r.uri, r.params.map(tuple => (tuple._1 -> Seq(tuple._2))), 302)
    //case a: InitiateApproval => Ok(views.html.user_approval(a.authzCode, a.authzRequest, a.client))
    case a: InitiateApproval => Redirect(processApprovalEndpoint, Map("code" -> Seq(a.authzCode)))
  }

  implicit def PlayRequestToOauth2Request(request: RequestHeader) = {
    BodyParsers.parse.urlFormEncoded(request).run.map { parsed =>
      parsed match {
        case Left(err) => request.queryString
        case Right(good) => good ++ request.queryString
      }
    }.map { par =>
      new OauthRequest() {
        override def params = par.map(v => (v._1 -> v._2.mkString))
        override def method = request.method
        override def path = request.path
        override def param(key: String) = params.get(key)
      }
    } recover {
      case e: Exception => e.printStackTrace; throw e
    }
  }
}

trait BodyReaderFilter extends EssentialFilter {

  this: Dispatcher =>

  import json._
  import play.api.libs.iteratee.{ Enumerator, Done, Iteratee, Traversable }
  import play.api.libs.concurrent.Execution.Implicits.defaultContext
  import play.api.mvc.BodyParsers.parse._
  import play.api.mvc.Results.InternalServerError

  override def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {
      checkFormBody(requestHeader, nextFilter)
      //Iteratee.flatten(scala.concurrent.Future(checkFormBody(requestHeader, nextFilter)))
    }
  }

  def bodyProcessor(a: OauthRequest, req: RequestHeader): Option[SimpleResult] = {
    Some(InternalServerError(Json.toJson(oauthze.utils.err(AuthzErrors.server_error, "Not implemented"))))
  }

  private def checkFormBody = checkBody1[Map[String, Seq[String]]](tolerantFormUrlEncoded, identity, bodyProcessor) _
  private def checkBody1[T](parser: BodyParser[T], extractor: (T => Map[String, Seq[String]]), processor: (OauthRequest, RequestHeader) => Option[SimpleResult])(request: RequestHeader, nextAction: EssentialAction) = {
    val firstPartOfBody: Iteratee[Array[Byte], Array[Byte]] =
      Traversable.take[Array[Byte]](50000) &>> Iteratee.consume[Array[Byte]]()

    firstPartOfBody.flatMap { bytes: Array[Byte] =>
      val parsedBody = Enumerator(bytes) |>>> parser(request)
      Iteratee.flatten(parsedBody.map { parseResult =>
        val bodyAsMap = parseResult.fold(
          err => { println(err); Map[String, Seq[String]]() },
          body => ({
            for {
              values <- extractor(body)
            } yield values
          }))
        process(bodyAsMap ++ request.queryString, request, nextAction, bytes)
      })
    }
  }

  private def process(bodyAndQueryStringAsMap: Map[String, Seq[String]], request: RequestHeader, nextAction: EssentialAction, bytes: Array[Byte]): Iteratee[Array[Byte], SimpleResult] = {
    val r = new OauthRequest() {
      override val path = request.path
      override val method = request.method
      override val params = bodyAndQueryStringAsMap.map(x => (x._1 -> x._2.mkString))
      override def param(key: String) = params.get(key)
    }
    if (matches(r)) {
      println(" -- found matching request, will process with the body processor " + r + ": " + this)
      bodyProcessor(r, request).fold(next(nextAction, bytes, request))(f => Done(f))
    } else {
      println(" -- didn't find matching request, will just forward " + r + ": " + this)
      next(nextAction, bytes, request)
    }
  }

  private def next(nextAction: EssentialAction, bytes: Array[Byte], request: RequestHeader) = Iteratee.flatten(Enumerator(bytes) |>> nextAction(request))
}
