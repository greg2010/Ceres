package org.red.ceres.jobs.quartz

import com.typesafe.scalalogging.LazyLogging
import org.quartz._
import org.red.ceres.controllers.ScheduleController
import org.red.ceres.exceptions.ExceptionHandlers
import org.red.db.models.Coalition
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

class UserDaemonJob extends Job with LazyLogging {
  override def execute(context: JobExecutionContext): Unit = {
    try {
      val dbAgent = context.getScheduler.getContext.get("dbAgent").asInstanceOf[JdbcBackend.Database]
      val scheduleController = context.getScheduler.getContext.get("scheduleController").asInstanceOf[ScheduleController]
      dbAgent.run(Coalition.Users.map(_.id).result).flatMap { r =>
        Future.sequence {
          r.map(scheduleController.scheduleUserUpdate)
        }.map(_.count(_.isDefined))
      }.onComplete {
        case Success(0) => logger.info("No new users to schedule for updates " +
          "usersScheduled=0 " +
          "event=user.schedule.success")
        case Success(affected) => logger.info(s"Successfully scheduled users for updates " +
          s"usersScheduled=$affected " +
          s"event=user.schedule.success")
        case Failure(ex) => logger.error("Failed to schedule users for updates " +
          "event=user.schedule.failure", ex)
      }
    } catch {
      ExceptionHandlers.jobExceptionHandler
    }
  }
}
