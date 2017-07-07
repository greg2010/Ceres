package org.red

import akka.actor.ActorSystem
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration._
import scala.language.postfixOps


package object ceres {
  private val conf: Config = ConfigFactory.load()
  val ceresConfig: Config = conf.getConfig("ceres")

  object Implicits {
    implicit val timeout: Timeout = Timeout(2 seconds)
    implicit val dbAgent: JdbcBackend.Database = Database.forConfig("postgres", conf)
  }

}
