package org.red.ceres.external.auth

import java.io.InputStream

import com.gilt.gfc.concurrent.ScalaFutures.retryWithExponentialDelay
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, parser}
import org.red.ceres.util.CeresSSOCredential
import org.red.iris.{CCPException, EveUserData}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scalaj.http.{Http, HttpRequest}


private[this] class SSOClient(config: Config, publicDataClient: PublicDataClient)(implicit ec: ExecutionContext) extends LazyLogging {
  private val defaultDatasource = Some("tranquility")
  private val baseUrl = "https://login.eveonline.com/oauth"
  private val userAgent = "red-ceres/1.0"

  private def tokenRequest: HttpRequest =
    Http(baseUrl + "/token")
      .header("Content-Type", "application/json")
      .auth(config.getString("SSOClientId"), config.getString("SSOClientSecret"))

  private case class VerifyResponse(CharacterID: Long,
                                    CharacterName: String,
                                    ExpiresOn: String,
                                    Scopes: String,
                                    TokenType: String,
                                    CharacterOwnerHash: String)

  private case class TokenExchangeRequest(grant_type: String, code: String)

  private case class TokenResponse(access_token: String,
                                   token_type: String,
                                   expires_in: Int,
                                   refresh_token: String)

  private def parseResponse[T](respCode: Int,
                               headers: Map[String, IndexedSeq[String]],
                               is: InputStream)(implicit evidence: Decoder[T]): T = {
    respCode match {
      case 200 =>
        parser.decode[T](scala.io.Source.fromInputStream(is).mkString) match {
          case Right(res) => res
          case Left(ex) => throw CCPException("Failed to parse SSO response")
        }
      case x =>
        throw CCPException(s"Got $x status code on attempt to access SSO API, " +
          s"response was ${scala.io.Source.fromInputStream(is).mkString}")
    }
  }


  private def executeAsync[T](httpRequest: HttpRequest,
                              parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T,
                              attempts: Int = 3) = {
    retryWithExponentialDelay(
      maxRetryTimes = attempts,
      initialDelay = 100.millis,
      maxDelay = 100.millis,
      exponentFactor = 1) {
      Future(httpRequest.exec[T](parser).body)
    }
  }

  def fetchSSOCredential(authCode: String): Future[CeresSSOCredential] = {
    val q =
      tokenRequest.postData(TokenExchangeRequest(grant_type = "authorization_code", code = authCode).asJson.noSpaces)
      .compress(false)
    this.executeAsync[TokenResponse](
      q,
      parseResponse[TokenResponse],
      attempts = 0 // Cannot retry
    ).map { res =>
      CeresSSOCredential(res.refresh_token, res.access_token)
    }
  }

  def refreshAccessToken(refreshToken: String): Future[String] = {
    val q =
      tokenRequest.postForm(
        Seq {
          ("grant_type", "refresh_token")
          ("refresh_token", refreshToken)
        }
      )
    this.executeAsync[TokenResponse](q, parseResponse[TokenResponse])
      .map(_.access_token)
  }

  def fetchUser(credential: CeresSSOCredential): Future[EveUserData] = {
    val q =
      Http(baseUrl + "/verify")
        .method("GET")
        .header("User-Agent", userAgent)
        .header("Authorization", s"Bearer ${credential.accessToken}")
    this.executeAsync[VerifyResponse](q, parseResponse[VerifyResponse])
      .flatMap(r => publicDataClient.fetchUserByCharacterId(r.CharacterID))
    // TODO: handle expired access token
  }
}
