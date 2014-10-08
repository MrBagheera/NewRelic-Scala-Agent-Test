import java.util
import java.util.concurrent.{TimeUnit, Executors}

import com.newrelic.api.agent._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Random, Success}


case class Player(id: String, name: String, age: Int)

/**
 * Simulates async IO by returning the future and completing it from some other thread. 
 */
class PlayerRepository {

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

  Thread.sleep(100000)

  @Trace(dispatcher = true)
  def simulateTransaction(): Unit = {
    NewRelic.setTransactionName(null, "test")
    val id = random.alphanumeric.take(10).mkString
    println(s"Starting transaction for $id")
    val updateFuture: Future[Player] = playerService.updatePlayerAge(id, random.nextInt(100))
    updateFuture onComplete (completeTransaction(id, _))
  }

  private def completeTransaction(id: String, result: Try[Player]): Unit = {
    val (httpStatusCode, httpStatusMessage) = result match {
      case Success(player) =>
        println(s"Transaction for $id completed")
        (200, "OK")
      case Failure(error) =>
        println(s"Transaction for $id failed with $error")
        (500, "Internal Server Error")
    }

    NewRelic.setRequestAndResponse(
      new Request {
        override def getRemoteUser: String = null
        override def getParameterNames: util.Enumeration[_] = new util.Enumeration[Nothing] {
          override def hasMoreElements: Boolean = false
          override def nextElement(): Nothing = throw new NoSuchElementException
        }
        override def getAttribute(name: String): AnyRef = null
        override def getRequestURI: String = "/player/updateAge"
        override def getParameterValues(name: String): Array[String] = null
        override def getCookieValue(name: String): String = null
        override def getHeaderType: HeaderType = HeaderType.HTTP
        override def getHeader(name: String): String = null
      },
      new Response {
        override def getStatus: Int = httpStatusCode
        override def getStatusMessage: String = httpStatusMessage
        override def getContentType: String = null
        override def getHeaderType: HeaderType = HeaderType.HTTP
        override def setHeader(name: String, value: String): Unit = {}
      }
    )
  }

}
