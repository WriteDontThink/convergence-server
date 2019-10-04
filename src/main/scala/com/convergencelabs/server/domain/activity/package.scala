package com.convergencelabs.server.domain.activity

import akka.actor.ActorRef
import com.convergencelabs.server.domain.DomainId
import org.json4s.JsonAST.JValue

// Incoming Activity Messages
sealed trait IncomingActivityMessage {
  val domain: DomainId
  val activityId: String
}

case class ActivityParticipantsRequest(domain: DomainId, activityId: String) extends IncomingActivityMessage

case class ActivityJoinRequest(domain: DomainId, activityId: String, sessionId: String, state: Map[String, JValue], actorRef: ActorRef) extends IncomingActivityMessage
case class ActivityLeave(domain: DomainId, activityId: String, sessionId: String) extends IncomingActivityMessage

case class ActivityUpdateState(
    domain: DomainId,
    activityId: String,
    sessionId: String, 
    state: Map[String, JValue],
    complete: Boolean,
    removed: List[String]) extends IncomingActivityMessage

// Outgoing Activity Messages
sealed trait OutgoingActivityMessage {
}

case class ActivityJoinResponse(state: Map[String, Map[String, JValue]]) extends OutgoingActivityMessage
case class ActivityParticipants(state: Map[String, Map[String, JValue]]) extends OutgoingActivityMessage

case class ActivitySessionJoined(activityId: String, sessionId: String, state: Map[String, JValue]) extends OutgoingActivityMessage
case class ActivitySessionLeft(activityId: String, sessionId: String) extends OutgoingActivityMessage

case class ActivityStateUpdated(
    activityId: String,
    sessionId: String, 
    state: Map[String, JValue],
    complete: Boolean,
    removed: List[String]) extends OutgoingActivityMessage
    
// Exceptions
case class ActivityAlreadyJoinedException(activityId: String) extends Exception(s"Activity '${activityId}' is already joined.")
case class ActivityNotJoinedException(activityId: String) extends Exception(s"Activity '${activityId}' is not joined.")