import java.util
import java.util.concurrent.{TimeUnit, Executors}

import com.newrelic.agent.TransactionApiImpl
import com.newrelic.agent.async.AsyncTransactionState
import com.newrelic.api.agent._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Random, Success}
import scala.collection.JavaConversions._

case class Player(id: String, name: String, age: Int)

class ResponseHolder extends Response {
  var statusCode: Int = 0
  var statusMessage: String = null
  override def getStatus: Int = statusCode
  override def getStatusMessage: String = statusMessage
  override def getContentType: String = null
  override def getHeaderType: HeaderType = HeaderType.HTTP
  override def setHeader(name: String, value: String): Unit = {}
}

/**
 * Simulates async IO by returning the future and completing it from some other thread. 
 */
class PlayerRepository {

  @Trace
  def getPlayer(id: String): Future[Player] = {
    val operationPromise = Promise[Player]()
    NewRelicAgentTest.executor.schedule(new Runnable() {
      override def run(): Unit = {
        println(s"Getting player for id=$id")
        operationPromise.complete(Success(Player(id, "Test#" + id, 100)))
      }
    }, 500, TimeUnit.MILLISECONDS)
    operationPromise.future
  }

  @Trace
  def savePlayer(player: Player): Future[Player] = {
    val operationPromise = Promise[Player]()
    NewRelicAgentTest.executor.schedule(new Runnable() {
      override def run(): Unit = {
        println(s"Updating player for id=${player.id}")
        operationPromise.complete(Success(player))
      }
    }, 1500, TimeUnit.MILLISECONDS)
    operationPromise.future
  }

}


/**
 * Typical async service
 */
class PlayerService(playerRepository: PlayerRepository) {

  @Trace
  def updatePlayerAge(id: String, newAge: Int): Future[Player] = {
    playerRepository.getPlayer(id) flatMap { player =>
      val newPlayer = Player(player.id, player.name, newAge)
      playerRepository.savePlayer(newPlayer)
    }
  }
  
}


/**
 * Runs simulated transactions at fixed rate.
 */
object NewRelicAgentTest extends App {
  val executor = Executors.newSingleThreadScheduledExecutor()

  private val playerRepository = new PlayerRepository
  private val playerService = new PlayerService(playerRepository)
  private val random = new Random

  executor.scheduleAtFixedRate(new Runnable {
    override def run(): Unit = {
      simulateTransaction()
    }
  }, 5, 5, TimeUnit.SECONDS)
  Thread.sleep(1000000)


  @Trace(dispatcher = true)
  def simulateTransaction(): Unit = {
    val id = random.alphanumeric.take(10).mkString

    println(s"Starting transaction for $id")
    val responseHolder = new ResponseHolder
    NewRelic.setRequestAndResponse(
      new Request {
        override def getRemoteUser: String = null
        override def getParameterNames: util.Enumeration[_] = Seq("deviceId", "playerId").iterator
        override def getAttribute(name: String): AnyRef = null
        override def getRequestURI: String = "/player/updateAge"
        override def getParameterValues(name: String): Array[String] = name match {
          case "deviceId" => Array("100")
          case "playerId" => Array("player")
        }
        override def getCookieValue(name: String): String = null
        override def getHeaderType: HeaderType = HeaderType.HTTP
        override def getHeader(name: String): String = null
      },
      responseHolder
    )

    // ugly hack to kick NewRelic into tracing Scala Future-s
    val tx = NewRelic.getAgent.getTransaction.asInstanceOf[TransactionApiImpl].getTransaction
    tx.setTransactionState(new AsyncTransactionState(tx.getTransactionActivity))

    val updateFuture: Future[Player] = playerService.updatePlayerAge(id, random.nextInt(100))
    updateFuture onComplete (completeTransaction(responseHolder, id, _))
  }

  private def completeTransaction(responseHolder: ResponseHolder, id: String, result: Try[Player]): Unit = {
    result match {
      case Success(player) =>
        println(s"Transaction for $id completed")
        responseHolder.statusCode = 200
        responseHolder.statusMessage = "OK"
      case Failure(error) =>
        println(s"Transaction for $id failed with $error")
        responseHolder.statusCode = 500
        responseHolder.statusMessage = "Internal Server Error"
    }
  }

}
