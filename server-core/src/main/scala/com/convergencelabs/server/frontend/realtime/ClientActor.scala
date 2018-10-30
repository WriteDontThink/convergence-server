package com.convergencelabs.server.frontend.realtime

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import com.convergencelabs.server.ProtocolConfiguration
import com.convergencelabs.server.db.provision.DomainProvisionerActor.DomainDeleted
import com.convergencelabs.server.db.provision.DomainProvisionerActor.domainTopic
import com.convergencelabs.server.domain.AnonymousAuthRequest
import com.convergencelabs.server.domain.AuthenticationError
import com.convergencelabs.server.domain.AuthenticationFailure
import com.convergencelabs.server.domain.AuthenticationRequest
import com.convergencelabs.server.domain.AuthenticationResponse
import com.convergencelabs.server.domain.AuthenticationSuccess
import com.convergencelabs.server.domain.ClientDisconnected
import com.convergencelabs.server.domain.DomainActorSharding
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.HandshakeFailureException
import com.convergencelabs.server.domain.HandshakeRequest
import com.convergencelabs.server.domain.HandshakeSuccess
import com.convergencelabs.server.domain.JwtAuthRequest
import com.convergencelabs.server.domain.PasswordAuthRequest
import com.convergencelabs.server.domain.PresenceServiceActor.PresenceRequest
import com.convergencelabs.server.domain.PresenceServiceActor.UserPresence
import com.convergencelabs.server.domain.ReconnectTokenAuthRequest
import com.convergencelabs.server.domain.activity.ActivityActorSharding
import com.convergencelabs.server.domain.model.SessionKey
import com.convergencelabs.server.util.concurrent.AskFuture

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.cluster.pubsub.DistributedPubSubMediator.SubscribeAck
import akka.http.scaladsl.model.RemoteAddress
import akka.util.Timeout

object ClientActor {
  def props(
    domainFqn: DomainFqn,
    protocolConfig: ProtocolConfiguration,
    remoteHost: RemoteAddress,
    userAgent: String): Props = Props(
    new ClientActor(
      domainFqn,
      protocolConfig,
      remoteHost,
      userAgent))
}

class ClientActor(
  private[this] val domainFqn: DomainFqn,
  private[this] val protocolConfig: ProtocolConfiguration,
  private[this] val remoteHost: RemoteAddress,
  private[this] val userAgent: String)
  extends Actor with ActorLogging {
  
  import akka.pattern.ask

  type MessageHandler = PartialFunction[ProtocolMessageEvent, Unit]

  // FIXME hard-coded (used for auth and handshake)
  private[this] implicit val requestTimeout = Timeout(protocolConfig.defaultRequestTimeout)
  private[this] implicit val ec = context.dispatcher

  private[this] var connectionActor: ActorRef = _
  
  val mediator = DistributedPubSub(context.system).mediator
  mediator ! Subscribe(domainTopic(domainFqn), self)

  // FIXME this should probably be for handshake and auth.
  private[this] val handshakeTimeoutTask =
    context.system.scheduler.scheduleOnce(protocolConfig.handshakeTimeout) {
      log.debug(s"${domainFqn}: Client handshaked timeout")
      Option(connectionActor) match {
        case Some(connection) => connection ! CloseConnection
        case None =>
      }
      context.stop(self)
    }

  private[this] var modelClient: ActorRef = _
  private[this] var userClient: ActorRef = _
  private[this] var activityClient: ActorRef = _
  private[this] var presenceClient: ActorRef = _
  private[this] var chatClient: ActorRef = _
  private[this] var historyClient: ActorRef = _

  private[this] var modelQueryActor: ActorRef = _
  private[this] var modelStoreActor: ActorRef = _
  private[this] var operationStoreActor: ActorRef = _
  private[this] var userServiceActor: ActorRef = _
  private[this] var presenceServiceActor: ActorRef = _
  private[this] var chatLookupActor: ActorRef = _
  private[this] var sessionId: String = _
  private[this] var reconnectToken: String = _

  private[this] var protocolConnection: ProtocolConnection = _

  private[this] val domainRegion = DomainActorSharding.shardRegion(context.system)

  //
  // Receive methods
  //
  def receive: Receive = receiveWhileConnecting orElse receiveCommon

  private[this] def receiveWhileConnecting: Receive = {
    case WebSocketOpened(connectionActor) =>
      this.connectionActor = connectionActor
      this.protocolConnection = new ProtocolConnection(
        self,
        connectionActor,
        protocolConfig,
        context.system.scheduler,
        context.dispatcher)
      context.become(receiveWhileHandshaking)
  }

  private[this] def receiveIncomingTextMessage: Receive = {
    case IncomingTextMessage(message) =>
      this.protocolConnection.onIncomingMessage(message) match {
        case Success(Some(event)) =>
          messageHandler(event)
        case Success(None) =>
        // No Op
        case Failure(cause) =>
          invalidMessage(cause)
      }
  }

  private[this] def receiveOutgoing: Receive = {
    case message: OutgoingProtocolNormalMessage => onOutgoingMessage(message)
    case message: OutgoingProtocolRequestMessage => onOutgoingRequest(message)
  }

  private[this] def receiveCommon: Receive = {
    case WebSocketClosed =>
      log.debug(s"${domainFqn}: WebSocketClosed for session: ${sessionId}")
      onConnectionClosed()
    case WebSocketError(cause) => 
      onConnectionError(cause)
    case SubscribeAck(_) =>
      // no-op
    case DomainDeleted(domainFqn) =>
      domainDeleted()
    case x: Any =>
      invalidMessage(x)
  }

  private[this] val receiveHandshakeSuccess: Receive = {
    case handshakeSuccess: InternalHandshakeSuccess =>
      handleHandshakeSuccess(handshakeSuccess)
  }

  private[this] val receiveWhileHandshaking =
    receiveHandshakeSuccess orElse
      receiveIncomingTextMessage orElse
      receiveCommon

  private[this] val receiveAuthentiationSuccess: Receive = {
    case authSuccess: InternalAuthSuccess =>
      handleAuthenticationSuccess(authSuccess)
  }

  private[this] val receiveWhileAuthenticating =
    receiveAuthentiationSuccess orElse
      receiveIncomingTextMessage orElse
      receiveCommon

  private[this] val receiveWhileAuthenticated =
    receiveIncomingTextMessage orElse
      receiveOutgoing orElse
      receiveCommon

  private[this] var messageHandler: MessageHandler = handleHandshakeMessage

  private[this] def handleHandshakeMessage: MessageHandler = {
    case RequestReceived(message, replyCallback) if message.isInstanceOf[HandshakeRequestMessage] => {
      handshake(message.asInstanceOf[HandshakeRequestMessage], replyCallback)
    }
    case x: Any => invalidMessage(x)
  }

  private[this] def handleAuthentationMessage: MessageHandler = {
    case RequestReceived(message, replyCallback) if message.isInstanceOf[AuthenticationRequestMessage] => {
      authenticate(message.asInstanceOf[AuthenticationRequestMessage], replyCallback)
    }
    case x: Any => invalidMessage(x)
  }

  private[this] def handleMessagesWhenAuthenticated: MessageHandler = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[HandshakeRequestMessage] => invalidMessage(message)
    case RequestReceived(message, replyPromise) if message.isInstanceOf[AuthenticationRequestMessage] => invalidMessage(message)

    case message: MessageReceived => onMessageReceived(message)
    case message: RequestReceived => onRequestReceived(message)
  }

  
  //
  // Handshaking
  //

  private[this] def handshake(request: HandshakeRequestMessage, cb: ReplyCallback): Unit = {
    val canceled = handshakeTimeoutTask.cancel()
    if (canceled) {
      log.debug(s"${domainFqn}: Handhsaking with DomainActor")
      val future = domainRegion ? HandshakeRequest(domainFqn, self, request.r, request.k)
      future.mapResponse[HandshakeSuccess] onComplete {
        case Success(success) => {
          log.debug(s"${domainFqn}: Handhsake success")
          self ! InternalHandshakeSuccess(success, cb)
        }
        case Failure(HandshakeFailureException(code, details)) => {
          log.debug(s"${domainFqn}: Handshake failure: {code: '${code}', details: '${details}'}")
          cb.reply(HandshakeResponseMessage(false, Some(ErrorData(code, details)), Some(false), None))
          this.connectionActor ! CloseConnection
          self ! PoisonPill
        }
        case Failure(cause) => {
          log.error(cause, s"${domainFqn}: Error handshaking with DomainActor")
          cb.reply(HandshakeResponseMessage(false, Some(ErrorData("unknown", "uknown error")), Some(true), None))
          this.connectionActor ! CloseConnection
          self ! PoisonPill
        }
      }
    } else {
      log.debug(s"${domainFqn}: Not handhsaking with domain because handshake timeout occured")
    }
  }

  private[this] def handleHandshakeSuccess(success: InternalHandshakeSuccess): Unit = {
    val InternalHandshakeSuccess(HandshakeSuccess(
       modelQueryActor, modelStoreActor, operationStoreActor, userActor, presenceActor, chatLookupActor),
      cb) = success
    this.modelStoreActor = modelStoreActor
    this.operationStoreActor = operationStoreActor
    this.modelQueryActor = modelQueryActor
    this.userServiceActor = userActor
    this.presenceServiceActor = presenceActor
    this.chatLookupActor = chatLookupActor
    log.debug(s"${domainFqn}: Sending handshake response to client")
    cb.reply(HandshakeResponseMessage(true, None, None, Some(ProtocolConfigData(true))))
    this.messageHandler = handleAuthentationMessage
    context.become(receiveWhileAuthenticating)
  }
  
  //
  // Authentication
  //
  
  private[this] def authenticate(requestMessage: AuthenticationRequestMessage, cb: ReplyCallback): Unit = {
    val authCredentials = requestMessage match {
      case PasswordAuthRequestMessage(username, password) =>
        PasswordAuthRequest(username, password)
      case TokenAuthRequestMessage(token) =>
        JwtAuthRequest(token)
      case ReconnectTokenAuthRequestMessage(token) =>
        ReconnectTokenAuthRequest(token)
      case AnonymousAuthRequestMessage(displayName) =>
        AnonymousAuthRequest(displayName)
    }

    val authRequest = AuthenticationRequest(
      domainFqn,
      self,
      remoteHost.toString,
      "javascript",
      "unknown",
      userAgent,
      authCredentials)

    // FIXME if authentication fails we should probably stop the actor
    // and or shut down the connection?
    (this.domainRegion ? authRequest).mapResponse[AuthenticationResponse] onComplete {
      case Success(AuthenticationSuccess(username, sk, reconnectToken)) =>
        getPresenceAfterAuth(username, sk, reconnectToken, cb)
      case Success(AuthenticationFailure) =>
        cb.reply(AuthenticationResponseMessage(false, None, None, None, None))
      case Failure(cause) =>
        log.error(cause, s"Error authenticating user for domain ${domainFqn}")
        cb.reply(AuthenticationResponseMessage(false, None, None, None, None))
    }
  }

  private[this] def getPresenceAfterAuth(username: String, sk: SessionKey, reconnectToken: String, cb: ReplyCallback) {
    val future = this.presenceServiceActor ? PresenceRequest(List(username))
    future.mapTo[List[UserPresence]] onComplete {
      case Success(first :: nil) =>
        self ! InternalAuthSuccess(username, sk, reconnectToken, first, cb)
      case _ =>
        cb.reply(AuthenticationResponseMessage(false, None, None, None, None))
    }
  }

  private[this] def handleAuthenticationSuccess(message: InternalAuthSuccess): Unit = {
    val InternalAuthSuccess(username, sk, reconnectToken, presence, cb) = message
    this.sessionId = sk.serialize();
    this.reconnectToken = reconnectToken;
    this.modelClient = context.actorOf(ModelClientActor.props(domainFqn, sk, modelQueryActor, requestTimeout))
    this.userClient = context.actorOf(IdentityClientActor.props(userServiceActor))
    this.chatClient = context.actorOf(ChatClientActor.props(domainFqn, chatLookupActor, sk, requestTimeout))
    this.activityClient = context.actorOf(ActivityClientActor.props(
        ActivityActorSharding.shardRegion(this.context.system), 
        domainFqn,
        sk))
    this.presenceClient = context.actorOf(PresenceClientActor.props(presenceServiceActor, sk))
    this.historyClient = context.actorOf(HistoricModelClientActor.props(sk, domainFqn, modelStoreActor, operationStoreActor));
    this.messageHandler = handleMessagesWhenAuthenticated

    cb.reply(AuthenticationResponseMessage(true, Some(username), Some(sk.serialize()), Some(reconnectToken), Some(presence.state)))
    context.become(receiveWhileAuthenticated)
  }
  
  //
  // Incoming / Outgoing Messages
  //

  private[this] def onOutgoingMessage(message: OutgoingProtocolNormalMessage): Unit = {
    protocolConnection.send(message)
  }

  private[this] def onOutgoingRequest(message: OutgoingProtocolRequestMessage): Unit = {
    val askingActor = sender
    val f = protocolConnection.request(message)
    f.mapTo[IncomingProtocolResponseMessage] onComplete {
      case Success(response) =>
        askingActor ! response
      case Failure(cause) =>
        this.protocolConnection.send(ErrorMessage("invalid_response", "Error processing a response", Map()))
        this.connectionActor ! PoisonPill
        this.onConnectionClosed()
    }
  }

  private[this] def onMessageReceived(message: MessageReceived): Unit = {
    message match {
      case MessageReceived(x: IncomingModelNormalMessage) =>
        modelClient.forward(message)
      case MessageReceived(x: IncomingActivityMessage) =>
        activityClient.forward(message)
      case MessageReceived(x: IncomingPresenceMessage) =>
        presenceClient.forward(message)
      case MessageReceived(x: IncomingChatMessage) =>
        chatClient.forward(message)
      case message: Any =>
        // TODO send an error back
    }
  }

  private[this] def onRequestReceived(message: RequestReceived): Unit = {
    message match {
      case RequestReceived(x: IncomingModelRequestMessage, _) =>
        modelClient.forward(message)
      case RequestReceived(x: IncomingIdentityMessage, _) =>
        userClient.forward(message)
      case RequestReceived(x: IncomingActivityMessage, _) =>
        activityClient.forward(message)
      case RequestReceived(x: IncomingPresenceMessage, _) =>
        presenceClient.forward(message)
      case RequestReceived(x: IncomingChatMessage, _) =>
        chatClient.forward(message)
      case RequestReceived(x: IncomingHistoricalModelRequestMessage, _) =>
        historyClient.forward(message)
      case RequestReceived(x: IncomingPermissionsMessage, _) =>
        val idType: IdType.Value = IdType(x.p)
        if (idType == IdType.Chat) {
          chatClient.forward(message)
        }
      case message: Any =>
        // TODO send an error back
    }
  }
  
  //
  // Error handling
  //

  private[this] def onConnectionClosed(): Unit = {
    log.info(s"${domainFqn}: Sending disconnect to domain and stopping: ${sessionId}")
    domainRegion ! ClientDisconnected(domainFqn, self)
    context.stop(self)
  }

  private[this] def onConnectionError(cause: Throwable): Unit = {
    log.debug(s"${domainFqn}: Connection Error for: ${sessionId} - " + cause.getMessage)
    domainRegion ! ClientDisconnected(domainFqn, self)
    context.stop(self)
  }

  private[this] def invalidMessage(message: Any): Unit = {
    log.error(s"${domainFqn}: Invalid message: '${message}'")
    connectionActor ! CloseConnection
    context.stop(self)
  }
  
  private[this] def domainDeleted(): Unit = {
    log.error(s"${domainFqn}: Domain deleted shutting down")
    connectionActor ! CloseConnection
    context.stop(self)
  }

  override def postStop(): Unit = {
    log.debug(s"ClientActor(${domainFqn}/${this.sessionId}): Stopped")
    if (!handshakeTimeoutTask.isCancelled) {
      handshakeTimeoutTask.cancel()
    }
    protocolConnection.dispose()
  }
}

case class InternalAuthSuccess(username: String, sk: SessionKey, reconnectToken: String, presence: UserPresence, cb: ReplyCallback)
case class InternalHandshakeSuccess(handshakeSuccess: HandshakeSuccess, cb: ReplyCallback)
