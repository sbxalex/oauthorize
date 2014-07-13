package app

import oauthorize.model._
import oauthorize.service._
import grants._
import grants.playimpl._
import play.api.mvc._

trait OauthMix extends OauthConfig
  with InMemoryOauth2Store
  with DefaultAuthzCodeGenerator
  with BCryptPasswordEncoder
  with ExecutionContextProvider {
    override val oauthExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext
  }

object Oauth extends OauthMix

object OauthRequestValidator extends OauthRequestValidatorPlay with OauthMix
object CodeGrant extends AuthorizationCodePlay with OauthMix
object ImplicitGrant extends ImplicitGrantPlay with OauthMix
object ClientCredentialsGrant extends ClientCredentialsGrantPlay with OauthMix
object AccessToken extends AccessTokenEndpointPlay with OauthMix
object UserApproval extends UserApprovalPlay with OauthMix

class AppFilters extends WithFilters(OauthRequestValidator, CodeGrant, ImplicitGrant, ClientCredentialsGrant, AccessToken, UserApproval)