package scuff.eventual.util

import scuff._
import java.util.Date
import concurrent._
import scala.util._
import java.util.concurrent.TimeUnit
import scala.annotation.implicitNotFound
import language.implicitConversions

/**
 * Non-persistent implementation, probably only useful for testing.
 */
@implicitNotFound("Cannot find an implicit ExecutionContext, either import scala.concurrent.ExecutionContext.Implicits.global or use a custom one")
abstract class InMemoryEventStore[ID, EVT, CAT](implicit execCtx: ExecutionContext) extends eventual.EventStore[ID, EVT, CAT] {

  implicit private[this] val Millis = TimeUnit.MILLISECONDS
  protected def clock: Clock

  implicit protected[this] def txn2cat(txn: Transaction): CAT

  private[this] val pubSub = new PubSub[CAT, Transaction](execCtx)

  def subscribe(subscriber: Transaction ⇒ Unit, filter: CAT ⇒ Boolean) = pubSub.subscribe(subscriber, filter)

  protected def publish(txn: Transaction) = pubSub.publish(txn)

  private[this] val txnList = collection.mutable.Buffer[Transaction]()

  private def findCurrentRevision(id: ID): Option[Int] = {
    if (txnList.isEmpty) {
      None
    } else {
      Some(txnList.view.filter(_.streamId == id).map(_.revision).max)
    }
  }

  def exists(stream: ID): Future[Boolean] = txnList.synchronized(Future.successful(txnList.find(_.streamId == stream).isDefined))

  def record(category: CAT, streamId: ID, revision: Int, events: List[_ <: EVT], metadata: Map[String, String]): Future[Transaction] = Future {
    txnList.synchronized {
      val nextExpectedRevision = findCurrentRevision(streamId).getOrElse(-1) + 1
      if (revision == nextExpectedRevision) {
        val txn = new Transaction(clock.now, category, streamId, revision, metadata, events)
        txnList += txn
        txn
      } else if (nextExpectedRevision > revision) {
        throw new eventual.DuplicateRevisionException(streamId, revision)
      } else {
        throw new IllegalStateException
      }
    }
  }.andThen {
    case Success(txn) ⇒ publish(txn)
  }
  def append(category: CAT, streamId: ID, events: List[_ <: EVT], metadata: Map[String, String]): Future[Transaction] = Future {
    txnList.synchronized {
      val revision = findCurrentRevision(streamId).getOrElse(-1) + 1
      val txn = new Transaction(clock.now, category, streamId, revision, metadata, events)
      txnList += txn
      txn
    }
  }.andThen {
    case Success(txn) ⇒ publish(txn)
  }
  def replayStream[T](stream: ID)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      callback(txnList.iterator.withFilter(t ⇒ t.streamId == stream))
    }
  }
  def replayStreamSince[T](stream: ID, sinceRevision: Int)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      callback(txnList.iterator.withFilter(t ⇒ t.streamId == stream && t.revision > sinceRevision))
    }
  }
  def replayStreamTo[T](stream: ID, toRevision: Int)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      callback(txnList.iterator.withFilter(t ⇒ t.streamId == stream && t.revision <= toRevision))
    }
  }
  def replayStreamRange[T](stream: ID, revisionRange: collection.immutable.Range)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      callback(txnList.iterator.withFilter(t ⇒ t.streamId == stream && revisionRange.contains(t.revision)))
    }
  }
  def replay[T](categories: CAT*)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      val iter = if (categories.isEmpty) {
        txnList.iterator
      } else {
        val catSet = categories.toSet
        txnList.iterator.withFilter(txn ⇒ catSet.contains(txn.category))
      }
      callback(iter)
    }
  }
  def replayFrom[T](fromTime: Date, categories: CAT*)(callback: Iterator[Transaction] ⇒ T): Future[T] = Future {
    txnList.synchronized {
      val iter = if (categories.isEmpty) {
        txnList.iterator.withFilter(_.timestamp >= fromTime.getTime)
      } else {
        val catSet = categories.toSet
        txnList.iterator.withFilter(txn ⇒ catSet.contains(txn.category) && txn.timestamp >= fromTime.getTime)
      }
      callback(iter)
    }
  }
}