package replication.webapp

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import kofre.base.{Id, Lattice}
import kofre.datatypes.ReplicatedList
import kofre.dotted.DottedLattice
import loci.registry.Registry
import org.scalajs.dom
import org.scalajs.dom.{CanvasRenderingContext2D, Fetch, HttpMethod, RequestInit}
import replication.{DataManager, PeerPair}
import rescala.default.*
import rescala.extra.Tags.*
import scalatags.JsDom.attrs.id
import scalatags.JsDom.implicits.{stringAttr, stringFrag}
import scalatags.JsDom.tags.{SeqFrag, SeqNode, body, form, h1, p, span, table}
import replication.JsoniterCodecs.given
import scalatags.JsDom.tags2.{article, aside, main}
import scalatags.JsDom.tags2
import replication.fbdc.FbdcExampleData

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.ArrayBuffer

case class MetaInfo(
    connection: Signal[Int],
    reconnecting: Signal[Int]
)

object WebRepMain {

  val baseurl = ""

  def fetchbuffer(
      endpoint: String,
      method: HttpMethod = HttpMethod.GET,
      body: Option[String] = None
  ): Future[ArrayBuffer] = {

    val ri = js.Dynamic.literal(method = method).asInstanceOf[RequestInit]

    body.foreach { content =>
      ri.body = content
      ri.headers = js.Dictionary("Content-Type" -> "application/json;charset=utf-8")
    }

    Fetch.fetch(baseurl + endpoint, ri).toFuture
      .flatMap(_.arrayBuffer().toFuture)
  }

  @JSExportTopLevel("Replication")
  def run() = main(Array.empty)

  def main(args: Array[String]): Unit = {
    dom.document.body = body("loading data …").render

    @nowarn given JsonValueCodec[ReplicatedList[String]] = JsonCodecMaker.make(CodecMakerConfig.withMapAsArray(true))

    val exData = new FbdcExampleData(kofre.base.Id.asId("presentation") )

    val ccm = new ContentConnectionManager(exData.registry)

    val bodySig = Signal {
      body(
        id := "index",
        tags2.main(
//          HTML.providers(exData),
          HTML.connectionManagement(ccm, exData),
          HTML.visualization()
        )
      )
    }

    val bodyParent = dom.document.body.parentElement
    bodyParent.removeChild(dom.document.body)
    bodySig.asModifier.applyTo(bodyParent)

    // draw network everytime peers are updating
    exData.connections.observe(peers => {
      DrawNetwork(peers.elements).draw();
    })

  }
}
