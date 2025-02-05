package replication.webapp

import org.scalajs.dom
import org.scalajs.dom.html.Element
import org.scalajs.dom.{MouseEvent, document}
import replication.DataManager
import replication.webapp.MetaInfo
import rescala.default.*
import rescala.extra.Tags.*
import scalatags.JsDom.TypedTag
import scalatags.JsDom.all.{*, given}
import scalatags.JsDom.tags2.{article, aside, nav, section}
import kofre.base.Id
import loci.transmitter.RemoteRef
import replication.fbdc.{FbdcExampleData, Req}
import replication.{PeerPair}

import scala.collection.immutable.LinearSeq

object HTML {

  def leftClickHandler(action: => Unit) = { (e: MouseEvent) =>
    if (e.button == 0) {
      e.preventDefault()
      action
    }
  }

  val RemoteRegex = raw"""^remote#(\d+).*""".r.anchored
  def remotePrettyName(rr: RemoteRef) =
    val RemoteRegex(res) = rr.toString: @unchecked
    res

  def connectionManagement(ccm: ContentConnectionManager, fbdcExampleData: FbdcExampleData) = {
    import fbdcExampleData.dataManager
    List(
      h2("Connect to server to start the real-time visualization"),
//      section(table(
//        tr(td("total state size"), dataManager.encodedStateSize.map(s => td(s)).asModifier),
//        tr(
//          td("request queue"),
//          dataManager.mergedState.map(v => td(v.store.requests.elements.size)).asModifier
//        ),
//      )),
//      section(
//        button("disseminate local", onclick := leftClickHandler(dataManager.disseminateLocalBuffer())),
//        button("disseminate all", onclick   := leftClickHandler(dataManager.disseminateFull()))
//      ),
//      section(table(
//        thead(th("remote ref"), th("connection"), th("request"), th("dots")),
//        tr(
//          td(Id.unwrap(dataManager.replicaId)),
//          td(),
//          td(),
//          table(
//            dataManager.currentContext.map(dotsToRows).asModifierL
//          )
//        ),
//        ccm.connectedRemotes.map { all =>
//          all.toList.sortBy(_._1.toString).map { (rr, connected) =>
//            tr(
//              td(remotePrettyName(rr)),
//              if !connected
//              then td("disconnected")
//              else
//                List(
//                  td(button("disconnect", onclick := leftClickHandler(rr.disconnect()))),
//                  td(button("request", onclick := leftClickHandler(dataManager.requestMissingFrom(rr)))),
//                  td(table(
//                    dataManager.contextOf(rr).map(dotsToRows).asModifierL
//                  ))
//                )
//            )
//          }
//        }.asModifierL,
//      )),
      section(aside(
        "remote url: ",
        ccm.wsUri,
        button("Connect", onclick := leftClickHandler(ccm.connect()))
      ))
    )
  }

  def dotsToRows(dots: kofre.time.Dots) =
    dots.internal.toList.sortBy(t => Id.unwrap(t._1)).map { (k, v) =>
      tr(td(Id.unwrap(k)), td(v.toString))
    }.toSeq

  def providers(exdat: FbdcExampleData) = {
    div(
      h1("make a request"),
      exdat.providers.map { prov =>
        prov.observeRemoveMap.entries.map { (id, provided) =>
          section(
            header(h2("Executor:", Id.unwrap(id))),
            provided.elements.iterator.map {
              case "fortune" => fortuneBox(exdat, id)
              case other     => northwindBox(exdat, id)
            }.toList
          ).asInstanceOf[TypedTag[Element]]
        }.toList
      }.asModifierL
    )

  }

  def visualization() = {
    div(
      div(
        id := "divId",
        `class` := "row",
        div(
          `class` := "col",
          canvas(
            id := "canvasId",
//            border := "1px solid black",
            width := "100%",
            height := "100%",
          )
        )
      )
    )
  }

  def fortuneBox(exdat: FbdcExampleData, id: Id) = aside(
    button(
      "get fortune",
      onclick := leftClickHandler {
        exdat.dataManager.transform { curr =>
          curr.modReq { reqs =>
            reqs.enqueue(Req.Fortune(id))
          }
        }
      }
    ),
    exdat.latestFortune.map(f => p(f.map(_.result).getOrElse(""))).asModifier
  )

  def northwindBox(exdat: FbdcExampleData, id: Id) =
    val ip = input().render

    aside(
      ip,
      button(
        "query northwind",
        onclick := leftClickHandler {
          exdat.dataManager.transform { curr =>
            curr.modReq { reqs =>
              reqs.enqueue(Req.Northwind(id, ip.value))
            }
          }
        }
      ),
      p(
      table(
        exdat.latestNorthwind.map {
          case None => Nil
          case Some(res) =>
            val keys = res.result.head.keys.toList.sorted
            thead(keys.map(th(_)).toList: _*) ::
            res.result.map { row =>
              tr(keys.map(k => td(row(k))))
            }
        }.asModifierL
      )
    ))
}
