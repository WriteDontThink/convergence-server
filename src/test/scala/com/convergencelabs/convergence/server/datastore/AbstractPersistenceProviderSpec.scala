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

package com.convergencelabs.convergence.server.datastore

import scala.util.Failure
import scala.util.Success
import org.mockito.Mockito
import org.scalatest.Matchers
import org.scalatest.WordSpec
import org.scalatest.mockito.MockitoSugar
import com.convergencelabs.convergence.server.db.DatabaseProvider
import org.scalactic.source.Position.apply

class AbstractPersistenceProviderSpec extends WordSpec with MockitoSugar with Matchers {
  "A AbstractPersistenceProvider" when {
    "validating the connection" must {
      "return true if a connection can be aquired and closed" in {
        val pool = mock[DatabaseProvider]
        Mockito.when(pool.validateConnection()).thenReturn(Success(()))
        val provider = new AbstractPersistenceProvider(pool) {}
        provider.validateConnection() shouldBe Success(())
      }

      "return false if a connection can not be aquired and closed" in {
        val pool = mock[DatabaseProvider]
        val cause = new IllegalStateException("Induced")
        Mockito.when(pool.validateConnection()).thenReturn(Failure(cause))
        val provider = new AbstractPersistenceProvider(pool) {}
        provider.validateConnection() shouldBe Failure(cause)
      }
    }

    "shutting down" must {
      "close the pool when shutting down the provider" in {
        val pool = mock[DatabaseProvider]
        val provider = new AbstractPersistenceProvider(pool) {}
        provider.shutdown()
        Mockito.verify(pool, Mockito.times(1)).shutdown()
      }
    }
  }
}
