package kadact.node

import akka.actor.{Actor, ActorRef, FSM}
import akka.actor.Actor._
import akka.util.Duration
import kadact.KadAct
import akka.actor.LoggingFSM
import scala.collection.immutable.Queue

//There should be a NodeLookupManager to deal with the creation of NodeLookups

object LookupManager {
	//import NodeLookup.{Lookup, LookupResponse}
	
	sealed trait State
	case object Working extends State
	case object Full extends State
	
	sealed trait LookupType
	case object Node extends LookupType
	case object Value extends LookupType
	
	sealed trait Messages
	case class LookupNode(nodeID: NodeID) extends Messages
	case class LookupNodeResponse(nodeID: NodeID, contacts: Set[Contact]) extends Messages
	case class LookupValue(key: Key) extends Messages
	case class LookupValueResponse[V](key: Key, answer: Either[V, Set[Contact]]) extends Messages
	
	object Lookup {
		def unapply(msg: Messages) : Option[(LookupType, GenericID)] = {
			msg match {
				case LookupNode(nodeID) => Some(Node, nodeID)
				case LookupValue(key) => Some(Value, key)
				case _ => None
			}
		}
	}
	
	case class Data(idleList: List[ActorRef] = Nil, workingList: List[ActorRef] = Nil, awaitingActors: Map[Int, ActorRef] = Map(), pendingLookups: Queue[(Messages, ActorRef)] = Queue())
}

class LookupManager[V](originalNode: Contact, routingTable: ActorRef) extends Actor with FSM[LookupManager.State, LookupManager.Data] with LoggingFSM[LookupManager.State, LookupManager.Data]{
	import FSM._
	import LookupManager._
	//import NodeLookup.{Lookup, LookupResponse}
	
	val generationIterator = Iterator from 0
	
	startWith(Working, Data(idleList = actorOf(new NodeLookup(this.self, originalNode, routingTable)).start() :: Nil))
	
	when(Working){
		case Event(Lookup(lookupType, id), currentData @ Data(someWorker :: tail, workList, awaitingActors, _)) => {
			val nextGen = generationIterator.next()
			
			lookupType match {
				case 
			}
			someWorker ! NodeLookup.Lookup(nextGen, nodeID)
			
			stay using currentData.copy(idleList = tail, workingList = someWorker :: workList, awaitingActors = awaitingActors + (nextGen -> self.sender.get))
		}
		
		case Event(look @ Lookup(nodeID), currentData @ Data(Nil, workList, awaitingActors,_)) if workList.size < KadAct.maxParallelLookups => {
			val someWorker = actorOf(new NodeLookup(this.self, originalNode, routingTable)).start()
			val nextGen = generationIterator.next()
			
			someWorker ! NodeLookup.Lookup(nextGen, nodeID)

			stay using currentData.copy(workingList = someWorker :: workList, awaitingActors = awaitingActors + (nextGen -> self.sender.get))
		}
		
		case Event(look @ Lookup(_), currentData @ Data(Nil, workList, awaitingActors, pendingLookups)) if workList.size == KadAct.maxParallelLookups => {
			goto(Full) using currentData.copy(pendingLookups = pendingLookups.enqueue((look, self.sender.get)))
		}
		
		case Event(NodeLookup.LookupResponse(generation, nodeID, contacts), currentData @ Data(idleList, workingList, awaitingActors, _)) => {
			val worker = self.sender.get
			
			awaitingActors(generation) ! LookupResponse(nodeID, contacts)
			
			stay using currentData.copy(idleList = worker :: idleList, workingList = workingList filterNot(_ == worker), awaitingActors = awaitingActors - generation)
		}
		
	}
	
	when(Full) {
		case Event(look @ Lookup(_), currentData @ Data(_, _, _, pendingLookups)) => {
			stay using currentData.copy(pendingLookups = pendingLookups.enqueue((look, self.sender.get)))
		}
		
		case Event(NodeLookup.LookupResponse(generation, nodeID, contacts), currentData @ Data(_, _, awaitingActors, pendingLookups)) => {
			awaitingActors(generation) ! LookupResponse(nodeID, contacts)
			val ((Lookup(otherNodeID), newAwaitingActor), newQueue) = pendingLookups.dequeue
			val someWorker = self.sender.get
			val nextGen = generationIterator.next()
			
			someWorker ! NodeLookup.Lookup(nextGen, otherNodeID)
			val newAwaitingActors = awaitingActors - generation + (nextGen -> newAwaitingActor)
			
			val newState = 
				if(pendingLookups.size == 1){
					goto(Working)
				} else {
					stay
				}
			newState using currentData.copy(awaitingActors = newAwaitingActors, pendingLookups = newQueue)
		}
	}
	
	initialize
}