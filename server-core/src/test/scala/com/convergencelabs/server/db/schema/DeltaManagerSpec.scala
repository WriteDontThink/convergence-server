package com.convergencelabs.server.db.schema

import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.TryValues._

class DeltaManagerSpec extends WordSpecLike with Matchers {

  "DeltaManager" when {
    
    "missing a released delta" must {
      "not validate" in {
        val manager = new DeltaManager(Some("/missingReleasedDelta/"))
        val manifest = manager.manifest(DeltaCategory.Convergence).success.value
        manifest.validate().failure
      }
    }
    
    "missing a non released delta" must {
      "not validate" in {
        val manager = new DeltaManager(Some("/missingNonReleasedDelta/"))
        val manifest = manager.manifest(DeltaCategory.Convergence).success.value
        manifest.validate().failure
      }
    }
    
    "having a bad hash" must {
      "not validate" in {
        val manager = new DeltaManager(Some("/badHashDelta/"))
        val manifest = manager.manifest(DeltaCategory.Convergence).success.value
        manifest.validate().failure
      }
    }
    
    "processing good deltas a bad hash" must {
      "return the correct maxDelta" in {
        val manager = new DeltaManager(None)
        val manifest = manager.manifest(DeltaCategory.Convergence).success.value
        manifest.maxDelta() shouldBe 3
      }
      
      "return the correct maxReleasedDelta" in {
        val manager = new DeltaManager(None)
        val manifest = manager.manifest(DeltaCategory.Convergence).success.value
        manifest.maxReleasedDelta() shouldBe 2
      }
    }
  }
}