package com.convergencelabs.server.domain.chat

import java.time.Instant

import scala.util.control.NonFatal

import com.convergencelabs.server.datastore.domain.ChatChannelInfo
import com.convergencelabs.server.datastore.domain.ChatChannelStore
import com.convergencelabs.server.datastore.domain.ChatChannelStore.ChannelType
import com.convergencelabs.server.datastore.domain.ChatCreatedEvent
import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.chat.ChatChannelMessages.CreateChannelRequest
import com.convergencelabs.server.domain.chat.ChatChannelMessages.CreateChannelResponse

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Status
import akka.actor.actorRef2Scala
import com.convergencelabs.server.domain.chat.ChatChannelMessages.ChannelNotFoundException
import com.convergencelabs.server.datastore.EntityNotFoundException
import com.convergencelabs.server.datastore.DuplicateValueException
import com.convergencelabs.server.domain.chat.ChatChannelMessages.ChannelAlreadyExistsException

object ChatChannelLookupActor {

  val RelativePath = "chatChannelLookupActor"

  def props(domainFqn: DomainFqn): Props = Props(
    new ChatChannelLookupActor(domainFqn))

  case class GetChannelsRequest(ids: List[String], username: String)
  case class GetChannelsResponse(channels: List[ChatChannelInfo])

  case class ChannelsExistsRequest(ids: List[String], username: String)
  case class ChannelsExistsResponse(channels: List[Boolean])

  case class GetJoinedChannelsRequest(username: String)
  case class GetJoinedChannelsResponse(channels: List[ChatChannelInfo])

  case class GetDirectChannelsRequest(username: String, userLists: List[List[String]])
  case class GetDirectChannelsResponse(channels: List[ChatChannelInfo])
}

class ChatChannelLookupActor private[domain] (domainFqn: DomainFqn) extends Actor with ActorLogging {

  import ChatChannelLookupActor._

  var chatChannelStore: ChatChannelStore = _

  def receive: Receive = {
    case message: CreateChannelRequest =>
      onCreateChannel(message)
    case message: GetChannelsRequest =>
      onGetChannels(message)
    case message: GetJoinedChannelsRequest =>
      onGetJoinedChannels(message)
    case message: GetDirectChannelsRequest =>
      onGetDirect(message)
    case message: ChannelsExistsRequest =>
      onExists(message)
  }

  def onCreateChannel(message: CreateChannelRequest): Unit = {
    val CreateChannelRequest(channelId, channelType, channelMembership, name, topic, members, createdBy) = message
    ChannelType.withNameOpt(channelType) match {
      case Some(ct) =>
        val isPrivate = channelMembership.toLowerCase match {
          case "private" => true
          case _ => false
        }

        (for {
          id <- this.chatChannelStore.createChatChannel(channelId, ct, isPrivate, name.getOrElse(""), topic.getOrElse(""), Some(members))
          _ <- this.chatChannelStore.addChatCreatedEvent(ChatCreatedEvent(0, id, createdBy, Instant.now(), name.getOrElse(""), topic.getOrElse(""), members))
        } yield {
          sender ! CreateChannelResponse(id)
        }) recover {
          case e: DuplicateValueException =>
            // FIXME how to deal with this? The channel id should only conflict if it was
            // defined by the user.
            val cId = channelId.get
            sender ! Status.Failure(ChannelAlreadyExistsException(cId))
          case NonFatal(cause) =>
            sender ! Status.Failure(cause)
        }
      case None =>
        sender ! Status.Failure(new IllegalArgumentException(s"Invalid channel type: ${channelType}"))
    }
  }

  def onGetChannels(message: GetChannelsRequest): Unit = {
    val GetChannelsRequest(ids, username) = message
    // TODO support multiple.
    val id = ids(0)
    chatChannelStore.getChatChannelInfo(id).map { info =>
      sender ! GetChannelsResponse(List(info))
    } recover {
      case cause: Exception =>
        sender ! Status.Failure(cause)
    }
  }

  def onExists(message: ChannelsExistsRequest): Unit = {
    val ChannelsExistsRequest(ids, username) = message
    // TODO support multiple.
    // FIXME this should be an option or something.
    val id = ids(0)
    chatChannelStore.getChatChannelInfo(id).map { info =>
      sender ! ChannelsExistsResponse(List(true))
    } recover {
      case cause: EntityNotFoundException =>
        sender ! ChannelsExistsResponse(List(false))
      case cause: Exception =>
        sender ! Status.Failure(cause)
    }
  }

  def onGetDirect(message: GetDirectChannelsRequest): Unit = {
    val GetDirectChannelsRequest(username, usernameLists) = message;
    ???
  }

  def onGetJoinedChannels(message: GetJoinedChannelsRequest): Unit = {
    val GetJoinedChannelsRequest(username) = message
    ???
  }

  override def preStart(): Unit = {
    DomainPersistenceManagerActor.acquirePersistenceProvider(self, context, domainFqn) map { provider =>
      chatChannelStore = provider.chatChannelStore
      ()
    } recover {
      case NonFatal(cause) =>
        throw cause
    }
  }
}