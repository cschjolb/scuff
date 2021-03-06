package scuff.ddd

import scala.concurrent.Future
import scuff.Threads
import scala.annotation.implicitNotFound

/**
 * Aggregate root repository.
 */
trait Repository[AR <: AggregateRoot] {

  def exists(id: AR#ID): Future[Boolean]

  /**
   * Load aggregate. Only for reading the aggregate.
   * Any modifications cannot be saved.
   * @param id The instance ID
   * @param revision Load a specific revision, or None for most current (default)
   * @return The requested revision or [[scuff.ddd.UnknownIdException]]
   */
  def load(id: AR#ID, revision: Option[Int] = None): Future[AR]
  final def load(id: (AR#ID, Int)): Future[AR] = load(id._1, Some(id._2))
  final def load(id: AR#ID, revision: Int): Future[AR] = load(id, Some(revision))

  /**
   * Update aggregate. Changes are committed and potential revision conflicts are
   * automatically retried. To back out of the transaction, throw an exception.
   * NOTICE: Anything done in this block must be thread-safe and idempotent,
   * due to the automatic retry on concurrent updates.
   * @param id The aggregate ID
   * @param basedOnRevision Revision, which update will be based on
   * @param updateBlock The transaction code block. This may be executed multiple times if concurrent updates occur
   */
  @implicitNotFound("Cannot find implicit Map[String, String] of metadata. If no metadata desired, provide empty")
  final def update[T](id: AR#ID, basedOnRevision: Int)(updateBlock: AR ⇒ Future[T])(implicit metadata: Map[String, String]): Future[Updated[T]] = {
    update(id, basedOnRevision, metadata) { aggr ⇒
      updateBlock(aggr).map { t =>
        if (aggr.events.nonEmpty) try {
          aggr.checkInvariants()
        } catch {
          case e: Exception => throw new InvariantFailure(id, aggr.events, e)
        }
        t
      }(Threads.PiggyBack)
    }
  }
  
  private[this] final val EventPrefix = s"${compat.Platform.EOL}\t * "
  class InvariantFailure(aggr: AR#ID, events: List[DomainEvent], cause: Exception)
    extends RuntimeException(s"""Aggregate $aggr invariant failure: "${cause.getMessage}" after applying events:${events.mkString(EventPrefix, EventPrefix, "")}""", cause)

  final class Updated[T] (val revision: Int, val output: T)

  @implicitNotFound("Cannot find implicit Map[String, String] of metadata. If no metadata desired, define as empty: implicit metadata = Map.empty[String, String]")
  final def update[T](id: AR#ID, basedOnRevision: Option[Int])(updateBlock: AR ⇒ Future[T])(implicit metadata: Map[String, String]): Future[Updated[T]] =
    basedOnRevision match {
      case Some(revision) ⇒ update(id, revision)(updateBlock)
      case _ ⇒ update(id, Int.MaxValue)(updateBlock)
    }
  protected def update[T](id: AR#ID, basedOnRevision: Int, metadata: Map[String, String])(updateBlock: AR ⇒ Future[T]): Future[Updated[T]]

  /**
   * Insert new aggregate root and publish committed events.
   * <p>NOTICE: No commands should be applied to the aggregate instance
   * after it's been saved, as it can neither be persisted.
   * @param aggr Aggregate root to save
   * @return Aggregate instance or [[scuff.ddd.DuplicateIdException]] if the ID is already used
   * or [[IllegalStateException]] if the instance has a revision number
   */
  @implicitNotFound("Cannot find implicit Map[String, String] of metadata. If no metadata desired, define as empty: implicit metadata = Map.empty[String, String]")
  final def insert(aggr: AR)(implicit metadata: Map[String, String]): Future[AR#ID] = {
    try {
      if (aggr.events.nonEmpty) aggr.checkInvariants()
      insert(metadata, aggr)
    } catch {
      case e: Exception ⇒ Future.failed(e)
    }
  }
  protected def insert(metadata: Map[String, String], aggr: AR): Future[AR#ID]
}

class UnknownIdException(val id: Any) extends RuntimeException("Unknown aggregate: " + id)
class DuplicateIdException(val id: Any) extends RuntimeException("Aggregate is already saved: " + id)
