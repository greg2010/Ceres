package org.red.ceres.util

import org.red.iris.PermissionBit

import scala.language.implicitConversions

package object converters {
  implicit class RichPermissionBit(val permissionBit: PermissionBit) extends AnyVal {
    implicit def toPermissionBitEntry: PermissionBitEntry = {
      PermissionBitEntry(
        name = permissionBit.name,
        bit_position = permissionBit.bitPosition,
        description =  permissionBit.description
      )
    }
  }
}