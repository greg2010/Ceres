package org.red.ceres.controllers


import com.gilt.gfc.concurrent.ScalaFutures
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import org.red.ceres.util.PermissionBitEntry
import org.red.db.models.Coalition
import org.red.iris.util.YamlParser
import org.red.iris.{EveUserData, PermissionBit, ResourceNotFoundException}
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}


class PermissionController(userController: => UserController)(implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext) extends LazyLogging {

  private case class PermissionMap(permission_map: Seq[PermissionBitEntry])
  val permissionMap: Seq[PermissionBitEntry] =
    YamlParser.parseResource[PermissionMap](Source.fromResource("permission_map.yml")).permission_map

  def findPermissionByName(name: String): PermissionBitEntry = {
    permissionMap.find(_.name == name) match {
      case Some(res) =>
        logger.info(s"Permission found name=$name event=perm.find.byName.success")
        res
      case None =>
        logger.error(s"Permission not found name=$name event=perm.find.byName.failure")
        throw ResourceNotFoundException("No permission exists with such name")
    }
  }

  def getPermissions(userId: Int): Future[Seq[PermissionBit]] = {
    for {
      characters <- userController.getOwnedCharacters(userId)
      permissions <- calculateBinPermission(characters.toList:_*).map(getAclPermissions)
    } yield permissions
  }

  def calculateBinPermission(characterId: Option[Long],
                             corporationId: Option[Long],
                             allianceId: Option[Long]): Future[Long] = {
    val q = Coalition.Acl.filter(
      r =>
        (r.characterId === characterId || r.characterId.isEmpty) &&
          (r.corporationId === corporationId || r.corporationId.isEmpty) &&
          (r.allianceId === allianceId || r.allianceId.isEmpty) &&
          (r.characterId.isDefined || r.corporationId.isDefined || r.allianceId.isDefined)
    ).map(_.entityPermission)

    val f = dbAgent.run(q.result).map { res =>
      res.foldLeft(0L)((l, r) => l | r)
    }
    f.onComplete {
      case Success(res) => logger.info(s"Calculated acl for " +
        s"characterId=${characterId.getOrElse("")} " +
        s"corporationId=${corporationId.getOrElse("")} " +
        s"allianceId=${allianceId.getOrElse("")} " +
        s"binPermissions=$res " +
        s"event=perm.calculate.success")
      case Failure(ex) => logger.error("Failed to calculate acl for " +
        s"characterId=${characterId.getOrElse("")} " +
        s"corporationId=${corporationId.getOrElse("")} " +
        s"allianceId=${allianceId.getOrElse("")} " +
        s"event=perm.calculate.failure", ex)
    }
    f
  }

  def calculateBinPermission(eveUserData: EveUserData*): Future[Long] = {
    val permissionList = eveUserData.map { u =>
      calculateBinPermission(
        Some(u.characterId),
        Some(u.corporationId),
        u.allianceId
      )
    }
    ScalaFutures.foldFast(permissionList)(0)(_ | _)
  }

  def getBinPermissions(acl: Seq[PermissionBitEntry]): Long = {
    @tailrec
    def getBinPermissionsRec(soFar: Long,
                             toParse: Seq[PermissionBitEntry]): Long = {
      if (toParse.isEmpty) soFar
      else getBinPermissionsRec(soFar + (1 << toParse.head.bit_position), toParse.tail)
    }

    val res = getBinPermissionsRec(0, acl)
    logger.info(s"Got binary permissions from " +
      s"acl=${acl.map(_.name).mkString(",")} " +
      s"binPermissions=$res " +
      s"event=perm.aclToBin.success")
    res
  }

  def getAclPermissions(aclMask: Long): Seq[PermissionBit] = {
    @tailrec
    def getBitsRec(aclMask: Long, curPosn: Int, soFar: Seq[Int]): Seq[Int] = {
      val mask = 1
      if (aclMask == 0) soFar
      else if ((aclMask & mask) == 1) getBitsRec(aclMask >> 1, curPosn + 1, soFar :+ curPosn)
      else getBitsRec(aclMask >> 1, curPosn + 1, soFar)
    }

    val res = getBitsRec(aclMask, 0, Seq()).map { bit =>
      permissionMap.find(_.bit_position == bit) match {
        case Some(entry) => entry.toPermissionBit
        case None => throw new RuntimeException("No permission bit defn found") //FIXME: change exception type
      }
    }
    logger.info(s"Got binary permissions from " +
      s"acl=${res.map(_.name).mkString(",")} " +
      s"binPermissions=$aclMask " +
      s"event=perm.binToAcl.success")
    res
  }
}
