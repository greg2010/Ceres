package org.red.ceres.controllers

import java.util.Date

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import org.quartz.JobBuilder.newJob
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.{CronScheduleBuilder, TriggerKey}
import org.red.ceres.daemons.ScheduleDaemon
import org.red.ceres.jobs.quartz.UserJob
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random


trait ScheduleService {
  def scheduleUserUpdate(userId: Int): Future[Option[Date]]
}

class ScheduleController(config: Config, userController: => UserController)
                        (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext)
  extends ScheduleService with LazyLogging {

  private val daemon = new ScheduleDaemon(this, config, userController)
  val userDaemon: Cancelable = daemon.userDaemon
  private val quartzScheduler = daemon.quartzScheduler

  def scheduleUserUpdate(userId: Int): Future[Option[Date]] = {
    val maybeTriggerKey = new TriggerKey(userId.toString, config.getString("quartzUserUpdateGroupName"))
    for {
      ifExists <- Future(quartzScheduler.checkExists(maybeTriggerKey))
      res <- {
        if (ifExists) {
          logger.info(s"Job already exists, skipping id=$userId userId=$userId event=user.schedule")
          Future(None)
        } else {
          logger.info(s"Job doesn't exist, scheduling id=$userId userId=$userId event=user.schedule")
          val j = newJob()
            .withIdentity(userId.toString, config.getString("quartzUserUpdateGroupName"))
          val builtJob = j.ofType((new UserJob).getClass).build()
          builtJob.getJobDataMap.put("userId", userId)
          if (config.getInt("quartzUserUpdateRefreshRate") > 59)
            throw new IllegalArgumentException("quartzUserUpdateRefreshRate must be <60")

          val randNum = Random.nextInt(config.getInt("quartzUserUpdateRefreshRate"))
          val t = newTrigger()
            .forJob(builtJob)
            .withIdentity(maybeTriggerKey)
            .withSchedule(
              CronScheduleBuilder
                .cronSchedule(s"0 $randNum/${config.getString("quartzUserUpdateRefreshRate")} * * * ?")
            )
            .build()
          val r = Some(quartzScheduler.scheduleJob(builtJob, t))
          logger.info(s"Scheduled " +
            s"userId=$userId " +
            s"event=user.schedule.success")
          Future(r)
        }
      }
    } yield res
  }
}
