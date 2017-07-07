package org.red.ceres.external.auth

import net.troja.eve.esi.api.{AllianceApi, CharacterApi, CorporationApi}
import net.troja.eve.esi.model.{AllianceResponse, CharacterResponse, CorporationResponse}
import org.red.iris.EveUserData

import scala.concurrent.{ExecutionContext, Future}

private[this] class PublicDataClient(implicit ec: ExecutionContext) {
  private val defaultDatasource = "tranquility"
  private lazy val esiCharClient = new CharacterApi
  private lazy val esiCorpClient = new CorporationApi
  private lazy val esiAllianceClient = new AllianceApi

  def fetchCharacterById(characterId: Long): Future[CharacterResponse] = Future {
    esiCharClient
      .getCharactersCharacterId(
        characterId.toInt,
        defaultDatasource,
        null,
        null
      )
  }


  def fetchCorporationById(corporationId: Long): Future[CorporationResponse] = Future {
    esiCorpClient
      .getCorporationsCorporationId(
        corporationId.toInt,
        defaultDatasource,
        null,
        null
      )
  }


  def fetchAllianceById(allianceId: Long): Future[AllianceResponse] = Future {
    esiAllianceClient
      .getAlliancesAllianceId(
        allianceId.toInt,
        defaultDatasource,
        null,
        null
      )
  }

  def fetchUserByCharacterId(characterId: Long): Future[EveUserData] = {
    for {
      extraCharInfo <- this.fetchCharacterById(characterId)
      corp <- this.fetchCorporationById(extraCharInfo.getCorporationId.toInt)
      alliance <- Option(corp.getAllianceId) match {
        case Some(id) => fetchAllianceById(id.toInt).map(Option.apply)
        case None => Future(None)
      }
      res <- Future {
        EveUserData(
          characterId,
          extraCharInfo.getName,
          extraCharInfo.getCorporationId.toLong,
          corp.getCorporationName,
          corp.getTicker,
          Option(corp.getAllianceId.toLong),
          alliance.map(_.getAllianceName),
          alliance.map(_.getTicker)
        )
      }
    } yield res
  }
}
