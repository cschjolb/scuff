package scuff.web

import javax.servlet._
import http._
import HttpServletResponse._
import scuff.LRUHeapCache

private object HttpCaching {
  case class Cached(bytes: Array[Byte], lastModified: Option[Long], headers: Iterable[(String, Iterable[String])], contentType: String, encoding: String, locale: java.util.Locale) {
    lazy val eTag = {
      val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
      "\"" + scuff.BitsBytes.hexEncode(digest) + "\""
    }
    def flushTo(res: HttpServletResponse) {
      for ((name, values) ← headers; value ← values) res.addHeader(name, value)
      res.setContentType(contentType)
      res.setCharacterEncoding(encoding)
      res.setLocale(locale)
      res.setContentLength(bytes.length)
      res.getOutputStream().write(bytes)
      res.flushBuffer()
    }
  }
}

private[web] sealed trait HttpCaching {
  import HttpCaching._

  private[this] lazy val defaultCache = new LRUHeapCache[Any, Any](Int.MaxValue, 0)
  protected def cache: scuff.Cache[Any, Any] = defaultCache
  private def theCache = cache.asInstanceOf[scuff.Cache[Any, Cached]]
  protected def makeCacheKey(req: HttpServletRequest): Option[Any]

  private def fetchResource(res: HttpServletResponse, fetch: HttpServletResponse ⇒ Unit): Cached = {
    import collection.JavaConverters._
    val proxy = new HttpServletResponseProxy(res)
    fetch(proxy)
    val lastMod = proxy.getDateHeaders(LastModified).headOption
    new Cached(proxy.getBytes, lastMod, proxy.headers.values, proxy.getContentType, proxy.getCharacterEncoding, proxy.getLocale)
  }
  protected[web] def respond(cacheKey: Any, req: HttpServletRequest, res: HttpServletResponse)(getResource: HttpServletResponse ⇒ Unit) {
    val cached = theCache.lookupOrStore(cacheKey)(fetchResource(res, getResource))
    val expectedETag = Option(req.getHeader(IfNoneMatch))
    val expectedLastMod = req.getDateHeader(IfModifiedSince)
    if (cached.lastModified.exists(_ == expectedLastMod) || expectedETag.exists(_ == cached.eTag)) {
      res.setStatus(SC_NOT_MODIFIED)
    } else {
      cached.flushTo(res)
    }
  }
}

trait HttpCachingServletMixin extends HttpServlet with HttpCaching {
  import HttpCaching._

  abstract override def destroy {
    cache.disable()
    super.destroy()
  }

  abstract override def service(req: HttpServletRequest, res: HttpServletResponse) {
    makeCacheKey(req) match {
      case Some(cacheKey) ⇒ respond(cacheKey, req, res) { res ⇒ super.service(req, res) }
      case None ⇒ super.service(req, res)
    }
  }
}

trait HttpCachingFilterMixin extends Filter with HttpCaching {
  import HttpCaching._

  abstract override def destroy {
    cache.disable()
    super.destroy()
  }

  abstract override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) = (req, res) match {
    case (req: HttpServletRequest, res: HttpServletResponse) ⇒ makeCacheKey(req) match {
      case Some(cacheKey) ⇒ respond(cacheKey, req, res) { res ⇒ super.doFilter(req, res, chain) }
      case None ⇒ super.doFilter(req, res, chain)
    }
    case _ ⇒ chain.doFilter(req, res)
  }
}
