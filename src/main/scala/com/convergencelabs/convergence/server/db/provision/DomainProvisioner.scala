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

package com.convergencelabs.convergence.server.db.provision

import java.time.temporal.ChronoUnit
import java.time.{Duration => JavaDuration}

import com.convergencelabs.convergence.server.datastore.convergence.DeltaHistoryStore
import com.convergencelabs.convergence.server.datastore.domain.DomainPersistenceProviderImpl
import com.convergencelabs.convergence.server.db.{DatabaseProvider, SingleDatabaseProvider}
import com.convergencelabs.convergence.server.db.provision.DomainProvisioner._
import com.convergencelabs.convergence.server.db.schema.DomainSchemaManager
import com.convergencelabs.convergence.server.domain.{DomainId, JwtKeyPair, JwtUtil, ModelSnapshotConfig}
import com.orientechnologies.orient.core.db.{ODatabaseType, OrientDB, OrientDBConfig}
import com.orientechnologies.orient.core.metadata.security.{ORole, ORule}
import com.orientechnologies.orient.core.metadata.sequence.OSequence
import com.typesafe.config.Config
import grizzled.slf4j.Logging

import scala.util.{Failure, Try}

object DomainProvisioner {
  val DefaultSnapshotConfig: ModelSnapshotConfig = ModelSnapshotConfig(
    snapshotsEnabled = false,
    triggerByVersion = false,
    limitedByVersion = false,
    250,
    1000,
    triggerByTime = false,
    limitedByTime = false,
    JavaDuration.of(0, ChronoUnit.MINUTES),
    JavaDuration.of(0, ChronoUnit.MINUTES))

  val OrientDefaultAdmin = "admin"
  val OrientDefaultReader = "reader"
  val OrientDefaultWriter = "writer"

  val StorageMode = ODatabaseType.PLOCAL
}

class DomainProvisioner(dbProvider: DatabaseProvider, config: Config) extends Logging {

  private[this] val historyStore = new DeltaHistoryStore(dbProvider)
  private[this] val dbBaseUri = config.getString("convergence.persistence.server.uri")
  private[this] val dbRootUsername = config.getString("convergence.persistence.server.admin-username")
  private[this] val dbRootPassword = config.getString("convergence.persistence.server.admin-password")
  private[this] val preRelease = config.getBoolean("convergence.persistence.domain-databases.pre-release")

  def provisionDomain(
    domainFqn:       DomainId,
    dbName:          String,
    dbUsername:      String,
    dbPassword:      String,
    dbAdminUsername: String,
    dbAdminPassword: String,
    anonymousAuth:   Boolean): Try[Unit] = {
    logger.debug(s"Provisioning domain: $dbBaseUri/$dbName")
    createDatabase(dbName) flatMap { _ =>
      setAdminCredentials(dbName, dbAdminUsername, dbAdminPassword)
    } flatMap { _ =>
      val provider = new SingleDatabaseProvider(dbBaseUri, dbName, dbAdminUsername, dbAdminPassword)
      val result = provider
        .connect()
        .flatMap(_ => configureNonAdminUsers(provider, dbUsername, dbPassword))
        .flatMap(_ => installSchema(domainFqn, provider, preRelease))

      // We need to do this no matter what, so we grab the result above, shut down
      // and then return the result.
      logger.debug(s"Disconnecting as admin user: $dbBaseUri/$dbName")
      provider.shutdown()

      result
    } flatMap { _ =>
      initDomain(dbName, dbUsername, dbPassword, anonymousAuth)
    }
  }

  private[this] def createDatabase(dbName: String): Try[Unit] = Try {
    logger.debug(s"Creating domain database: $dbBaseUri/$dbName")
    val orientDb = new OrientDB(dbBaseUri, dbRootUsername, dbRootPassword, OrientDBConfig.defaultConfig())
    orientDb.create(dbName, StorageMode)
    orientDb.close()
    logger.debug(s"Domain database created at: $dbBaseUri/$dbName")
  }

  private[this] def setAdminCredentials(dbName: String, adminUsername: String, adminPassword: String): Try[Unit] = Try {
    logger.debug(s"Updating database admin credentials: $dbBaseUri/$dbName")
    // Orient DB has three default users. admin, reader and writer. They all
    // get created with their passwords equal to their usernames. We want
    // to change the admin and writer and delete the reader.
    val orientDb = new OrientDB(dbBaseUri, dbRootUsername, dbRootPassword, OrientDBConfig.defaultConfig())
    val db = orientDb.open(dbName, dbRootUsername, dbRootPassword)

    // Change the admin username / password and then reconnect
    val adminUser = db.getMetadata.getSecurity.getUser(OrientDefaultAdmin)
    adminUser.setName(adminUsername)
    adminUser.setPassword(adminPassword)
    adminUser.save()

    logger.debug(s"Database admin credentials set, reconnecting: $dbBaseUri/$dbName")

    // Close and reconnect with the new credentials to make sure everything
    // we set properly.
    db.close()
    orientDb.close()
  }

  private[this] def configureNonAdminUsers(dbProvider: DatabaseProvider, dbUsername: String, dbPassword: String): Try[Unit] = {
    dbProvider.tryWithDatabase { db =>
      logger.debug(s"Updating normal user credentials: $dbBaseUri/${db.getName}")

      // Change the username and password of the normal user
      val normalUser = db.getMetadata.getSecurity.getUser(OrientDefaultWriter)
      normalUser.setName(dbUsername)
      normalUser.setPassword(dbPassword)
      normalUser.save()

      // FIXME work around for this: https://github.com/orientechnologies/orientdb/issues/8535
      val writer = db.getMetadata.getSecurity.getRole("writer")
      writer.addRule(ORule.ResourceGeneric.CLASS, OSequence.CLASS_NAME, ORole.PERMISSION_READ + ORole.PERMISSION_UPDATE)
      writer.save()

      logger.debug(s"Deleting 'reader' user credentials: $dbBaseUri/${db.getName}")
      // Delete the reader user since we do not need it.
      db.getMetadata.getSecurity.getUser(OrientDefaultReader).getDocument.delete()
      ()
    }
  }

  private[this] def installSchema(domainFqn: DomainId, dbProvider: DatabaseProvider, preRelease: Boolean): Try[Unit] = {
    dbProvider.withDatabase { db =>
      // FIXME should be use the other actor
      val schemaManager = new DomainSchemaManager(domainFqn, db, historyStore, preRelease)
      logger.debug(s"Installing domain db schema to: $dbBaseUri/${db.getName}")
      schemaManager.install() map { _ =>
        logger.debug(s"Base domain schema created: $dbBaseUri/${db.getName}")
      }
    }
  }

  private[this] def initDomain(dbName: String, username: String, password: String, anonymousAuth: Boolean): Try[Unit] = {
    logger.debug(s"Connecting as normal user to initialize domain: $dbBaseUri/$dbName")
    val provider = new SingleDatabaseProvider(dbBaseUri, dbName, username, password)
    val persistenceProvider = new DomainPersistenceProviderImpl(provider)
    provider
      .connect()
      .flatMap(_ => persistenceProvider.validateConnection())
      .map(_ => persistenceProvider)
  } flatMap {
    persistenceProvider =>
      logger.debug(s"Connected to domain database: $dbBaseUri/$dbName")

      logger.debug(s"Generating admin key: $dbBaseUri/$dbName")
      JwtUtil.createKey().flatMap { rsaJsonWebKey =>
        for {
          publicKey <- JwtUtil.getPublicCertificatePEM(rsaJsonWebKey)
          privateKey <- JwtUtil.getPrivateKeyPEM(rsaJsonWebKey)
        } yield {
          JwtKeyPair(publicKey, privateKey)
        }
      } flatMap { keyPair =>
        logger.debug(s"Created key pair for domain: $dbBaseUri/$dbName")

        logger.debug(s"Initializing domain: $dbBaseUri/$dbName")
        persistenceProvider.configStore.initializeDomainConfig(
          keyPair,
          DefaultSnapshotConfig,
          anonymousAuth)
      } map { _ =>
        logger.debug(s"Domain initialized: $dbBaseUri/$dbName")
        persistenceProvider.shutdown()
      } recoverWith {
        case cause: Exception =>
          logger.error(s"Failure initializing domain: $dbBaseUri/$dbName", cause)
          persistenceProvider.shutdown()
          Failure(cause)
      }
  }

  def destroyDomain(dbName: String): Try[Unit] = Try {
    logger.debug(s"Deleting database at: $dbBaseUri/$dbName")
    val orientDb = new OrientDB(dbBaseUri, dbRootUsername, dbRootPassword, OrientDBConfig.defaultConfig())
    orientDb.drop(dbName)
    orientDb.close()
  }
}
