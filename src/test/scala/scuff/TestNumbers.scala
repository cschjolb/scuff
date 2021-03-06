package scuff

import org.junit._
import Assert._
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Arrays

class TestNumbers {
  val r = new scala.util.Random

  @Test
  def `back and forth` {
    for (_ ← 1 to 1000) {
      val arrL1 = new Array[Byte](8)
      val arrI1 = new Array[Byte](4)
      r.nextBytes(arrL1)
      r.nextBytes(arrI1)
      val l = Numbers.bytesToLong(arrL1)
      val i = Numbers.bytesToInt(arrI1)
      val arrL2 = Numbers.longToBytes(l)
      val arrI2 = Numbers.intToBytes(i)
      assertTrue(Arrays.equals(arrL1, arrL2))
      assertTrue(Arrays.equals(arrI1, arrI2))
    }
  }

  @Test
  def `forth and back` {
    for (_ ← 1 to 1000) {
      val l1 = r.nextLong
      val i1 = r.nextInt
      val arrL = Numbers.longToBytes(l1)
      val arrI = Numbers.intToBytes(i1)
      val l2 = Numbers.bytesToLong(arrL)
      val i2 = Numbers.bytesToInt(arrI)
      assertEquals(l1, l2)
      assertEquals(i1, i2)
    }
  }

  @Test
  def long2bytes {
    for (_ ← 1 to 1000) {
      val arrL = new Array[Byte](8)
      val arrI = new Array[Byte](4)
      val l = r.nextLong
      val i = r.nextInt
      val bbL = ByteBuffer.allocate(8)
      val bbI = ByteBuffer.allocate(4)
      bbL.putLong(l)
      Numbers.longToBytes(l, arrL)
      bbI.putInt(i)
      Numbers.intToBytes(i, arrI)
      assertTrue(java.util.Arrays.equals(bbL.array, arrL))
      assertTrue(java.util.Arrays.equals(bbI.array, arrI))
    }
  }
  @Test
  def bytes2long {
    for (_ ← 1 to 1000) {
      val arrL = new Array[Byte](8)
      val arrI = new Array[Byte](4)
      r.nextBytes(arrL)
      r.nextBytes(arrI)
      val bbLong = ByteBuffer.wrap(arrL).getLong()
      val bbInt = ByteBuffer.wrap(arrI).getInt()
      val arrLong = Numbers.bytesToLong(arrL)
      val arrInt = Numbers.bytesToInt(arrI)
      assertEquals(bbLong, arrLong)
      assertEquals(bbInt, arrInt)
    }
  }

  @Test
  def parsing {
    assertEquals(987065L, "987065".unsafeLong())
    assertEquals(987065, "987065".unsafeInt())
    assertEquals(987065L, "x987065".unsafeLong(offset = 1))
    assertEquals(987065, "5987065".unsafeInt(offset = 1))
    assertEquals(987065L, "987065x".unsafeLong(Numbers.NonDigit))
    assertEquals(987065, "987065/".unsafeInt(Numbers.NonDigit))

  }

}