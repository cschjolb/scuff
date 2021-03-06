package scuff.eventual

import java.util.concurrent.{ CountDownLatch, Executors, ScheduledFuture, TimeUnit }
import scala.concurrent.{ Await, Awaitable, ExecutionContext, Future }
import scala.concurrent.duration.{ Duration, DurationInt, FiniteDuration }
import scuff.{ HashBasedSerialExecutionContext, LockFreeConcurrentMap, Subscription, Threads, Timestamp }
import java.util.concurrent.TimeoutException

/**
 * Event stream, which guarantees consistent ordering,
 * even when using distributed protocols that do not.
 * @param es The event source for subscription and replay
 * @param consumerExecCtx The consumer execution context
 * @param replayBuffer Limit the number of in-memory replay transactions.
 * @param gapReplayDelay If revision number gaps are detected, when live, transactions will be replayed after this delay.
 * This should only happen if using unreliable messaging, where messages can get dropped or arrive out-of-order.
 * @param maxClockSkew The max possible clock skew on transaction timestamps
 * @param maxReplayConsumptionWait The maximum time to wait for replay to finish
 */
final class EventStream[ID, EVT, CAT](
    es: EventSource[ID, EVT, CAT],
    consumerCtx: ExecutionContext,
    replayBuffer: Int,
    gapReplayDelay: FiniteDuration,
    maxClockSkew: FiniteDuration,
    maxReplayConsumptionWait: Duration) {

  type Transaction = EventSource[ID, EVT, CAT]#Transaction

  /**
   * A durable consumer goes through two
   * stages,
   * 1) Replay mode. First time run or
   * resumption after downtime will result
   * in feeding of replayed data.
   * 2) Live mode. Once replay is done,
   * consumption becomes live.
   */
  trait DurableConsumer {
    trait LiveConsumer {
      /**
       * Expected revision for a given stream.
       * If unknown stream, return 0.
       */
      def expectedRevision(stream: ID): Int
      /** Consume live transaction. */
      def consumeLive(txn: Transaction)
    }
    /** Last transaction timestamp. */
    def lastTimestamp(): Option[Long]

    /**
     * Called when live consumption starts.
     */
    def onLive(): LiveConsumer

    /** Consume replay transaction. */
    def consumeReplay(txn: Transaction)

    /** Categories. Empty means all. */
    def categoryFilter: Set[CAT]
  }

  private val _failedStreams = new LockFreeConcurrentMap[ID, (CAT, Throwable)]

  def failedStreams = _failedStreams.snapshot()

  private class BlockingReplayProxy(consumer: DurableConsumer) {
    private[this] val awaitQueue = new java.util.concurrent.ArrayBlockingQueue[(Transaction, Awaitable[_])](replayBuffer)
    private[this] var lastTime = -1L
    @volatile var doneReading = false
    private[this] val awaiterLatch = new CountDownLatch(1)
    private val awaiter = new Runnable {
      def run {
        while (!Thread.currentThread.isInterrupted) {
          awaitQueue.poll(1, TimeUnit.SECONDS) match {
            case null =>
              if (doneReading) {
                awaiterLatch.countDown()
                return
              }
            case (txn, awaitable) =>
              try {
                Await.result(awaitable, 60.seconds)
              } catch {
                case toe: TimeoutException =>
                  throw new IllegalStateException(s"${consumer.getClass.getName} timed out processing $txn", toe)
              }
          }
        }
      }
    }
    def processBlocking(replay: Iterator[Transaction]): Option[Long] = {
        def consume(txn: Transaction) = try {
          consumer consumeReplay txn
        } catch {
          case e: Exception =>
            throw new IllegalStateException(s"${consumer.getClass.getName} failed to process $txn", e)
        }
      Threads.Blocking.execute(awaiter)
      consumerCtx match {
        case ctx: HashBasedSerialExecutionContext =>
          replay.foreach { txn =>
            awaitQueue put txn -> ctx.submit(txn.streamId.hashCode)(consume(txn))
            lastTime = txn.timestamp
          }
        case ctx =>
          replay.foreach { txn =>
            awaitQueue put txn -> Future(consume(txn))(ctx)
            lastTime = txn.timestamp
          }
      }
      doneReading = true
      if (maxReplayConsumptionWait.isFinite) {
        if (!awaiterLatch.await(maxReplayConsumptionWait.length, maxReplayConsumptionWait.unit)) {
          throw new TimeoutException(s"Replay processing exceeded $maxReplayConsumptionWait")
        }
      } else {
        awaiterLatch.await()
      }
      if (lastTime == -1L) None else Some(lastTime)
    }

  }

  private class LiveConsumerProxy(consumer: DurableConsumer#LiveConsumer) extends (Transaction ⇒ Unit) {
    def apply(txn: Transaction) = consumer.consumeLive(txn)
  }

  private[this] val pendingReplays = new LockFreeConcurrentMap[ID, ScheduledFuture[_]]

  private def LiveConsumerProxy(consumer: DurableConsumer#LiveConsumer) =
    new LiveConsumerProxy(consumer) with util.FailSafeTransactionHandler[ID, EVT, CAT] with util.SequencedTransactionHandler[ID, EVT, CAT] with util.AsyncTransactionHandler[ID, EVT, CAT] { self: LiveConsumerProxy =>
      def asyncTransactionCtx = consumerCtx
      def onGapDetected(id: ID, expectedRev: Int, actualRev: Int) {
        if (!pendingReplays.contains(id)) {
          val replayer = new Runnable {
            def run = es.replayStreamRange(id, expectedRev until actualRev)(_.foreach(self))
          }
          val futureReplayer = EventStream.schedule(replayer, gapReplayDelay)
          if (pendingReplays.putIfAbsent(id, futureReplayer).isDefined) futureReplayer.cancel(false)
        }
      }
      def onGapClosed(id: ID) {
        pendingReplays.get(id) match {
          case Some(futureReplayer) ⇒
            futureReplayer.cancel(false)
            pendingReplays.remove(id, futureReplayer)
          case _ ⇒ // Ignore
        }
      }
      def expectedRevision(streamId: ID): Int = consumer.expectedRevision(streamId)
      def isFailed(streamId: ID) = _failedStreams.contains(streamId)
      def markFailed(streamId: ID, cat: CAT, t: Throwable) {
        _failedStreams.update(streamId, cat -> t)
        consumerCtx.reportFailure(t)
      }
    }

  /**
   *  Resume consumption.
   *  @return Live subscription. The future is resolved once consumption goes live.
   */
  def resume(consumer: DurableConsumer): Future[Subscription] = {
      implicit def ec = Threads.PiggyBack
    val starting = System.currentTimeMillis
    val categorySet = consumer.categoryFilter
      def categoryFilter(cat: CAT) = categorySet.isEmpty || categorySet.contains(cat)
      def replayConsumer(txns: Iterator[Transaction]): Option[Long] = {
        val replayConsumer = new BlockingReplayProxy(consumer)
        replayConsumer.processBlocking(txns)
      }
    val replayFinished: Future[Option[Long]] = consumer.lastTimestamp match {
      case None ⇒ es.replay(categorySet.toSeq: _*)(replayConsumer)
      case Some(lastTime) ⇒
        val replaySince = new Timestamp(lastTime - maxClockSkew.toMillis)
        es.replayFrom(replaySince, categorySet.toSeq: _*)(replayConsumer)
    }
    val futureSub = replayFinished.flatMap { lastTime ⇒
      if (_failedStreams.nonEmpty) {
        throw new EventStream.StreamsReplayFailure(_failedStreams.snapshot)
      }
      val liveConsumer = LiveConsumerProxy(consumer.onLive())
      val sub = es.subscribe(liveConsumer, categoryFilter)
      val replaySince = new Timestamp(lastTime.getOrElse(starting) - maxClockSkew.toMillis)
      // Close the race condition; replay anything missed between replay and subscription
      es.replayFrom(replaySince, categorySet.toSeq: _*)(_.foreach(liveConsumer)).map(_ ⇒ sub)
    }
    futureSub.onFailure {
      case t => consumerCtx.reportFailure(t)
    }
    futureSub
  }
}

object EventStream {
  import java.util.concurrent._

  final class StreamsReplayFailure[ID, CAT](val failures: Map[ID, (CAT, Throwable)]) extends IllegalStateException(s"${failures.size} streams failed processing during replay")

  private val ConsumerThreadFactory = Threads.factory(classOf[EventStream[Any, Any, Any]#DurableConsumer].getName)

  private def schedule(r: Runnable, dur: Duration) = Threads.DefaultScheduler.schedule(r, dur.toMillis, TimeUnit.MILLISECONDS)

  def serializedStreams[ID, EVT, CAT](
    numThreads: Int,
    es: EventSource[ID, EVT, CAT],
    replayBufferSize: Int,
    gapReplayDelay: FiniteDuration,
    maxClockSkew: FiniteDuration,
    maxReplayConsumptionWait: Duration,
    failureReporter: Throwable ⇒ Unit = (t) ⇒ t.printStackTrace(System.err)) = {
    val consumerCtx = numThreads match {
      case 1 ⇒ ExecutionContext.fromExecutor(Threads.newSingleThreadExecutor(EventStream.ConsumerThreadFactory, t => ()), failureReporter)
      case n ⇒ HashBasedSerialExecutionContext(n, EventStream.ConsumerThreadFactory, failureReporter)
    }
    new EventStream(es, consumerCtx, replayBufferSize, gapReplayDelay, maxClockSkew, maxReplayConsumptionWait)
  }
}
