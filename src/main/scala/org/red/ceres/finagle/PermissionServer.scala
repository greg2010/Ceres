package org.red.ceres.finagle

import com.twitter.util.{Future => TFuture}
import org.red.ceres.controllers.PermissionController
import org.red.iris._
import org.red.ceres.util.converters._

import scala.concurrent.ExecutionContext

class PermissionServer(permissionController: => PermissionController)
                      (implicit ec: ExecutionContext) extends PermissionService[TFuture] {
  override def getPermissionBits(mask: Long): TFuture[Seq[PermissionBit]] = {
    TFuture(permissionController.getAclPermissions(mask).map(_.toPermissionBit))
  }

  override def getPermissionMask(permissionList: Seq[PermissionBit]): TFuture[Long] = {
    TFuture(permissionController.getBinPermissions(permissionList.map(_.toPermissionBitEntry)))
  }

  override def getPermissionList: TFuture[Seq[PermissionBit]] = {
    TFuture(permissionController.permissionMap.map(_.toPermissionBit))
  }
}
