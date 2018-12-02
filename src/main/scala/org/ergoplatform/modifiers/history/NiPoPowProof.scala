package org.ergoplatform.modifiers.history

import com.google.common.primitives.{Bytes, Ints}
import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.settings.Algos
import scorex.core.ModifierTypeId
import scorex.core.serialization.Serializer
import scorex.core.validation.ModifierValidator
import scorex.util.ModifierId

import scala.util.Try

case class NiPoPowProof(m: Int,
                        k: Int,
                        prefix: Seq[Header],
                        suffix: Seq[Header],
                        sizeOpt: Option[Int] = None)
  extends ErgoPersistentModifier with ModifierValidator {

  import NiPoPowAlgos._

  override type M = NiPoPowProof

  override val modifierTypeId: ModifierTypeId = NiPoPowProof.TypeId

  override def serializedId: Array[Byte] = Algos.hash(bytes)

  override def serializer: Serializer[NiPoPowProof] = NiPoPowProofSerializer

  override def parentId: ModifierId = prefix.head.id

  def headersOfLevel(l: Int): Seq[Header] = prefix.filter(maxLevelOf(_) >= l)

  def validate: Try[Unit] = {
    failFast
      .demand(suffix.lengthCompare(k) == 0, "Invalid suffix length")
      .demand(prefix.tail.groupBy(maxLevelOf).forall(_._2.lengthCompare(m) == 0), "Invalid prefix length")
      .demand(prefix.tail.forall(_.interlinks.headOption.contains(prefix.head.id)), "Chain is not anchored")
      .result
      .toTry
  }

  def isBetterThan(that: NiPoPowProof): Boolean = {
    val (thisDivergingChain, thatDivergingChain) = lowestCommonAncestor(prefix, that.prefix)
      .map(h => prefix.filter(_.height > h.height) -> that.prefix.filter(_.height > h.height))
      .getOrElse(prefix -> that.prefix)
    bestArg(thisDivergingChain)(m) > bestArg(thatDivergingChain)(m)
  }

}

object NiPoPowProof {
  val TypeId: ModifierTypeId = ModifierTypeId @@ (110: Byte)
}

object NiPoPowProofSerializer extends Serializer[NiPoPowProof] {

  override def toBytes(obj: NiPoPowProof): Array[Byte] = {
    def serializeChain(chain: Seq[Header]) = Ints.toByteArray(chain.size) ++
      Bytes.concat(chain.map(h => Ints.toByteArray(h.bytes.length) ++ h.bytes): _*)
    Bytes.concat(
      Ints.toByteArray(obj.k),
      Ints.toByteArray(obj.m),
      serializeChain(obj.prefix),
      serializeChain(obj.suffix)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[NiPoPowProof] = Try {
    import cats.implicits._
    val k = Ints.fromByteArray(bytes.take(4))
    val m = Ints.fromByteArray(bytes.slice(4, 8))
    val prefixSize = Ints.fromByteArray(bytes.slice(8, 12))
    val (prefixTryList, suffixBytes) = (0 until prefixSize)
      .foldLeft((List.empty[Try[Header]], bytes.drop(12))) {
        case ((acc, leftBytes), _) =>
          val headerLen = Ints.fromByteArray(leftBytes.take(4))
          val headerTry = HeaderSerializer.parseBytes(leftBytes.slice(4, 4 + headerLen))
          (acc :+ headerTry, leftBytes.drop(4 + headerLen))
      }
    val suffixSize = Ints.fromByteArray(suffixBytes.take(4))
    val suffixTryList = (0 until suffixSize)
      .foldLeft((List.empty[Try[Header]], suffixBytes.drop(4))) {
        case ((acc, leftBytes), _) =>
          val headerLen = Ints.fromByteArray(leftBytes.take(4))
          val headerTry = HeaderSerializer.parseBytes(leftBytes.slice(4, 4 + headerLen))
          (acc :+ headerTry, leftBytes.drop(4 + headerLen))
      }._1
    val prefixTry: Try[List[Header]] = prefixTryList.sequence
    val suffixTry: Try[List[Header]] = suffixTryList.sequence
    prefixTry.flatMap(prefix => suffixTry.map(suffix => NiPoPowProof(k, m, prefix, suffix, Some(bytes.length))))
  }.flatten

}