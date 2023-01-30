package replication.calendar

import kofre.base.{Id, Lattice}
import kofre.datatypes.AddWinsSet
import kofre.datatypes.AddWinsSet.syntax
import kofre.datatypes.experiments.RaftState
import kofre.dotted.Dotted
import kofre.syntax.{DeltaBuffer, DeltaBufferDotted, Named}

import scala.util.Random

case class Token(id: Long, owner: Id, value: String) {
  def same(other: Token) = owner == other.owner && value == other.value
}

case class RaftTokens(
    replicaID: Id,
    tokenAgreement: RaftState[Token],
    want: DeltaBufferDotted[AddWinsSet[Token]],
    tokenFreed: DeltaBufferDotted[AddWinsSet[Token]]
) {

  def owned(value: String): List[Token] = {
    val freed  = tokenFreed.elements
    val owners = tokenAgreement.values.filter(t => t.value == value && !freed.contains(t))
    val mine   = owners.filter(_.owner == replicaID)
    // return all ownership tokens if this replica owns the oldest one
    if (mine.headOption == owners.headOption) mine else Nil
  }

  def isOwned(value: String): Boolean = owned(value).nonEmpty

  def acquire(value: String): RaftTokens = {
    val token = Token(Random.nextLong(), replicaID, value)

    // conditional is only an optimization
    if (!(tokenAgreement.values.iterator ++ want.elements.iterator).exists(_.same(token))) {
      copy(want = want.add(token))
    } else this
  }

  def free(value: String): RaftTokens = {
    copy(tokenFreed = tokenFreed.addAll(owned(value)))
  }

  def update(): RaftTokens = {
    val generalDuties = tokenAgreement.supportLeader(replicaID).supportProposal(replicaID)

    if (tokenAgreement.leader == replicaID) {
      val unwanted = want.removeAll(want.elements.filter(generalDuties.values.contains))
      unwanted.elements.headOption match {
        case None => copy(tokenAgreement = generalDuties, want = unwanted)
        case Some(tok) =>
          copy(tokenAgreement = generalDuties.propose(replicaID, tok), want = unwanted)
      }
    } else copy(tokenAgreement = generalDuties)
  }

  def applyWant(state: Named[Dotted[AddWinsSet[Token]]]): RaftTokens = {
    copy(want = want.applyDelta(state.replicaId, state.anon))
  }

  def applyFree(state: Named[Dotted[AddWinsSet[Token]]]): RaftTokens = {
    copy(tokenFreed = tokenFreed.applyDelta(state.replicaId, state.anon))
  }

  def applyRaft(state: RaftState[Token]): RaftTokens = {
    copy(tokenAgreement = Lattice.merge(tokenAgreement, state))
  }

  def lead(): RaftTokens =
    copy(tokenAgreement = tokenAgreement.becomeCandidate(replicaID))

}

object RaftTokens {
  def init(replicaID: Id): RaftTokens =
    RaftTokens(
      replicaID,
      RaftState(Set(replicaID)),
      DeltaBuffer.dotted(replicaID, AddWinsSet.empty[Token]),
      DeltaBuffer.dotted(replicaID, AddWinsSet.empty[Token])
    )
}
