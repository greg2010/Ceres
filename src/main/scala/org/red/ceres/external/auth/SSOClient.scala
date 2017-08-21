package org.red.ceres.external.auth

import java.io.InputStream

import com.gilt.gfc.concurrent.ScalaFutures.retryWithExponentialDelay
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, parser}
import org.red.ceres.util.{CeresSSOCredential, TokenExpiredException}
import org.red.db.models.Coalition
import org.red.iris.{BadEveCredential, CCPException, EveUserData}
import slick.dbio.Effect
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scalaj.http.{Http, HttpRequest}


private[this] class SSOClient(config: Config, publicDataClient: PublicDataClient)
                             (implicit ec: ExecutionContext, dbAgent: JdbcBackend.Database) extends LazyLogging {
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

  private case class ErrorResponse(error: String, sso_status: Int)

  private def parseResponse[T](respCode: Int,
                               headers: Map[String, IndexedSeq[String]],
                               is: InputStream)(implicit evidence: Decoder[T]): T = {
    def handleError(code: Int, er: ErrorResponse): Nothing = {
      (code, er) match {
        case (403, ErrorResponse("expired", 400)) => throw new TokenExpiredException
        case (400, _) => throw BadEveCredential("Bad credential", 1)
        case _ => throw CCPException("Bad response code and/or response from CCP")
      }
    }
    def handleParseException(ex: Exception): Nothing = {
      logger.error("Failed to parse SSO response, the response was\n" +
        s"${scala.io.Source.fromInputStream(is).mkString}\n" +
        s"event=sso.parse.failed", ex)
      throw CCPException("Failed to parse SSO response")
    }
    respCode match {
      case 200 =>
        parser.decode[T](scala.io.Source.fromInputStream(is).mkString) match {
          case Right(res) => res
          case Left(ex) => handleParseException(ex)
        }
      case 400 => handleError(400, ErrorResponse("_", 400))
      case x =>
        parser.decode[ErrorResponse](scala.io.Source.fromInputStream(is).mkString) match {
          case Right(er) => handleError(x, er)
          case Left(ex) => handleParseException(ex)
        }
    }
  }


  private def executeAsync[T](httpRequest: HttpRequest,
                              parser: (Int, Map[String, IndexedSeq[String]], InputStream) => T,
                              attempts: Int = 3): Future[T] = {
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
    def updateAccessTokenQuery(newAccessToken: String): FixedSqlAction[Int, NoStream, Effect.Write] = {
      Coalition.EveApi.filter(_.evessoRefreshToken === credential.refreshToken)
        .map(_.evessoAccessToken).update(newAccessToken)
    }
    val q =
      Http(baseUrl + "/verify")
        .method("GET")
        .header("User-Agent", userAgent)
        .header("Authorization", s"Bearer ${credential.accessToken}")
    this.executeAsync[VerifyResponse](q, parseResponse[VerifyResponse])
      .flatMap(r => publicDataClient.fetchUserByCharacterId(r.CharacterID))
      .recoverWith {
        case ex: TokenExpiredException => for {
          accessToken <- this.refreshAccessToken(credential.refreshToken)
          newCredential <- Future(credential.copy(accessToken = accessToken))
          res <- this.fetchUser(credential)
          updateToken <- dbAgent.run(updateAccessTokenQuery(accessToken))
        } yield res
      }
  }
}
