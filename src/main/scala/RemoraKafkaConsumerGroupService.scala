import java.util.logging.Logger

import kafka.admin.ConsumerGroupCommand
import kafka.admin.ConsumerGroupCommand.{ConsumerGroupCommandOptions, KafkaConsumerGroupService}

import scala.concurrent.{ExecutionContextExecutor, Future}

trait ConsumerGroupService {
  def list(): Future[List[String]]
  def describeConsumerGroup(group: String): Future[GroupInfo]
}

class RemoraKafkaConsumerGroupService(kafkaSettings: KafkaSettings)
                                     (implicit executionContext: ExecutionContextExecutor) extends ConsumerGroupService
  with nl.grons.metrics.scala.DefaultInstrumented {

  private val logger = Logger.getLogger(RemoraKafkaConsumerGroupService.this.getClass.getName)

  private val listTimer = metrics.timer("list-timer")
  private val describeTimer = metrics.timer("describe-timer")

  private def createKafkaConsumerGroupService(groupId: Option[String] = None): ConsumerGroupCommand.KafkaConsumerGroupService = {

    val baseConfig: Array[String] = Array("--bootstrap-server", kafkaSettings.address)

    groupId match {
      case Some(g) => createKafkaConsumerGroupService(baseConfig ++ Array("--group", g))
      case None => createKafkaConsumerGroupService(baseConfig)
    }
  }

  def createKafkaConsumerGroupService(baseConfig: Array[String]): KafkaConsumerGroupService = {
    new KafkaConsumerGroupService(new ConsumerGroupCommandOptions(baseConfig))
  }

  def list(): Future[List[String]] = Future {
    listTimer.time {
      val adminClient = createKafkaConsumerGroupService()
      try {
        adminClient.listGroups()
      } finally {
        adminClient.close()
      }
    }
  }

  def describeConsumerGroup(group: String): Future[GroupInfo] = Future {
    describeTimer.time {
      val kafkaConsumerGroupService = createKafkaConsumerGroupService(Some(group))
      try {
        val (state, assignments) = kafkaConsumerGroupService.describeGroup()
        assignments match {
          case Some(partitionAssignmentStates) => GroupInfo(state,
            Some(partitionAssignmentStates.map(a => PartitionAssignmentState(a.group,
              a.coordinator match {
                case Some(c) => Some(Node(Option(c.id), Option(c.idString), Option(c.host), Option(c.port), Option(c.rack)))
                case None => None
              },
              a.topic, a.partition, a.offset,
              a.lag, a.consumerId, a.host, a.clientId, a.logEndOffset))))
          case None => GroupInfo(state)
        }
      } finally {
        kafkaConsumerGroupService.close()
      }
    }
  }
}