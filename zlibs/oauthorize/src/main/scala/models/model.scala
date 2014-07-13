package oauthorize.model

import oauthorize.utils._
import oauth2.spec.Req._
import oauth2.spec.ResponseType
import oauth2.spec.GrantTypes._
import oauth2.spec.StatusCodes._
import oauth2.spec.StatusCodes
import oauth2.spec.model.ErrorResponse

case class AuthzRequest(clientId: String, responseType: ResponseType, redirectUri: String, authScope: Seq[String], approved: Boolean, state: Option[State] = None, user: Option[Oauth2User] = None) extends AuthzRequestValidation
case class AccessTokenRequest(grantType: GrantType, authzCode: String, redirectUri: String, clientId: Option[String]) extends AccessTokenRequestValidation
case class ClientCredentialsRequest(client: Oauth2Client, scope: Option[String]) extends ClientCredentialsRequestValidation

case class AccessToken(value: String, clientId: String, scope: Seq[String], validity: Long, created: Long, userId: Option[String])
case class RefreshToken(value: String, clientId: String, validity: Long, created: Long, userId: Option[String])

trait OauthRequest {
  def path: String
  def param(key: String): Option[String]
  def method: String
  def params: Map[String, String]
  override def toString = s"$method '$path' $params"
}

trait OauthResponse
case class OauthRedirect(uri: String, params: Map[String, String]) extends OauthResponse
case class InitiateApproval(authzCode: String, authzRequest: AuthzRequest, client: Oauth2Client) extends OauthResponse
case class Err(error: String, error_description: Option[String] = None, error_uri: Option[String] = None,
  @transient redirect_uri: Option[String] = None, @transient status_code: Int = StatusCodes.BadRequest) extends ErrorResponse(error, error_description, error_uri) with OauthResponse

case class Oauth2Client(clientId: String, clientSecret: String, scope: Seq[String] = Seq(), authorizedGrantTypes: Seq[String] = Seq(),
  redirectUri: String, authorities: Seq[String] = Seq(), accessTokenValidity: Long = 3600, refreshtokenValidity: Long = 604800,
  additionalInfo: Option[String], autoapprove: Boolean = false)

case class UserId(value: String, provider: Option[String])  
case class Oauth2User(id: UserId)  

case class ClientAuthentication(clientId: String, clientSecret: String)

case class AccessAndRefreshTokens(accessToken: AccessToken, refreshToken: Option[RefreshToken] = None)

trait OauthConfig {
  def authorizeEndpoint: String = "/oauth/authorize"
  def accessTokenEndpoint: String = "/oauth/token"
  def processApprovalEndpoint: String = "/oauth/approve"  
}

trait AuthzRequestValidation {
  this: AuthzRequest =>

  import oauth2.spec.AuthzErrors._

  def getError(implicit client: Oauth2Client): Option[Err] = {
    errClientId orElse
      errResponseType orElse
      errRedirectUri orElse
      errScope
  }

  private def errClientId = {
    errForEmpty(clientId, err(invalid_request, s"mandatory: $client_id"))
  }

  private def errScope(implicit client: Oauth2Client) = {
    errForEmpty(authScope, err(invalid_request, s"mandatory: $scope")) orElse {
      if (authScope.foldLeft(false)((acc, current) => acc || !client.scope.contains(current))) Some(err(invalid_request, s"invalid scope value")) else None
    }
  }

  private def errRedirectUri(implicit client: Oauth2Client) = {
    errForEmpty(redirectUri, err(invalid_request, s"mandatory: $redirect_uri")) orElse
      (if (redirectUri != client.redirectUri) Some(err(invalid_request, s"missmatched: $redirect_uri")) else None)
  }

  private def errResponseType = {
    errForEmpty(responseType, err(invalid_request, s"mandatory: $response_type")) orElse {
      responseType match {
        case ResponseType.code | ResponseType.token => None
        case _ => Some(err(invalid_request, s"mandatory: $response_type in ['${ResponseType.code}','${ResponseType.token}']"))
      }
    }
  }

  private def errForEmpty(value: { def isEmpty: Boolean }, error: Err) = {
    Option(value).filter(!_.isEmpty) match {
      case Some(s: Any) => None
      case _ => Some(error)
    }
  }
}

trait AccessTokenRequestValidation {
  this: AccessTokenRequest =>

  import oauth2.spec.AccessTokenErrors._

  def getError(authzRequest: AuthzRequest, authenticatedClientId: String): Option[Err] = {
    errClientId(authzRequest, authenticatedClientId) orElse
      errGrantType orElse
      errCode(authzRequest) orElse
      errForUnmatchingRedirectUri(authzRequest)
  }

  private def errClientId(authzRequest: AuthzRequest, authenticatedClientId: String) = {
    if (authzRequest.clientId != authenticatedClientId) Some(err(invalid_grant, s"mismatched $client_id")) else None
  }

  private def errGrantType = {
    errForEmpty(grantType, err(invalid_request, s"mandatory: $grant_type")) orElse {
      if (grantType != authorization_code) Some(err(invalid_grant, s"mandatory: $grant_type in ['$authorization_code']")) else None
    }
  }

  private def errCode(authzRequest: AuthzRequest) = {
    errForEmpty(authzCode, err(invalid_request, s"mandatory: $code"))
  }

  private def errForUnmatchingRedirectUri(authzRequest: AuthzRequest) = {
    errForEmpty(redirectUri, err(invalid_request, s"mandatory: $redirect_uri")) orElse {
      if (authzRequest.redirectUri != redirectUri) Some(err(invalid_request, s"mismatched: $redirect_uri")) else None
    }
  }

  private def errForEmpty(value: String, error: Err) = {
    Option(value).filterNot(_.trim.isEmpty) match {
      case Some(s: String) => None
      case _ => Some(error)
    }
  }
}

trait ClientCredentialsRequestValidation {
  this: ClientCredentialsRequest =>
    def getError(): Option[Err] = None
}