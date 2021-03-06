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

package com.convergencelabs.convergence.server.domain.model.reference

import com.convergencelabs.convergence.server.domain.DomainUserSessionId

import scala.collection.mutable.ListBuffer

class ReferenceMap {

  // stored by sessionId first, then key.
  private[this] val references =
    collection.mutable.Map[DomainUserSessionId, collection.mutable.Map[String, ModelReference[_]]]()

  def put(reference: ModelReference[_]): Unit = {
    val session: DomainUserSessionId = reference.session
    val key: String = reference.key;

    val sessionRefs = this.references.get(session) match {
      case Some(map) => map
      case None =>
        this.references(session) = collection.mutable.Map[String, ModelReference[_]]()
        this.references(session)
    }

    if (sessionRefs.contains(key)) {
      throw new Error("Model reference already exists");
    }

    sessionRefs(key) = reference
  }

  def get(session: DomainUserSessionId, key: String): Option[ModelReference[_]] = {
    this.references.get(session).flatMap { sr => sr.get(key) }
  }

  def getAll(): Set[ModelReference[_]] = {
    val buffer = ListBuffer[ModelReference[_]]()
    references.foreach {
      case (_, sessionRefs) =>
        sessionRefs.foreach {
          case (_, ref) =>
            buffer += ref
        }
    }
    buffer.toSet
  }

  def removeAll(): Unit = {
    this.references.clear()
  }

  def remove(session: DomainUserSessionId, key: String): Option[ModelReference[_]] = {
    val result = this.get(session, key)
    if (result.isDefined) {
      references(session) -= key
    }
    result
  }

  def removeBySession(session: DomainUserSessionId): Unit = {
    references -= session
  }
}
