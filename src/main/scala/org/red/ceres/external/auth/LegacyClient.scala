package org.red.ceres.external.auth

import cats.data.NonEmptyList
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import moe.pizza.eveapi.{ApiKey, EVEAPI}
import org.red.ceres.util.CeresLegacyCredential
import org.red.iris.{BadEveCredential, EveUserData, ResourceNotFoundException}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


private[this] class LegacyClient(config: Config, publicDataClient: PublicDataClient)
                                (implicit ec: ExecutionContext) extends LazyLogging {
  private val minimumMask: Int = config.getInt("legacyAPI.minimumKeyMask")

  def fetchUser(legacyCredentials: CeresLegacyCredential): Future[NonEmptyList[EveUserData]] = {
    lazy val client = new EVEAPI()(Some(legacyCredentials.apiKey), ec)
    val f = client.account.APIKeyInfo().flatMap {
      case Success(res) if (res.result.key.accessMask & minimumMask) == minimumMask =>
        legacyCredentials.name match {
          case Some(n) =>
            res.result.key.rowset.row.find(_.characterName == n) match {
              case Some(ch) => publicDataClient.fetchUserByCharacterId(ch.characterID.toLong)
                .map( x => NonEmptyList(x, List()))
              case None => throw ResourceNotFoundException(s"Character ${legacyCredentials.name} not found")
            }
          case None =>
            Future.sequence {
              res.result.key.rowset.row.map { ch =>
                publicDataClient.fetchUserByCharacterId(ch.characterID.toLong)
              }
            }.map { x =>
              NonEmptyList.fromList(x.toList) match {
                case Some(l) => l
                case None => throw ResourceNotFoundException("No characters found for the API key")
              }
            }
        }
      case Failure(ex) => throw BadEveCredential("Invalid key", -2)
      case _ => throw BadEveCredential("Invalid mask", -1)
    }
    f.onComplete {
      case Success(res) =>
        logger.info(s"Fetched user using legacy API " +
          s"characterIds=${res.map(_.characterId).toList.mkString(",")} " +
          s"event=external.auth.legacy.fetch.success")
      case Failure(ex) =>
        logger.error(s"Failed to fetch user using legacy API " +
          s"event=external.auth.legacy.fetch.failure", ex)
    }
    f
  }
}
