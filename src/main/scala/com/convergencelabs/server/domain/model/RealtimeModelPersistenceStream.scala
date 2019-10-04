package com.convergencelabs.server.domain.model

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.convergencelabs.server.datastore.domain.ModelOperationProcessor
import com.convergencelabs.server.datastore.domain.ModelSnapshotStore
import com.convergencelabs.server.datastore.domain.ModelStore
import com.convergencelabs.server.domain.DomainId
import com.convergencelabs.server.domain.model.RealtimeModelPersistence.PersistenceEventHandler

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Status
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import grizzled.slf4j.Logging

trait RealtimeModelPersistenceFactory {
  def create(handler: PersistenceEventHandler): RealtimeModelPersistence
}

object RealtimeModelPersistence {
  trait PersistenceEventHandler {
    def onError(message: String): Unit
    def onClosed(): Unit
    def onOperationCommitted(version: Long): Unit
    def onOperationError(message: String): Unit
  }
}

trait RealtimeModelPersistence {
  def processOperation(op: NewModelOperation): Unit
  def executeSnapshot(): Unit
  def close(): Unit
}

object RealtimeModelPersistenceStream {
  sealed trait ModelPersistenceCommand
  case class ProcessOperation(op: NewModelOperation) extends ModelPersistenceCommand
  case object ExecuteSnapshot extends ModelPersistenceCommand

}

class RealtimeModelPersistenceStream(
                                      private[this] val handler: PersistenceEventHandler,
                                      private[this] val domainFqn: DomainId,
                                      private[this] val modelId: String,
                                      private[this] implicit val system: ActorSystem,
                                      private[this] val modelStore: ModelStore,
                                      private[this] val modelSnapshotStore: ModelSnapshotStore,
                                      private[this] val modelOperationProcessor: ModelOperationProcessor)
  extends RealtimeModelPersistence
  with Logging {

  import RealtimeModelPersistenceStream._

  private[this] implicit val materializer = ActorMaterializer()

  logger.debug(s"Persistence stream started ${domainFqn}/${modelId}")

  def processOperation(op: NewModelOperation): Unit = {
    streamActor ! ProcessOperation(op)
  }

  def executeSnapshot(): Unit = {
    streamActor ! ExecuteSnapshot
  }

  def close(): Unit = {
    streamActor ! Status.Success(())
  }

  private[this] val streamActor: ActorRef =
    Flow[ModelPersistenceCommand]
      .map {
        case ProcessOperation(modelOperation) =>
          onProcessOperation(modelOperation)
        case ExecuteSnapshot =>
          onExecuteSnapshot()
      }.to(Sink.onComplete {
        case Success(_) =>
          logger.debug(s"${domainFqn}/${modelId}: Persistence stream completed successfully.")
          handler.onClosed()
        case Failure(cause) =>
          logger.error(s"${domainFqn}/${modelId}: Persistence stream completed with an error.", cause)
          handler.onError("There was an unexpected error in the persistence stream")
      }).runWith(Source
        .actorRef[ModelPersistenceCommand](bufferSize = 1000, OverflowStrategy.fail))

  private[this] def onProcessOperation(modelOperation: NewModelOperation): Unit = {
    modelOperationProcessor.processModelOperation(modelOperation)
      .map(_ => handler.onOperationCommitted(modelOperation.version))
      .recover {
        case cause: Throwable =>
          logger.error(s"${domainFqn}/${modelId}: Error applying operation: ${modelOperation}", cause)
          handler.onOperationError("There was an unexpected persistence error applying an operation.")
          ()
      }
  }

  private[this] def onExecuteSnapshot(): Unit = {
    Try {
      // FIXME: Handle Failure from try and None from option.
      val modelData = modelStore.getModel(this.modelId).get.get
      val snapshotMetaData = new ModelSnapshotMetaData(
        modelId,
        modelData.metaData.version,
        modelData.metaData.modifiedTime)

      val snapshot = new ModelSnapshot(snapshotMetaData, modelData.data)
      modelSnapshotStore.createSnapshot(snapshot)
      snapshotMetaData
    } map { snapshotMetaData =>
      logger.debug(s"${domainFqn}/${modelId}: Snapshot successfully taken for model " +
        s"at version: ${snapshotMetaData.version}, timestamp: ${snapshotMetaData.timestamp}")
    } recover {

      case cause: Throwable =>
        logger.error(s"${domainFqn}/${modelId}: Error taking snapshot of model.", cause)
    }
  }
}

class RealtimeModelPersistenceStreamFactory(
  private[this] val domainFqn: DomainId,
  private[this] val modelId: String,
  private[this] implicit val system: ActorSystem,
  private[this] val modelStore: ModelStore,
  private[this] val modelSnapshotStore: ModelSnapshotStore,
  private[this] val modelOperationProcessor: ModelOperationProcessor)
  extends RealtimeModelPersistenceFactory {

  def create(handler: PersistenceEventHandler): RealtimeModelPersistence = {
    new RealtimeModelPersistenceStream(handler, domainFqn, modelId, system, modelStore, modelSnapshotStore, modelOperationProcessor)
  }
}