/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.BroadcastMode
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.metric.LongSQLMetric


trait HashSemiJoin {
  self: SparkPlan =>
  val leftKeys: Seq[Expression]
  val rightKeys: Seq[Expression]
  val left: SparkPlan
  val right: SparkPlan
  val condition: Option[Expression]

  override def output: Seq[Attribute] = left.output

  protected def leftKeyGenerator: Projection =
    UnsafeProjection.create(leftKeys, left.output)

  protected def rightKeyGenerator: Projection =
    UnsafeProjection.create(rightKeys, right.output)

  @transient private lazy val boundCondition =
    newPredicate(condition.getOrElse(Literal(true)), left.output ++ right.output)

  protected def hashSemiJoin(
      streamIter: Iterator[InternalRow],
      hashedRelation: HashedRelation,
      numOutputRows: LongSQLMetric): Iterator[InternalRow] = {
    val joinKeys = leftKeyGenerator
    val joinedRow = new JoinedRow
    streamIter.filter { current =>
      val key = joinKeys(current)
      lazy val rowBuffer = hashedRelation.get(key)
      val r = !key.anyNull && rowBuffer != null && (condition.isEmpty || rowBuffer.exists {
        (row: InternalRow) => boundCondition(joinedRow(current, row))
      })
      if (r) numOutputRows += 1
      r
    }
  }
}
