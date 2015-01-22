/*
 Copyright 2014 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.twitter.scalding.macros.impl.ordser

import scala.reflect.macros.Context
import scala.language.experimental.macros

import com.twitter.scalding._
import com.twitter.scalding.serialization.OrderedSerialization

sealed trait LengthTypes[C <: Context]
// Repesents an Int returning
object FastLengthCalculation {
  def apply[C <: Context](c: Context)(tree: c.Tree): FastLengthCalculation[c.type] =
    new FastLengthCalculation[c.type] {
      override val ctx: c.type = c
      override val t: c.Tree = tree
    }
}

trait FastLengthCalculation[C <: Context] extends LengthTypes[C] {
  val ctx: C
  def t: ctx.Tree
}

// Repesents an Option[Either[Int, Int]] returning
object MaybeLengthCalculation {
  def apply[C <: Context](c: Context)(tree: c.Tree): MaybeLengthCalculation[c.type] =
    new MaybeLengthCalculation[c.type] {
      override val ctx: c.type = c
      override val t: c.Tree = tree
    }
}

trait MaybeLengthCalculation[C <: Context] extends LengthTypes[C] {
  val ctx: C
  def t: ctx.Tree
}

object ConstantLengthCalculation {
  def apply(c: Context)(intArg: Int): ConstantLengthCalculation[c.type] =
    new ConstantLengthCalculation[c.type] {
      override val toInt = intArg
    }
}

trait ConstantLengthCalculation[C <: Context] extends LengthTypes[C] {
  def toInt: Int
}

object NoLengthCalculationAvailable {

  def apply(c: Context): NoLengthCalculationAvailable[c.type] = {
    new NoLengthCalculationAvailable[c.type] {}
  }
}
// represents an Array[Byte] returning
trait NoLengthCalculationAvailable[C <: Context] extends LengthTypes[C]

object TreeOrderedBuf {
  def toOrderedSerialization[T](c: Context)(t: TreeOrderedBuf[c.type])(implicit T: t.ctx.WeakTypeTag[T]): t.ctx.Expr[OrderedSerialization[T]] = {
    import t.ctx.universe._
    def freshT(id: String) = newTermName(c.fresh(s"fresh_$id"))
    val outputLength = freshT("outputLength")

    val innerLengthFn: Tree = {
      val element = freshT("element")

      val tempLen = freshT("tempLen")
      val lensLen = freshT("lensLen")

      val fnBodyOpt = t.length(q"$element") match {
        case _: NoLengthCalculationAvailable[_] => None
        case const: ConstantLengthCalculation[_] => None
        case f: FastLengthCalculation[_] => Some(q"""
        Option[Either[Int,Int]](Right[Int, Int](${f.asInstanceOf[FastLengthCalculation[c.type]].t}))
        """)
        case m: MaybeLengthCalculation[_] => Some(m.asInstanceOf[MaybeLengthCalculation[c.type]].t)
      }

      fnBodyOpt.map { fnBody =>
        q"""
        private[this] def payloadLength($element: $T): Option[Either[Int,Int]] = {
          $fnBody
        }
        """
      }.getOrElse(q"")
    }

    def binaryLengthGen(typeName: Tree) = {
      val tempLen = freshT("tempLen")
      val lensLen = freshT("lensLen")
      val element = freshT("element")
      val callDynamic = q"""

      override def staticSize: Option[Int] = None
      override def dynamicSize($element: $typeName): Option[Int] = {
        payloadLength($element).map { lenEither =>
          val $tempLen = lenEither match {
            case Left(l) => l
            case Right(r) => r
          }
          val $lensLen = sizeBytes($tempLen)
          $tempLen + $lensLen
         }: Option[Int]
     }
      """
      t.length(q"$element") match {
        case _: NoLengthCalculationAvailable[_] => q"""
          override def staticSize: Option[Int] = None
          override def dynamicSize($element: $typeName): Option[Int] = None
        """
        case const: ConstantLengthCalculation[_] => q"""
          override def staticSize: Option[Int] = Some(${const.toInt})
          override def dynamicSize($element: $typeName): Option[Int] = Some(${const.toInt})
          """
        case f: FastLengthCalculation[_] => callDynamic
        case m: MaybeLengthCalculation[_] => callDynamic
      }
    }

    def putFnGen(outerbaos: TermName, element: TermName) = {
      val tmpArray = freshT("tmpArray")
      val len = freshT("len")
      val oldPos = freshT("oldPos")
      val noLenCalc = q"""

      val $tmpArray = {
        val baos = new _root_.java.io.ByteArrayOutputStream
        innerPutNoLen(baos, $element)
        baos.toByteArray
      }

      val $len = $tmpArray.size
      $outerbaos.writeSize($len)
      $outerbaos.writeBytes($tmpArray)
      """

      def withLenCalc(lenC: Tree) = q"""
        val $len = $lenC
        $outerbaos.writeSize($len)
        innerPutNoLen($outerbaos, $element)
      """

      t.length(q"$element") match {
        case _: NoLengthCalculationAvailable[_] => noLenCalc
        case _: ConstantLengthCalculation[_] => q"""
        innerPutNoLen($outerbaos, $element)
        """
        case f: FastLengthCalculation[_] =>
          withLenCalc(f.asInstanceOf[FastLengthCalculation[c.type]].t)
        case m: MaybeLengthCalculation[_] =>
          val tmpLenRes = freshT("tmpLenRes")
          q"""
            def noLenCalc = {
              $noLenCalc
            }
            def withLenCalc(cnt: Int) = {
              ${withLenCalc(q"cnt")}
            }
            val $tmpLenRes: Option[Either[Int, Int]] = payloadLength($element)
            $tmpLenRes match {
              case None => noLenCalc
              case Some(Left(const)) => withLenCalc(const)
              case Some(Right(s)) => withLenCalc(s)
            }
        """
      }
    }

    def readLength(inputStream: TermName) = {
      t.length(q"e") match {
        case const: ConstantLengthCalculation[_] => q"${const.toInt}"
        case _ => q"$inputStream.readSize"
      }
    }

    val lazyVariables = t.lazyOuterVariables.map {
      case (n, t) =>
        val termName = newTermName(n)
        q"""lazy val $termName = $t"""
    }

    val bb = freshT("byteArrayInputStream")
    val element = freshT("element")

    val inputStreamA = freshT("inputStreamA")
    val inputStreamB = freshT("inputStreamB")

    val bufferedStreamA = freshT("bufferedStreamA")
    val bufferedStreamB = freshT("bufferedStreamB")

    val lenA = freshT("lenA")
    val lenB = freshT("lenB")

    t.ctx.Expr[OrderedSerialization[T]](q"""
      new _root_.com.twitter.scalding.serialization.OrderedSerialization[$T] with _root_.com.twitter.bijection.macros.MacroGenerated  {
        import com.twitter.scalding.serialization.JavaStreamEnrichments._
        ..$lazyVariables

        def compareBinary($inputStreamA: _root_.java.io.InputStream, $inputStreamB: _root_.java.io.InputStream): _root_.com.twitter.scalding.serialization.OrderedSerialization.Result = {
          try {

            val $lenA = ${readLength(inputStreamA)}
            require($lenA >= 0, "Length was " + $lenA + "which is < 0, invalid")
            val $lenB = ${readLength(inputStreamB)}
            require($lenB >= 0, "Length was " + $lenB + "which is < 0, invalid")


            val subSetRes = if(($lenA == $lenB) && $lenA > 24 && $inputStreamA.markSupported && $inputStreamB.markSupported) {
              $inputStreamA.mark($lenA)
              $inputStreamB.mark($lenB)

              var pos = 0
              var isSame = true

              while(pos < $lenA && isSame == true) {
                $inputStreamA.readByte == $inputStreamB.readByte
                pos = pos + 1
              }

              if(isSame) {
                0
              } else {
                // rewind if they don't match for doing the full compare
                $inputStreamA.reset()
                $inputStreamA.reset()
                -1
              }
            } else {
              -1
            }

            val r = if(subSetRes == 0) {
              0
              } else {
              val $bufferedStreamA = _root_.com.twitter.scalding.serialization.PositionInputStream($inputStreamA)
              val initialPositionA = $bufferedStreamA.position
              val $bufferedStreamB = _root_.com.twitter.scalding.serialization.PositionInputStream($inputStreamB)
              val initialPositionB = $bufferedStreamB.position

              val innerR = ${t.compareBinary(bufferedStreamA, bufferedStreamB)}

              $bufferedStreamA.seekToPosition(initialPositionA + $lenA)
              $bufferedStreamB.seekToPosition(initialPositionB + $lenB)
              innerR
            }

            _root_.com.twitter.scalding.serialization.OrderedSerialization.resultFrom(r)
            }
            catch { case _root_.scala.util.control.NonFatal(e) =>
              _root_.com.twitter.scalding.serialization.OrderedSerialization.CompareFailure(e)
            }
          }

        def hash(passedInObjectToHash: $T): Int = {
          ${t.hash(newTermName("passedInObjectToHash"))}
        }

        $innerLengthFn

        ${binaryLengthGen(q"$T")}


        override def read(from: _root_.java.io.InputStream): _root_.scala.util.Try[$T] = {
          try {
              ${readLength(newTermName("from"))}
             _root_.scala.util.Success(${t.get(newTermName("from"))})
          } catch { case _root_.scala.util.control.NonFatal(e) =>
            _root_.scala.util.Failure(e)
          }
        }

        private[this] final def innerPutNoLen(into: _root_.java.io.OutputStream, e: $T) {
          ${t.put(newTermName("into"), newTermName("e"))}
        }

        override def write(into: _root_.java.io.OutputStream, e: $T): _root_.scala.util.Try[Unit] = {
          try {
              ${putFnGen(newTermName("into"), newTermName("e"))}
              _root_.com.twitter.scalding.serialization.Serialization.successUnit
          } catch { case _root_.scala.util.control.NonFatal(e) =>
            _root_.scala.util.Failure(e)
          }
        }

        def compare(x: $T, y: $T): Int = {
          ${t.compare(newTermName("x"), newTermName("y"))}
        }
      }
    """)
  }
}

abstract class TreeOrderedBuf[C <: Context] {
  val ctx: C
  val tpe: ctx.Type
  // Expected byte buffers to be in values a and b respestively, the tree has the value of the result
  def compareBinary(inputStreamA: ctx.TermName, inputStreamB: ctx.TermName): ctx.Tree
  // expects the thing to be tested on in the indiciated TermName
  def hash(element: ctx.TermName): ctx.Tree

  // Place input in param 1, tree to return result in param 2
  def get(inputStreamA: ctx.TermName): ctx.Tree

  // BB input in param 1
  // Other input of type T in param 2
  def put(inputStream: ctx.TermName, element: ctx.TermName): ctx.Tree

  def compare(elementA: ctx.TermName, elementB: ctx.TermName): ctx.Tree

  def lazyOuterVariables: Map[String, ctx.Tree]
  // Return the constant size or a tree
  def length(element: ctx.universe.Tree): LengthTypes[ctx.type]

}