package com.convergencelabs.server.db.data

import org.scalatest.Matchers
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.WordSpecLike

import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.db.schema.DatabaseSchemaManager
import com.convergencelabs.server.db.schema.DeltaCategory
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx

class DomainImportExportSpec extends WordSpecLike with Matchers {

  "A DomainImport and Export" must {
    "import the correct data" in {
      val url = "memory:DomainImporterSpec-" + System.nanoTime()

      val db = new ODatabaseDocumentTx(url)
      db.activateOnCurrentThread()
      db.create()

      val dbPool = new OPartitionedDatabasePool(url, "admin", "admin")

      val upgrader = new DatabaseSchemaManager(dbPool, DeltaCategory.Domain)
      upgrader.upgradeToLatest()

      val provider = new DomainPersistenceProvider(dbPool)
      provider.validateConnection().success

      val serializer = new DomainScriptSerializer()
      val in = getClass.getResourceAsStream("/com/convergencelabs/server/db/data/import-domain-test.yaml")
      val importScript = serializer.deserialize(in).success.value

      val importer = new DomainImporter(provider, importScript)

      importer.importDomain().success

      val exporter = new DomainExporter(provider)
      val exportedScript = exporter.exportDomain().success.value

      val DomainScript(importConfig, importJwtKeys, importUsers, importCollections, importModels) = importScript
      val DomainScript(exportConfig, exportJwtKeys, exportUsers, exportCollections, exportModels) = exportedScript

      importConfig shouldBe exportConfig
      importJwtKeys.toSet shouldBe exportJwtKeys.toSet
      importCollections.toSet shouldBe exportCollections.toSet
      importModels.toSet shouldBe exportModels.toSet
      importUsers.value.toSet should be(exportUsers.value.toSet)
    }
  }
}