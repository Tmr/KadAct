package kadact.node.routing

import akka.actor.{Actor, ActorLogging}
import akka.actor.Actor._
import akka.event.{Logging, LoggingReceive}

import kadact.KadAct
import kadact.node.Contact
import kadact.node._

import scala.collection.immutable.TreeSet


object RoutingTable {
	sealed trait Messages
	case class Insert(contact: Contact) extends Messages
	case class PickNNodesCloseTo(n: Int, nodeID: NodeID) extends Messages
	case object SelectRandomIDs extends Messages
	case class Failed(contact: Contact) extends Messages
}

class RoutingTable(originalNode: Contact, origin: NodeID) extends Actor with ActorLogging{
	import RoutingTable._
	
	def this(originalNode: Contact) = this(originalNode, originalNode.nodeID)
	
	var rootIDSpace: IDDistanceSpace = new LeafIDSpace(origin)
	val siblings: SBucket = new SBucket(origin)
	
	def receive = LoggingReceive{
		case Insert(contact) => {
			if(contact.nodeID != origin){ //"A node should never put its own NodeID into a bucket as a contact"
				// First we try to insert the contact in our siblings list
				siblings.insertOrUpdate(contact) match {
					// The contact was inserted and no other contact had to be removed from the siblings list
					case (true, None) => {
						// nothing do do
					}
					// The contact wasn't inserted (it is too far away), so we must insert it in the other KBuckets
					case (false, None) => {
						val (newRoot, result) = this.rootIDSpace.insert(contact)
						this.rootIDSpace = newRoot
					}
					// The contact was inserted and one contact that already existed now must be inserted in the KBuckets
					case (true, Some(removedContact)) => {
						val (newRoot, result) = this.rootIDSpace.insert(removedContact)
						this.rootIDSpace = newRoot
					}
				}
			}
		}
		case PickNNodesCloseTo(n, nodeID) => {
			/* This is highly inefficient but for now it works as expected */
			var result = new TreeSet[Contact]()(kadact.node.ContactClosestToOrdering(nodeID))
			result += originalNode
			result ++= siblings.pickNNodes(n)
			result ++= rootIDSpace.pickNNodesCloseTo(n, distance(this.origin, nodeID))

			val tmp = result.take(n)
			log.debug("Picked "+n+" nodes: "+tmp)
			sender ! tmp
		}
		case SelectRandomIDs => {
			val tmp = rootIDSpace.selectRandomNodeIDs()
			sender ! tmp
		}
	}
	
	
}