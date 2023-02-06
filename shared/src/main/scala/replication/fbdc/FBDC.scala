package replication.fbdc

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import de.rmgk.options.{Argument, Style}
import kofre.base.{Bottom, Id, Lattice}
import kofre.datatypes.alternatives.ObserveRemoveSet
import kofre.datatypes.{AddWinsSet, CausalQueue, LastWriterWins, ObserveRemoveMap, ReplicatedList}
import kofre.dotted.{Dotted, DottedLattice, HasDots}
import kofre.syntax.{PermCausalMutate, PermId}
import kofre.time.{Dots, VectorClock}
import loci.communicator.tcp.TCP
import loci.registry.Registry
import replication.{DataManager, PeerPair, Status}
import replication.JsoniterCodecs.given

import scala.reflect.ClassTag
import kofre.base.Lattice.optionLattice
import kofre.datatypes.CausalQueue.QueueElement
import kofre.datatypes.LastWriterWins.TimedVal
import rescala.default.*

import java.nio.file.Path
import java.util.Timer
import scala.annotation.nowarn

enum Req:
  def executor: Id
  case Fortune(executor: Id)
  case Northwind(executor: Id, query: String)
enum Res:
  def req: Req
  case Fortune(req: Req.Fortune, result: String)
  case Northwind(req: Req.Northwind, result: List[Map[String, String]])

class FbdcExampleData(val replicaId: kofre.base.Id = Id.gen()) {
//  val replicaId = Id.gen()
  val registry  = new Registry

  val remotesEvt = Evt[(String, String, Boolean)]()
  val remotes: Signal[Map[String, String]] = remotesEvt.fold(Map.empty[String, String]) { (current, info) =>
    // remove Map entry if false is passed, otherwise add to map
    if (!info._3) {
      current - (info._1)
    } else {
      current + (info._1 -> info._2)
    }
  }
  remotes.changed.observe { x =>
          println(s"====== REMOTES CHANGED ======")
          x.toList.foreach(x => println(x))
  }



  given Bottom[State] = Bottom.derived

  val dataManager =
    @nowarn given JsonValueCodec[State] = JsonCodecMaker.make(CodecMakerConfig.withMapAsArray(true))
    new DataManager[State](replicaId, registry)

  registry.remoteJoined.foreach(rr => {
    println(s"$replicaId: registering new remote $rr")
    addStatus(Status(replicaId.toString,true, false, rr.toString))
  })

  registry.remoteLeft.foreach(rr => {
    println(s"$replicaId: disconnecting remote $rr")
    addStatus(Status(replicaId.toString,false, true, rr.toString))
  })

  def addCapability(capamility: String) =
    dataManager.transform { current =>
      current.modParticipants { part =>
        part.mutateKeyNamedCtx(replicaId)(_.add(capamility))
      }
    }

  def addPeer(peerPair: PeerPair) =
    dataManager.transform { current =>
      current.modPeer { curr =>
        if (peerPair.right == "disconnected") {
          val currentList: List[PeerPair] = curr.elements.toList
          var temp: List[PeerPair] = List[PeerPair]()
          for (peerPairElem <- currentList) {
            if (peerPairElem.consistsString(peerPair.left)) {
              println(s"removing element $peerPairElem")
              temp = peerPairElem :: temp
            }
          }
          curr.removeAll(temp)
        } else {
          curr.add(peerPair)
        }
      }
    }

  def addStatus(status: Status) =
    dataManager.transform { current =>
      current.modStatus { curr =>
        // delete peers if status.status is false
        if(!status.status){
          // this will find and delete the peer-pairs that are disconnected
          addPeer(PeerPair(remotes.now.get(status.ref).get, "disconnected"))
          // this will remove the map of the remote reference
          remotesEvt.fire((status.ref, "", false))
        }
        if (status.remove) {
          val currentList: List[Status] = curr.elements.toList
          var index: Option[Status] = None
          for (n <- 0 to currentList.length - 1) {
            if (currentList(n) == status) {
              index = Some(status)
            }
          }
          index match {
            case None => curr.elements.toList
            case Some(value) => {
              curr.remove(value)
            }
          }
        } else {
          curr.add(status)
        }
      }
    }

  type RespValue = Option[TimedVal[Res]]

  given Ordering[VectorClock] = VectorClock.vectorClockTotalOrdering
  given DottedLattice[RespValue] = DottedLattice.liftLattice
  given HasDots[RespValue] with {
    override def dots(a: RespValue): Dots = Dots.empty
  }


  case class State(
      requests: CausalQueue[Req],
      responses: ObserveRemoveMap[String, RespValue],
      providers: ObserveRemoveMap[Id, AddWinsSet[String]],
      connections: AddWinsSet[PeerPair],
      status: AddWinsSet[Status]
  ) derives DottedLattice, HasDots {

    class Forward[T](select: State => T, wrap: T => State)(using pcm: PermCausalMutate[State, State], pi: PermId[State])
        extends PermId[T]
        with PermCausalMutate[T, T] {
      override def replicaId(c: T): Id = pi.replicaId(State.this)
      override def mutateContext(container: T, withContext: Dotted[T]): T =
        pcm.mutateContext(State.this, withContext.map(wrap))
        container
      override def query(c: T): T      = select(pcm.query(State.this))
      override def context(c: T): Dots = pcm.context(State.this)
    }

    type Mod[T] = PermCausalMutate[T, T] ?=> PermId[T] ?=> T => Unit

    def modReq(using pcm: PermCausalMutate[State, State], pi: PermId[State])(fun: Mod[CausalQueue[Req]]) = {
      val x = new Forward(_.requests, State(_, Bottom.empty, Bottom.empty, Bottom.empty, Bottom.empty))
      fun(using x)(using x)(requests)
    }

    def modPeer(using pcm: PermCausalMutate[State, State], pi: PermId[State])(fun: Mod[AddWinsSet[PeerPair]]) = {
      val x = new Forward(_.connections, State(Bottom.empty, Bottom.empty, Bottom.empty, _, Bottom.empty))
      fun(using x)(using x)(connections)
    }

    def modStatus(using pcm: PermCausalMutate[State, State], pi: PermId[State])(fun: Mod[AddWinsSet[Status]]) = {
      val x = new Forward(_.status, State(Bottom.empty, Bottom.empty, Bottom.empty, Bottom.empty, _))
      fun(using x)(using x)(status)
    }

    def modRes(using pcm: PermCausalMutate[State, State], pi: PermId[State])(fun: Mod[ObserveRemoveMap[String, RespValue]]) = {
      val x = new Forward(_.responses, State(Bottom.empty, _, Bottom.empty, Bottom.empty, Bottom.empty))
      fun(using x)(using x)(responses)
    }

    def modParticipants(using
        pcm: PermCausalMutate[State, State],
        pi: PermId[State]
    )(fun: Mod[ObserveRemoveMap[Id, AddWinsSet[String]]]) = {
      val x = new Forward(_.providers, State(Bottom.empty, Bottom.empty, _, Bottom.empty, Bottom.empty))
      fun(using x)(using x)(providers)
    }
  }

  val requests = dataManager.mergedState.map(_.store.requests.values)
  val myRequests =
    val r = requests.map(_.filter(_.value.executor == replicaId))
    r.observe { reqs =>
      if reqs.nonEmpty
      then
        dataManager.transform { state =>
          state.modReq { aws =>
            aws.removeBy { (req: Req) => req.executor == replicaId }
          }
        }
    }
    r
  val responses = dataManager.mergedState.map(_.store.responses.entries.toMap)

  val latestFortune = responses.map(_.get("fortune").flatten.map(_.payload).collect {
    case res: Res.Fortune => res
  })


  val latestNorthwind = responses.map(_.get("northwind").flatten.map(_.payload).collect{
    case res: Res.Northwind => res
  })

  def requestsOf[T: ClassTag] = myRequests.map(_.collect {
    case req@ QueueElement(x: T, _, _) => req.copy(value = x)
  })

  val providers = dataManager.mergedState.map(_.store.providers)



  val connections = dataManager.mergedState.map(_.store.connections)

  dataManager.mergedState.map(state => {
    println(s"====== STATE CHANGED ======")
    val peers: List[PeerPair] = state.store.connections.elements.toList
    peers.foreach(x => println(x))

    // delete peer with ID "presentation"
    if(peers.exists(p => p.left == "presentation" || p.right == "presentation")){
      addPeer(PeerPair("presentation", "disconnected"))
    }


    val listStatus: List[Status] = state.store.status.elements.toList
    listStatus.foreach(x => println(x))

    if (listStatus.length == 2 && !listStatus(0).remove && !listStatus(1).remove) {
      val peerOne = listStatus(0)
      val peerTwo = listStatus(1)
      val peerToAdd = if (replicaId.toString != peerOne.id) peerOne.id else peerTwo.id
      val refToAdd = if (replicaId.toString == peerOne.id) peerOne.ref else peerTwo.ref

      // add new remote reference in Map
      if (!remotes.now.contains(refToAdd)) {
        remotesEvt.fire((refToAdd, peerToAdd, true))
      }

      // create the peer-pair
      addPeer(PeerPair(peerOne.id, peerTwo.id))

      // delete the elements in the list
      addStatus(Status(peerOne.id, true, true, peerOne.ref))
      addStatus(Status(peerTwo.id, true, true, peerTwo.ref))
    }


  })

}
