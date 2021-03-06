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

object DomainStatus extends Enumeration {
  type DomainStatus = Value
  val Initializing: DomainStatus = Value("initializing")
  val Error: DomainStatus = Value("error")
  val Online: DomainStatus = Value("online")
  val Offline: DomainStatus = Value("offline")
  val Maintenance: DomainStatus = Value("maintenance")
  val Deleting: DomainStatus = Value("deleting")
}

case class Domain(
  domainFqn: DomainId,
  displayName: String,
  status: DomainStatus.Value,
  statusMessage: String)

case class DomainDatabase(
  database: String,
  username: String,
  password: String,
  adminUsername: String,
  adminPassword: String)
