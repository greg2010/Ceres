package org.red.ceres.daemons

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Cancelable
import monix.execution.Scheduler.{global => scheduler}
import org.quartz.JobBuilder.newJob
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory
import org.quartz.{Scheduler, SimpleScheduleBuilder, TriggerKey}
import org.red.ceres.controllers.{ScheduleController, UserController}
import org.red.ceres.jobs.quartz.UserDaemonJob
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class ScheduleDaemon(scheduleController: => ScheduleController,
                     config: Config,
                     userController: => UserController)
                    (implicit dbAgent: JdbcBackend.Database, ec: ExecutionContext) extends LazyLogging {
  val quartzScheduler: Scheduler = new StdSchedulerFactory().getScheduler
  private val userDaemonTriggerName = "userDaemon"

  quartzScheduler.getContext.put("dbAgent", dbAgent)
  quartzScheduler.getContext.put("ec", ec)
  quartzScheduler.getContext.put("scheduleController", scheduleController)
  quartzScheduler.getContext.put("userController", userController)
  quartzScheduler.start()
  val userDaemon: Cancelable =
    scheduler.scheduleWithFixedDelay(0.seconds, 1.minute) {
      val maybeTriggerKey = new TriggerKey(userDaemonTriggerName, config.getString("quartzUserUpdateGroupName"))
      if (quartzScheduler.checkExists(maybeTriggerKey)) {
        logger.info("User daemon has already started, doing nothing event=user.schedule")
        quartzScheduler.getTrigger(maybeTriggerKey).getNextFireTime
      } else {
        val j = newJob((new UserDaemonJob).getClass)
          .withIdentity(userDaemonTriggerName, config.getString("quartzUserUpdateGroupName"))
          .build()
        val t = newTrigger()
          .withIdentity(userDaemonTriggerName, config.getString("quartzUserUpdateGroupName"))
          .forJob(j)
          .withSchedule(SimpleScheduleBuilder
            .repeatMinutelyForever(config.getInt("quartzUserUpdateDaemonRefreshRate"))
          )
          .startNow()
          .build()
        quartzScheduler.scheduleJob(j, t)
      }
    }
}
