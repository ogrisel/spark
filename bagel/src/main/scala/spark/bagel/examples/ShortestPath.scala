package spark.bagel.examples

import spark._
import spark.SparkContext._

import scala.math.min

import spark.bagel._
import spark.bagel.Bagel._

object ShortestPath {
  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println("Usage: ShortestPath <graphFile> <startVertex> " +
                         "<numSplits> <host>")
      System.exit(-1)
    }

    val graphFile = args(0)
    val startVertex = args(1)
    val numSplits = args(2).toInt
    val host = args(3)
    val sc = new SparkContext(host, "ShortestPath")

    // Parse the graph data from a file into two RDDs, vertices and messages
    val lines =
      (sc.textFile(graphFile)
       .filter(!_.matches("^\\s*#.*"))
       .map(line => line.split("\t")))

    val vertices: RDD[(String, SPVertex)] =
      (lines.groupBy(line => line(0))
       .map {
         case (vertexId, lines) => {
           val outEdges = lines.collect {
             case Array(_, targetId, edgeValue) =>
               new SPEdge(targetId, edgeValue.toInt)
           }
           
           (vertexId, new SPVertex(vertexId, Int.MaxValue, outEdges, true))
         }
       })

    val messages: RDD[(String, SPMessage)] =
      (lines.filter(_.length == 2)
       .map {
         case Array(vertexId, messageValue) =>
           (vertexId, new SPMessage(vertexId, messageValue.toInt))
       })
    
    System.err.println("Read "+vertices.count()+" vertices and "+
                       messages.count()+" messages.")

    // Do the computation
    val compute = addAggregatorArg {
      (self: SPVertex, messageMinValue: Option[Int], superstep: Int) =>
        val newValue = messageMinValue match {
          case Some(minVal) => min(self.value, minVal)
          case None => self.value
        }

        val outbox =
          if (newValue != self.value)
            self.outEdges.map(edge =>
              new SPMessage(edge.targetId, newValue + edge.value))
          else
            List()

        (new SPVertex(self.id, newValue, self.outEdges, false), outbox)
    }
    val result = Bagel.run(sc, vertices, messages)(combiner = MinCombiner, numSplits = numSplits)(compute)

    // Print the result
    System.err.println("Shortest path from "+startVertex+" to all vertices:")
    val shortest = result.map(vertex =>
      "%s\t%s\n".format(vertex.id, vertex.value match {
        case x if x == Int.MaxValue => "inf"
        case x => x
      })).collect.mkString
    println(shortest)
  }
}

@serializable
object MinCombiner extends Combiner[SPMessage, Int] {
  def createCombiner(msg: SPMessage): Int =
    msg.value
  def mergeMsg(combiner: Int, msg: SPMessage): Int =
    min(combiner, msg.value)
  def mergeCombiners(a: Int, b: Int): Int =
    min(a, b)
}

@serializable class SPVertex(val id: String, val value: Int, val outEdges: Seq[SPEdge], val active: Boolean) extends Vertex
@serializable class SPEdge(val targetId: String, val value: Int) extends Edge
@serializable class SPMessage(val targetId: String, val value: Int) extends Message
