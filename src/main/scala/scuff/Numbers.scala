package scuff

import collection.generic.Growable

object Numbers {
  def unsigned(n: Int) = n & 0xFFFFFFFFL
  def unsigned(n: Short) = n & 0xFFFF
  def unsigned(n: Byte) = n & 0xFF
  private[this] val longUnsigner = (2 * BigInt(Long.MaxValue) + 2).underlying()
  def unsigned(value: Long) = {
    if (value < 0)
      new BigInt(java.math.BigInteger.valueOf(value) add longUnsigner)
    else
      BigInt(value)
  }

  @annotation.tailrec
  def hexEncode[T <: Growable[Char] with CharSequence](bytes: Array[Byte], g: T = new StringBuilder, offset: Int = 0): T = {
    if (offset < bytes.length) {
      val uint32 = unsigned(bytes(offset))
      if (uint32 < 16) g += '0'
      g ++= java.lang.Integer.toHexString(uint32)
      hexEncode(bytes, g, offset + 1)
    } else {
      g
    }
  }

  def bytesToLong(arr: Array[Byte], offset: Int = 0): Long =
    (arr(offset): Long) << 56 |
      ((arr(offset + 1): Long) & 0xff) << 48 |
      ((arr(offset + 2): Long) & 0xff) << 40 |
      ((arr(offset + 3): Long) & 0xff) << 32 |
      ((arr(offset + 4): Long) & 0xff) << 24 |
      ((arr(offset + 5): Long) & 0xff) << 16 |
      ((arr(offset + 6): Long) & 0xff) << 8 |
      ((arr(offset + 7): Long) & 0xff)

  def bytesToInt(arr: Array[Byte], offset: Int = 0): Int =
    (arr(offset): Int) << 24 |
      ((arr(offset + 1): Int) & 0xff) << 16 |
      ((arr(offset + 2): Int) & 0xff) << 8 |
      ((arr(offset + 3): Int) & 0xff)

  def longToBytes(long: Long, arr: Array[Byte] = new Array[Byte](8), offset: Int = 0): Array[Byte] = {
    arr(offset) = (long >> 56).asInstanceOf[Byte]
    arr(offset + 1) = (long >> 48).asInstanceOf[Byte]
    arr(offset + 2) = (long >> 40).asInstanceOf[Byte]
    arr(offset + 3) = (long >> 32).asInstanceOf[Byte]
    arr(offset + 4) = (long >> 24).asInstanceOf[Byte]
    arr(offset + 5) = (long >> 16).asInstanceOf[Byte]
    arr(offset + 6) = (long >> 8).asInstanceOf[Byte]
    arr(offset + 7) = (long).asInstanceOf[Byte]
    arr
  }

  def intToBytes(int: Int, arr: Array[Byte] = new Array[Byte](4), offset: Int = 0): Array[Byte] = {
    arr(offset) = (int >> 24).asInstanceOf[Byte]
    arr(offset + 1) = (int >> 16).asInstanceOf[Byte]
    arr(offset + 2) = (int >> 8).asInstanceOf[Byte]
    arr(offset + 3) = (int).asInstanceOf[Byte]
    arr
  }

  trait Stopper {
    def apply(c: Char): Boolean
  }
  object NonStop extends Stopper {
    def apply(c: Char) = false
  }
  object NonDigit extends Stopper {
    def apply(c: Char) = c > '9' || c < '0'
  }

  @annotation.tailrec
  private def parseUnsafeLong(str: String, idx: Int, end: Int, acc: Long)(implicit stop: Stopper): Long = {
    if (idx == end) {
      acc
    } else {
      val c = str.charAt(idx)
      if (stop(c)) {
        acc
      } else {
        parseUnsafeLong(str, idx + 1, end, acc * 10 + (c - '0'))
      }
    }
  }
  def parseUnsafeLong(str: String, start: Int = 0, end: Int = -1)(implicit stop: Stopper = NonStop): Long = {
    val finish = if (end == -1) str.length else end

    if (start > finish) throw new IllegalArgumentException(s"Start index of $start is after end index of $finish")

    parseUnsafeLong(str, start, finish, 0L)
  }

  def parseUnsafeInt(str: String, start: Int = 0, end: Int = -1)(implicit stop: Stopper = NonStop): Int = {
    val finish = if (end == -1) str.length else end

    if (start > finish) throw new IllegalArgumentException(s"Start index of $start is after end index of $finish")

    parseUnsafeInt(str, start, finish, 0)
  }

  @annotation.tailrec
  private def parseUnsafeInt(str: String, idx: Int, end: Int, acc: Int)(implicit stop: Stopper): Int = {
    if (idx == end) {
      acc
    } else {
      val c = str.charAt(idx)
      if (stop(c)) {
        acc
      } else {
        parseUnsafeInt(str, idx + 1, end, acc * 10 + (c - '0'))
      }
    }
  }

}
