package replication.webapp

import org.scalajs.dom
import org.scalajs.dom.{CanvasRenderingContext2D, document}
import org.scalajs.dom.html.{Canvas, Div, Image}
import replication.PeerPair

import scala.collection.mutable.ListBuffer
import scala.math.{cos, sin, toRadians}

case class Peer(id: String,x: Double, y: Double)
class DrawNetwork(val pairs: Set[PeerPair]) {

  private val uniqueIds: Set[String] = pairs.flatMap(pair => Set(pair.left, pair.right))
  private val indexToId: Map[Int, String] = uniqueIds.toList.sorted.zipWithIndex.map((id, index) => index -> id).toMap
  private val idToIndex: Map[String, Int] = uniqueIds.toList.sorted.zipWithIndex.map((id, index) => id -> index).toMap
  private val peersSize = uniqueIds.size

  // Variables for the function to find critical pairs
  private var disc: Array[Int] = new Array[Int](peersSize)
  private var low: Array[Int] = new Array[Int](peersSize)
  private var time: Int = 1
  private var criticalPairs: scala.collection.mutable.Set[PeerPair] = scala.collection.mutable.Set.empty
  private var pairMap: scala.collection.mutable.Map[Int, ListBuffer[Int]] = scala.collection.mutable.Map.empty

  /**
    * Function to find critical pairs.
    * If there are no critical pairs, meaning that every peer in the network is in a cycle
    * See Tarjan's Bridge-Finding Algorithm (TBFA)
    */
  private def criticalConnections(): Unit = {
    val connections: List[List[Int]] = pairs.map(pp => List(idToIndex.get(pp.left).get, idToIndex.get(pp.right).get)).toList
    for(i <- 0 to peersSize-1){
      pairMap.put(i, ListBuffer[Int]())
    }
    connections.foreach(conn => {
      pairMap.get(conn(0)).get += conn(1)
      pairMap.get(conn(1)).get += conn(0)
    })
    dfs(0,-1)
  }

  /**
    * Recursive function to find the pairs that are critical
    *
    * @param curr
    * @param prev
    */
  private def dfs(curr: Int, prev: Int): Unit = {
    disc(curr) = time
    low(curr) = time
    time += 1

    pairMap.get(curr).get.foreach(next => {
      if(disc(next) == 0){
        dfs(next, curr)
        low(curr) = Math.min(low(curr), low(next))
      } else if (next != prev){
        low(curr) = Math.min(low(curr), disc(next))
      }
      if(low(next) > disc(curr)){
        criticalPairs += PeerPair(indexToId.get(curr).get, indexToId.get(next).get)
      }
    })
  }

  /**
    * To calculate where the peers should be positioned in the canvas
    * based on which topology suit best for the network gathered
    *
    * @param canvasWidth
    * @param canvasHeight
    * @param centerX
    * @param centerY
    * @return
    */
  private def calculatePeerPosition(canvasWidth: Double, canvasHeight: Double, centerX: Double, centerY: Double): Map[String, Peer] = {
    var result: scala.collection.mutable.Map[String, Peer] = scala.collection.mutable.Map.empty

    val connectedPeersSizeMap: Map[Int, Int] = pairMap.map(p => {
      p._1 -> p._2.size
    }).toMap

    // only linear is currently using the grid system --> might be deleted later, or maybe might be useful for hybrid topology (if possible)
    val canvasGrid = Array.ofDim[String](peersSize, peersSize)
    var col: Int = 0
    var row: Int = 0

    var stack = scala.collection.mutable.Stack[Int]()
    val processed = new Array[Boolean](peersSize)

    val radius = Math.min(centerX * 1.5, centerY * 1.5) / 2
    var distanceBetweenPeers = 360 / peersSize

    var showMesh = false

    /**
      * If there is no critical pairs --> network has cycle loop(s)
      * * if each of the peer are exactly connected with 2 peers --> RING TOPOLOGY
      * * else show the network in MESH TOPOLOGY
      * Else (if there is critical pairs) --> network has no cycle loop
      * * if the number of critical pairs are the same as the number of peer connections AND each of the peer are exactly connected with 1 or 2 peer(s) --> LINEAR TOPOLOGY
      * * else
      * * * if there is one peer that has multiple connection to other peers AND all other peers are exactly connected with only 1 peer --> STAR TOPOLOGY
      * * * else show the network in MESH TOPOLOGY
      * * *
      * *
      *
      */
    if(criticalPairs.size == 0){
      val eachPeerEqualsTwo = connectedPeersSizeMap.forall(p => p._2 == 2)
      if(eachPeerEqualsTwo){
        // RING TOPOLOGY
        var ringOrder = ListBuffer[Int]()
        val mid = peersSize / 2
        col = mid
        stack.push(connectedPeersSizeMap.head._1)
        while (stack.size > 0) {
          val curr = stack.pop()
          if (!processed(curr)) {
            ringOrder += curr
            processed(curr) = true
            pairMap.get(curr).get.foreach(i => stack.push(i))
          }
        }

        ringOrder.zipWithIndex.foreach((idAsIndex, index) => {
          result.put(indexToId.get(idAsIndex).get,
            Peer(indexToId.get(idAsIndex).get, centerX + radius * sin(toRadians(distanceBetweenPeers * index)), centerY + radius * cos(toRadians(distanceBetweenPeers * index))))
        })

      } else {
        // MESH TOPOLOGY
        showMesh = true
      }
    } else {
      val eachPeerLessThanTwo = connectedPeersSizeMap.forall(p => p._2 <= 2)

      if(criticalPairs.size == pairs.size && eachPeerLessThanTwo) {
        // LINEAR TOPOLOGY
        row = (peersSize/2)-1
        stack.push(connectedPeersSizeMap.toList.sortBy(_._2).head._1)
        while(stack.size > 0){
          val curr = stack.pop()
          if(!processed(curr) && canvasGrid(row)(col) == null){
            canvasGrid(row)(col) = indexToId.get(curr).get
            processed(curr) = true
            println(s"peer $curr saved in row $row, col $col")
            col += 1
            pairMap.get(curr).get.foreach(i => stack.push(i))
          }
        }

        for(row <- 0 until peersSize){
          for(col <- 0 until peersSize){
            if(canvasGrid(row)(col) != null){
              result.put(canvasGrid(row)(col),
                Peer(canvasGrid(row)(col), (canvasWidth/peersSize * col) + canvasWidth/peersSize/2, (canvasHeight/peersSize * row) + canvasHeight/peersSize/2))
            }
          }
        }
      } else {
        val peersWithOneConn = connectedPeersSizeMap.filter(p => p._2 == 1)
        val peersWithMultConn = connectedPeersSizeMap.filter(p => p._2 > 1)

        if(peersWithMultConn.size == 1 && peersWithOneConn.size == connectedPeersSizeMap.size-1){
          //STAR TOPOLOGY
          distanceBetweenPeers = 360 / peersWithOneConn.size
          result.put(indexToId.get(peersWithMultConn.head._1).get, Peer(indexToId.get(peersWithMultConn.head._1).get, centerX, centerY))
          peersWithOneConn.zipWithIndex.foreach((p, index) => {
            result.put(indexToId.get(p._1).get,
              Peer(indexToId.get(p._1).get, centerX + radius * sin(toRadians(distanceBetweenPeers * index)), centerY + radius * cos(toRadians(distanceBetweenPeers * index))))
          })
        } else {
          // MESH TOPOLOGY
          showMesh = true
        }
      }
    }

    if(showMesh){
      uniqueIds.toList.sorted.zipWithIndex.map((id, index) => {
        id -> Peer(id, centerX + radius * sin(toRadians(distanceBetweenPeers * index)), centerY + radius * cos(toRadians(distanceBetweenPeers * index)))
      }).toMap
    } else {
      result.toMap
    }

  }

  /**
    * Process and draw the topology of the p2p network
    */
  def draw(): Unit = {
    val canvasElem = getCanvas().get
    val divCanvas = getDivCanvas().get
    val ctx = canvasElem.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    // The commented below is alternative to fixing the pixelated canvas, but currently not working when app is started in browsers in MacOS (firefox,chrome,etc)
    // Due to the NS_ERROR_FAILURE in javascript. Forum said cause changing canvas' width/height to a big value
    //    val dpr = dom.window.devicePixelRatio
    //    val canvasElemHeight = dom.window.getComputedStyle(canvasElem).getPropertyValue("height").dropRight(2)
    //    val canvasElemWidth = dom.window.getComputedStyle(canvasElem).getPropertyValue("width").dropRight(2)
    //    canvasElem.setAttribute("height", canvasElemHeight * dpr.toInt)
    //    canvasElem.setAttribute("width", canvasElemWidth * dpr.toInt)

    /* setting the canvas width and height based on the window (another alternative to make the canvas not pixelated */
    canvasElem.width = divCanvas.getBoundingClientRect().width.toInt
    canvasElem.height = dom.window.innerHeight.toInt
    //     canvasElem.height = divCanvas.getBoundingClientRect().height.toInt

    val imageSize = 100
    val offsetImage = imageSize / 2
    val canvasWidth = canvasElem.width.toDouble
    val canvasHeight = canvasElem.height.toDouble
    val centerX = canvasWidth / 2 - offsetImage
    val centerY = canvasHeight / 2 - offsetImage

    // return if the peer is not yet connected. Also show hint to connect
    if (peersSize == 0) {
      showHintText("Please connect to a peer", canvasWidth, canvasHeight, centerX - offsetImage, centerY + offsetImage, imageSize * 5, ctx)
      return
    }

    criticalConnections()
    criticalPairs.foreach(pair => println(s"critical edge = ${pair.left} -- ${pair.right}"))

    val peers: Map[String, Peer] = calculatePeerPosition(canvasWidth, canvasHeight, centerX, centerY)

    if(peers.isEmpty){
      showHintText("Something is not right, please check console", canvasWidth, canvasHeight, centerX - offsetImage, centerY + offsetImage, imageSize * 5, ctx)
      return
    }

    // do the drawing after image has been loaded
    val image = document.createElement("img").asInstanceOf[Image]
    image.src = "https://bagusnanda.com/download/images/desktop.png"
    image.onload = (e: dom.Event) => {

      // reset the canvas
      ctx.clearRect(0, 0, canvasWidth, canvasHeight.toDouble)

      // Make the connection lines
      ctx.lineWidth = 3
      ctx.strokeStyle = "blue"
      ctx.font = "14px sans-serif"
      ctx.beginPath()
      pairs.foreach(pair => {
        val peerLeft: Peer = peers(pair.left)
        val peerRight: Peer = peers(pair.right)
        ctx.moveTo(peerLeft.x + imageSize / 2, peerLeft.y + imageSize / 2)
        ctx.lineTo(peerRight.x + imageSize / 2, peerRight.y + imageSize / 2)
      })
      ctx.stroke()

      // insert the peer image
      peers.foreach((id, peer) => {
        ctx.drawImage(image, peer.x, peer.y, imageSize, imageSize)
//        val peerText = if (peer.id == peerId) "You" else peer.id
        val peerText = peer.id
        ctx.fillText(peerText, peer.x, peer.y, imageSize * 5)
      })
    }
  }

  private def showHintText(text: String, canvasWidth: Double, canvasHeight: Double, textPosX: Double, textPosY: Double, maxWidth: Double, ctx: CanvasRenderingContext2D): Unit = {
    ctx.font = "20px sans-serif"
    ctx.clearRect(0, 0, canvasWidth, canvasHeight)
    ctx.fillText(text, textPosX, textPosY, maxWidth)
  }

  def getCanvas(): Option[Canvas] = {
    val queryResult = document.querySelector(s"#canvasId")
    queryResult match {
      case canvas: Canvas => Some(canvas)
      case other =>
        println(s"Element with ID canvasId is not an image, it's $other")
        None
    }
  }

  def getDivCanvas(): Option[Div] = {
    val queryResult = document.querySelector(s"#divId")
    queryResult match {
      case divCanvas: Div => Some(divCanvas)
      case other =>
        println(s"Element with ID divId is not an image, it's $other")
        None
    }
  }
}

