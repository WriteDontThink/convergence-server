/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.domain

import akka.cluster.sharding.ShardRegion
import com.convergencelabs.convergence.server.actor.ActorSharding

object DomainActorSharding extends ActorSharding(
  "DomainActorShardRegion",
  "backend",
  classOf[DomainActor]) {

  override val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: DomainMessage ⇒
      (s"${msg.domainFqn.namespace}::${msg.domainFqn.domainId}", msg)
  }

  override def extractShardId(numberOfShards: Int): ShardRegion.ExtractShardId = {
    case msg: DomainMessage =>
      Math.abs(msg.domainFqn.hashCode % numberOfShards).toString
  }
}
