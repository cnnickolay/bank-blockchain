package com.digitalasset.quickstart.iou

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.grpc.adapter.AkkaExecutionSequencerPool
import com.digitalasset.ledger.api.refinements.ApiTypes.{ApplicationId, WorkflowId}
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.client.LedgerClient
import com.digitalasset.ledger.client.binding.Contract
import com.digitalasset.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement
}
import com.digitalasset.quickstart.iou.ClientUtil.workflowIdFromParty
import com.digitalasset.quickstart.iou.DecodeUtil.{decodeAllCreated, decodeArchived, decodeCreated}
import com.digitalasset.quickstart.iou.FutureUtil.toFuture
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

import com.digitalasset.ledger.client.binding.{Primitive => P}
import com.digitalasset.quickstart.iou.model.Banking._

object IouMain extends App with StrictLogging {

  if (args.length != 2) {
    logger.error("Usage: LEDGER_HOST LEDGER_PORT")
    System.exit(-1)
  }

  logger.info("Starting")
  private val ledgerHost = args(0)
  private val ledgerPort = args(1).toInt

  private val bank = P.Party("bank")
  private val nik = P.Party("nik")
  private val vero = P.Party("vero")

  private val asys = ActorSystem()
  private val amat = ActorMaterializer()(asys)
  private val aesf = new AkkaExecutionSequencerPool("clientPool")(asys)

  private def shutdown(): Unit = {
    logger.info("Shutting down...")
    Await.result(asys.terminate(), 10.seconds)
    ()
  }

  private implicit val ec: ExecutionContext = asys.dispatcher

  private val applicationId = ApplicationId("IOU Example")

  private val timeProvider = TimeProvider.Constant(Instant.EPOCH)

  private val clientConfig = LedgerClientConfiguration(
    applicationId = ApplicationId.unwrap(applicationId),
    ledgerIdRequirement = LedgerIdRequirement("", enabled = false),
    commandClient = CommandClientConfiguration.default,
    sslContext = None
  )

  private val clientF: Future[LedgerClient] =
    LedgerClient.singleHost(ledgerHost, ledgerPort, clientConfig)(ec, aesf)

  private val clientUtilF: Future[ClientUtil] =
    clientF.map(client => new ClientUtil(client, applicationId, 30.seconds, timeProvider))

  private val offset0F: Future[LedgerOffset] = clientUtilF.flatMap(_.ledgerEnd)

  private val bankWorkflowId: WorkflowId = workflowIdFromParty(bank)

  val newOwnerAcceptsAllTransfers: Future[Unit] = for {
    clientUtil <- clientUtilF
    offset0 <- offset0F
    _ <- clientUtil.subscribe(bank, offset0, None) { tx =>
      logger.info(s"$bank received new order: $tx")

      decodeCreated[TransferOrder](tx).foreach { contract: Contract[TransferOrder] =>
        logger.info(s"$bank received contract: $contract")
        val exerciseCmd = contract.contractId.exerciseExecuteTransferOrder(actor = bank)
        clientUtil.submitCommandReturnUnit(bank, bankWorkflowId, exerciseCmd) onComplete {
          case Success(_) =>
            logger.info(s"$bank sent exercise command: $exerciseCmd")
            logger.info(s"$bank accepted IOU Transfer: $contract")
          case Failure(e) =>
            logger.error(s"$bank failed to send exercise command: $exerciseCmd", e)
        }
      }
    }(amat)
  } yield ()

  def createOrder() = for {
    clientUtil <- clientUtilF
    offset0 <- offset0F
    _ = logger.info(s"Client API initialization completed, Ledger ID: ${clientUtil.toString}")

    createCmd = TransferOrder(bank, nik, "1111", "2222", BigDecimal(1.0)).create
    _ <- clientUtil.submitCommand(bank, bankWorkflowId, createCmd)
    _ = logger.info(s"$bank created BANK: $bank")
    _ = logger.info(s"$bank sent create command: $createCmd")
  } yield ()

  createOrder()
  Thread.sleep(1000)
  createOrder()
  Thread.sleep(1000)
  createOrder()

  val returnCodeF: Future[Int] = newOwnerAcceptsAllTransfers.transform {
    case Success(_) =>
      logger.info("IOU flow completed.")
      Success(0)
    case Failure(e) =>
      logger.error("IOU flow completed with an error", e)
      Success(1)
  }

  //  val returnCode: Int = Await.result(returnCodeF, 10.seconds)
  //  shutdown()
  //  System.exit(returnCode)
}
