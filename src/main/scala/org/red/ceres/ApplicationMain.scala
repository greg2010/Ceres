package org.red.ceres

import com.twitter.util.Await
import com.typesafe.scalalogging.LazyLogging
import org.red.ceres.Implicits._
import org.red.ceres.controllers._
import org.red.ceres.external.auth.EveApiClient
import org.red.ceres.finagle.{PermissionServer, UserServer}
import org.red.iris.finagle.clients.TeamspeakClient
import org.red.iris.finagle.servers.{PermissionServer => TPermissionServer, UserServer => TUserServer}

import scala.concurrent.ExecutionContext.Implicits.global


object ApplicationMain extends App with LazyLogging {

  lazy val teamspeakClient = new TeamspeakClient(config)

  lazy val eveApiClient: EveApiClient = new EveApiClient(ceresConfig)
  lazy val permissionController: PermissionController = new PermissionController(userController)
  lazy val userController: UserController = new UserController(permissionController, eveApiClient, teamspeakClient)

  lazy val scheduleController = new ScheduleController(ceresConfig, userController)

  val userServer = new TUserServer(config).build(new UserServer(userController, eveApiClient))
  val permissionServer = new TPermissionServer(config).build(new PermissionServer(permissionController))

  Await.result(userServer)
}
