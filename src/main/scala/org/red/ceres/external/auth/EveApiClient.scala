package org.red.ceres.external.auth

import cats.data.NonEmptyList
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.red.ceres.util._
import org.red.iris.EveUserData

import scala.concurrent.{ExecutionContext, Future}


class EveApiClient(config: Config)(implicit ec: ExecutionContext) extends LazyLogging {
  private val publicDataClient = new PublicDataClient
  private val legacyClient = new LegacyClient(config, publicDataClient)
  private val ssoClient = new SSOClient(config, publicDataClient)


  def fetchUserByCharacterId(characterId: Long): Future[EveUserData] = {
    publicDataClient.fetchUserByCharacterId(characterId)
  }

  def fetchUser(credentials: CeresCredentials): Future[NonEmptyList[EveUserData]] = {
    credentials match {
      case legacyCredentials: CeresLegacyCredentials => legacyClient.fetchUser(legacyCredentials)
      case ssoCredentials: CeresSSOCredentials => ssoClient.fetchUser(ssoCredentials)
        .map(x => NonEmptyList(x, List()))
    }
  }

  def fetchCredentials(ssoAuthCode: SSOAuthCode): Future[CeresSSOCredentials] = {
    ssoClient.fetchSSOCredential(ssoAuthCode)
  }

  def fetchCredentials(refreshToken: String): Future[CeresSSOCredentials] = {
    ssoClient.createSSOCredential(refreshToken)
  }

  def fetchUserAndCredentials(ssoAuthCode: SSOAuthCode): Future[(CeresSSOCredentials, EveUserData)] = {
    for {
      creds <- this.fetchCredentials(ssoAuthCode)
      data <- ssoClient.fetchUser(creds)
    } yield (creds, data)
  }

  def fetchUserAndCredentials(refreshToken: String): Future[(CeresSSOCredentials, EveUserData)] = {
    for {
      creds <- this.fetchCredentials(refreshToken)
      data <- ssoClient.fetchUser(creds)
    } yield (creds, data)
  }
}
