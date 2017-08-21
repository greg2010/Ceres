package org.red.ceres.external.auth

import cats.data.NonEmptyList
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.red.ceres.util._
import org.red.iris.EveUserData
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}


trait EveApiClientService {
  def exchangeAuthCode(authCode: String): Future[CeresSSOCredential]
  def refreshAccessToken(refreshToken: String): Future[String]
  def fetchUser(ceresCredentials: CeresCredential): Future[NonEmptyList[EveUserData]]
  def fetchUser(characterId: Long): Future[EveUserData]
}

class EveApiClient(config: Config)
                  (implicit ec: ExecutionContext, dbAgent: JdbcBackend.Database) extends EveApiClientService with LazyLogging {
  private val publicDataClient = new PublicDataClient
  private val legacyClient = new LegacyClient(config, publicDataClient)
  private val ssoClient = new SSOClient(config, publicDataClient)


  def fetchUser(characterId: Long): Future[EveUserData] = {
    publicDataClient.fetchUserByCharacterId(characterId)
  }


  def fetchUser(credentials: CeresCredential): Future[NonEmptyList[EveUserData]] = {
    credentials match {
      case legacyCredentials: CeresLegacyCredential => legacyClient.fetchUser(legacyCredentials)
      case ssoCredentials: CeresSSOCredential => ssoClient.fetchUser(ssoCredentials)
        .map(x => NonEmptyList(x, List()))
    }
  }

  def exchangeAuthCode(authCode: String): Future[CeresSSOCredential] = {
    ssoClient.fetchSSOCredential(authCode)
  }

  def refreshAccessToken(refreshToken: String): Future[String] = {
    ssoClient.refreshAccessToken(refreshToken)
  }
}
