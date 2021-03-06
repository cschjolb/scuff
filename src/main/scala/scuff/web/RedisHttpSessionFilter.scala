package scuff.web

import scuff.redis.RedisConnectionPool
import javax.servlet._
import javax.servlet.http._
import scuff.JavaSerializer

/**
 * Given a specific Redis database, this filter stores any session
 * data in Redis, making it available in a horizontally
 * scaled environment.
 */
abstract class RedisHttpSessionFilter extends Filter {

  def redisSessionDB: RedisConnectionPool

  def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) = httpFilter(req, res, chain)

  @inline
  private def httpFilter(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
    val request = new HttpServletRequestProxy(req) {
      private[this] lazy val session = new RedisHttpSession(redisSessionDB, req.getSession)
      override def getSession = session
      override def getSession(create: Boolean) =
        if (create || req.getSession(false) != null) {
          session
        } else {
          null
        }
    }
    chain.doFilter(request, res)
  }
}

private class RedisHttpSession(redis: RedisConnectionPool, delegate: HttpSession, keyPrefix: String = "HttpSession") extends HttpSession {
  import scuff.redis._
  import collection.JavaConverters._
  private[this] var _map: BinaryRedisHashMap[String, AnyRef] = _
  private def map = {
    if (_map == null) {
      _map = new BinaryRedisHashMap(delegate.getId, redis, new StringSerializer(keyPrefix), JavaSerializer)
    }
    _map
  }
  def getAttribute(attr: String) = map.getOrElse(attr, null)
  def getAttributeNames() = map.keysIterator.asJavaEnumeration
  def getCreationTime() = delegate.getCreationTime()
  def getId() = delegate.getId()
  val getLastAccessedTime = System.currentTimeMillis()
  def getMaxInactiveInterval() = delegate.getMaxInactiveInterval()
  def getServletContext() = delegate.getServletContext()
  def invalidate() = {
    delegate.invalidate()
    if (_map != null) _map.clear()
  }
  def isNew() = delegate.isNew()
  def removeAttribute(attr: String) = map.del(attr)
  def setAttribute(attr: String, value: AnyRef) = map.set(attr, value)
  def setMaxInactiveInterval(max: Int) = delegate.setMaxInactiveInterval(max)

  def getSessionContext() = sys.error("Deprecated and you know it")
  def getValue(name: String) = sys.error("Deprecated and you know it")
  def getValueNames() = sys.error("Deprecated and you know it")
  def putValue(n: String, v: Object) = sys.error("Deprecated and you know it")
  def removeValue(n: String) = sys.error("Deprecated and you know it")
}
