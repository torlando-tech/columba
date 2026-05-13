package network.columba.app.reticulum.protocol

import network.columba.app.rns.api.model.ConversationLinkResult
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.FailedInterface
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.PropagationState
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.api.model.VoiceCallState

/** Type aliases to disambiguate reticulum-kt types from Columba model types. */
internal typealias NativeIdentity = network.reticulum.identity.Identity
internal typealias NativeDestination = network.reticulum.destination.Destination
internal typealias NativeDestinationType = network.reticulum.common.DestinationType
internal typealias NativeDeliveryMethod = network.reticulum.lxmf.DeliveryMethod
