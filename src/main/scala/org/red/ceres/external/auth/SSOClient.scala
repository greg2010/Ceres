package org.red.ceres.external.auth

import java.io.InputStream

import com.gilt.gfc.concurrent.ScalaFutures.retryWithExponentialDelay
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.{Decoder, parser}
import org.red.ceres.util.{CeresSSOCredentials, SSOAuthCode}
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
      .method("POST")
      .header("User-Agent", userAgent)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .auth(config.getString("SSOClientId"), config.getString("SSOClientSecret"))

  private case class VerifyResponse(CharacterID: Long,
                                    CharacterName: String,
                                    ExpiresOn: String,
                                    Scopes: String,
                                    TokenType: String,
                                    CharacterOwnerHash: String)


  private case class TokenResponse(access_token: String,
                                   token_type: String,
                                   expires_in: String,
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
        throw CCPException(s"Got $x status code on attempt to access SSO API")
    }
  }


  private def executeAsync[T](httpRequest: HttpRequest,
                              parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T) = {
    retryWithExponentialDelay(
      maxRetryTimes = 3,
      initialDelay = 100.millis,
      maxDelay = 100.millis,
      exponentFactor = 1) {
      Future(httpRequest.exec[T](parser).body)
    }
  }

  def fetchSSOCredential(SSOAuthCode: SSOAuthCode): Future[CeresSSOCredentials] = {
    val q =
      tokenRequest.postForm(
        Seq {
          ("grant_type", "authorization_code")
          ("code", SSOAuthCode.code)
        }
      )
    this.executeAsync[TokenResponse](
      q,
      parseResponse[TokenResponse]
    ).map { res =>
      CeresSSOCredentials(res.refresh_token, Some(res.access_token))
    }
  }

  def createSSOCredential(refreshToken: String): Future[CeresSSOCredentials] = {
    val q =
      tokenRequest.postForm(
        Seq {
          ("grant_type", "refresh_token")
          ("refresh_token", refreshToken)
        }
      )
    this.executeAsync[TokenResponse](q, parseResponse[TokenResponse]).map { res =>
      CeresSSOCredentials(refreshToken, Some(res.access_token))
    }
  }

  def fetchUser(credential: CeresSSOCredentials): Future[EveUserData] = {
    val q =
      Http(baseUrl + "/verify")
        .method("GET")
        .header("User-Agent", userAgent)
        .header("Authorization", s"Bearer ${credential.accessToken}")
    this.executeAsync[VerifyResponse](q, parseResponse[VerifyResponse])
      .flatMap(r => publicDataClient.fetchUserByCharacterId(r.CharacterID))
  }
}
