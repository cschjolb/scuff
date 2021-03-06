package scuff

import org.junit._
import org.junit.Assert._

class TestPubSub {

  class Event

  var pubSub: PubSub[Event, Event] = _

  @Before
  def setup {
    pubSub = new PubSub[Event, Event](concurrent.ExecutionContext.global)
  }

  @Test(timeout = 2000)
  def exceptional {
    val countDown = new java.util.concurrent.CountDownLatch(6)
    val exceptions = collection.mutable.Buffer[Throwable]()
      def errHandler(t: Throwable) {
        exceptions += t
        countDown.countDown()
      }
    val execCtx = concurrent.ExecutionContext.fromExecutor(null, errHandler)
    pubSub = new PubSub[Event, Event](execCtx)
    val l1 = (e: Event) ⇒ throw new RuntimeException
    pubSub.subscribe(l1)
    val l2 = (e: Event) ⇒ countDown.countDown()
    pubSub.subscribe(l2)
    val l3 = (e: Event) ⇒ countDown.countDown()
    pubSub.subscribe(l3)
    pubSub.publish(new Event)
    pubSub.publish(new Event)
    countDown.await()
    assertEquals(2, exceptions.size)
  }
}