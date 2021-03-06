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

package com.convergencelabs.convergence.server.datastore.domain.mapper

import scala.language.implicitConversions

import com.convergencelabs.convergence.server.datastore.mapper.ODocumentMapper
import com.convergencelabs.convergence.server.domain.model.data.ArrayValue
import com.convergencelabs.convergence.server.domain.model.data.BooleanValue
import com.convergencelabs.convergence.server.domain.model.data.DataValue
import com.convergencelabs.convergence.server.domain.model.data.DoubleValue
import com.convergencelabs.convergence.server.domain.model.data.NullValue
import com.convergencelabs.convergence.server.domain.model.data.ObjectValue
import com.convergencelabs.convergence.server.domain.model.data.StringValue
import com.convergencelabs.convergence.server.domain.model.data.DateValue
import com.orientechnologies.orient.core.record.impl.ODocument

import ArrayValueMapper.ArrayValueToODocument
import ArrayValueMapper.{ DocumentClassName => ArrayValueDocName }
import ArrayValueMapper.ODocumentToArrayValue
import ArrayValueMapper.{ OpDocumentClassName => ArrayOpValueDocName }
import BooleanValueMapper.BooleanValueToODocument
import BooleanValueMapper.{ DocumentClassName => BooleanValueDocName }
import BooleanValueMapper.ODocumentToBooleanValue
import BooleanValueMapper.{ OpDocumentClassName => BooleanOpValueDocName }
import DoubleValueMapper.{ DocumentClassName => DoubleValueDocName }
import DoubleValueMapper.DoubleValueToODocument
import DoubleValueMapper.ODocumentToDoubleValue
import DoubleValueMapper.{ OpDocumentClassName => DoubleOpValueDocName }
import NullValueMapper.{ DocumentClassName => NullValueDocName }
import NullValueMapper.NullValueToODocument
import NullValueMapper.ODocumentToNullValue
import NullValueMapper.{ OpDocumentClassName => NullOpValueDocName }
import ObjectValueMapper.{ DocumentClassName => ObjectValueDocName }
import ObjectValueMapper.ODocumentToObjectValue
import ObjectValueMapper.ObjectValueToODocument
import ObjectValueMapper.{ OpDocumentClassName => ObjectOpValueDocName }
import StringValueMapper.{ DocumentClassName => StringValueDocName }
import StringValueMapper.ODocumentToStringValue
import StringValueMapper.{ OpDocumentClassName => StringOpValueDocName }
import StringValueMapper.StringValueToODocument
import DateValueMapper.{ DocumentClassName => DateValueDocName }
import DateValueMapper.{ OpDocumentClassName => DateOpValueDocName }
import DateValueMapper.DateValueToODocument
import DateValueMapper.ODocumentToDateValue

object DataValueMapper extends ODocumentMapper {

  private[domain] implicit class DataValueToODocument(val value: DataValue) extends AnyVal {
    def asODocument: ODocument = dataValueToODocument(value)
  }

  private[domain] implicit def dataValueToODocument(data: DataValue): ODocument = {
    data match {
      case data: ObjectValue => data.asODocument
      case data: ArrayValue => data.asODocument
      case data: StringValue => data.asODocument
      case data: BooleanValue => data.asODocument
      case data: DoubleValue => data.asODocument
      case data: NullValue => data.asODocument
      case data: DateValue => data.asODocument
    }
  }

  private[domain] implicit class ODocumentToDataValue(val d: ODocument) extends AnyVal {
    def asDataValue: DataValue = oDocumentToDataValue(d)
  }

  private[domain] implicit def oDocumentToDataValue(doc: ODocument): DataValue = {
    doc.getClassName match {
      case ObjectValueDocName | ObjectOpValueDocName => doc.asObjectValue
      case ArrayValueDocName | ArrayOpValueDocName => doc.asArrayValue
      case StringValueDocName | StringOpValueDocName => doc.asStringValue
      case BooleanValueDocName | BooleanOpValueDocName => doc.asBooleanValue
      case DoubleValueDocName | DoubleOpValueDocName => doc.asDoubleValue
      case NullValueDocName | NullOpValueDocName => doc.asNullValue
      case DateValueDocName | DateOpValueDocName => doc.asDateValue
    }
  }
}
