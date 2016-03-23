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

package org.apache.spark.ml.feature

import org.apache.hadoop.fs.Path

import org.apache.spark.SparkException
import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.ml.{ModelEstimator, Estimator, Model, Transformer}
import org.apache.spark.ml.attribute.{Attribute, NominalAttribute}
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.OpenHashMap

/**
 * Base trait for [[StringIndexer]].
 */
private[feature] trait StringIndexerBase extends Params
  with HasInputCol with HasOutputCol with HasHandleInvalid with HasStringIndex {

  /** Validates and transforms the input schema. */
  protected def validateAndTransformSchema(schema: StructType): StructType = {
    val inputColName = $(inputCol)
    val inputDataType = schema(inputColName).dataType
    require(inputDataType == StringType || inputDataType.isInstanceOf[NumericType],
      s"The input column $inputColName must be either string type or numeric type, " +
        s"but got $inputDataType.")
    val inputFields = schema.fields
    val outputColName = $(outputCol)
    require(inputFields.forall(_.name != outputColName),
      s"Output column $outputColName already exists.")
    val attr = NominalAttribute.defaultAttr.withName($(outputCol))
    val outputFields = inputFields :+ attr.toStructField()
    StructType(outputFields)
  }
}

private[feature] trait HasStringIndex extends Params { self: HasInputCol => // This is just for doc
  /** @group setParam */
  def setLabels(value: Array[String]): this.type = set(labels, value)

  /**
    * Optional param for array of labels specifying index-string mapping.
    *
    * Default: Empty array, in which case [[inputCol]] metadata is used for labels.
    *
    * @group param
    */
  final val labels: StringArrayParam = new StringArrayParam(this, "labels",
    "Optional array of labels specifying index-string mapping." +
      " If not provided or if empty, then metadata from inputCol is used instead.")
  setDefault(labels, Array.empty[String])

  /** @group getParam */
  final def getLabels: Array[String] = $(labels)

  // A reference to the array used to compute the labels.
  private[this] var cachedArray: Array[String] = null

  // A cache of the indexes.
  private[this] var cachedLabelToIndex: OpenHashMap[String, Double] = null

  protected def labelToIndex: OpenHashMap[String, Double] = this.synchronized {
    // Do reference equality to check if the label has been changed.
    val currentArray = $(labels)
    if (cachedArray == null || ! cachedArray.eq(currentArray)) {
      cachedArray = currentArray
      val n = currentArray.length
      cachedLabelToIndex = new OpenHashMap[String, Double](n)
      var i = 0
      while (i < n) {
        cachedLabelToIndex.update(currentArray(i), i)
        i += 1
      }
    }
    cachedLabelToIndex
  }

}

/**
 * :: Experimental ::
 * A label indexer that maps a string column of labels to an ML column of label indices.
 * If the input column is numeric, we cast it to string and index the string values.
 * The indices are in [0, numLabels), ordered by label frequencies.
 * So the most frequent label gets index 0.
 *
 * @see [[IndexToString]] for the inverse transformation
 */
@Experimental
class StringIndexer(override val uid: String) extends ModelEstimator[StringIndexer]
  with Model[StringIndexer] with StringIndexerBase with DefaultParamsWritable
  with Transformer {

  def this() = this(Identifiable.randomUID("strIdx"))

  // ***** Java bytecode compatibility methods *****

  /** @group setParam */
  def setHandleInvalid(value: String): this.type = set(handleInvalid, value)
  setDefault(handleInvalid, "error")

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  final override def copy(extra: ParamMap): StringIndexer = super.copy(extra)

  // *** Fitting and transform ***

  override def fit(dataset: DataFrame): StringIndexer = {
    val counts = dataset.select(col($(inputCol)).cast(StringType))
      .rdd
      .map(_.getString(0))
      .countByValue()
    val labels = counts.toSeq.sortBy(-_._2).map(_._1).toArray
    setLabels(labels)
    this
  }

  override def transform(dataset: DataFrame): DataFrame = {
    if (!dataset.schema.fieldNames.contains($(inputCol))) {
      logInfo(s"Input column ${$(inputCol)} does not exist during transformation. " +
        "Skip StringIndexerModel.")
      return dataset
    }
    validateAndTransformSchema(dataset.schema)

    val l2i = labelToIndex

    val indexer = udf { label: String =>
      if (l2i.contains(label)) {
        l2i(label)
      } else {
        throw new SparkException(s"Unseen label: $label.")
      }
    }

    val metadata = NominalAttribute.defaultAttr
      .withName($(inputCol)).withValues(getLabels).toMetadata()
    // If we are skipping invalid records, filter them out.
    val filteredDataset = getHandleInvalid match {
      case "skip" => {
        val filterer = udf { label: String =>
          labelToIndex.contains(label)
        }
        dataset.where(filterer(dataset($(inputCol))))
      }
      case _ => dataset
    }
    filteredDataset.select(col("*"),
      indexer(dataset($(inputCol)).cast(StringType)).as($(outputCol), metadata))
  }

  override def transformSchema(schema: StructType): StructType = {
    if (schema.fieldNames.contains($(inputCol))) {
      validateAndTransformSchema(schema)
    } else {
      // If the input column does not exist during transformation, we skip StringIndexerModel.
      schema
    }
  }
}

@Since("1.6.0")
object StringIndexer extends DefaultParamsReadable[StringIndexer] {

  @Since("1.6.0")
  override def load(path: String): StringIndexer = super.load(path)

  def apply(uid: String, labels: Array[String]): StringIndexer = {
    new StringIndexer(uid).setLabels(labels)
  }
}

/**
 * :: Experimental ::
 * A [[Transformer]] that maps a column of indices back to a new column of corresponding
 * string values.
 * The index-string mapping is either from the ML attributes of the input column,
 * or from user-supplied labels (which take precedence over ML attributes).
 *
 * @see [[StringIndexer]] for converting strings into indices
 */
@Experimental
class IndexToString private[ml] (override val uid: String)
  extends Transformer with HasInputCol with HasOutputCol with DefaultParamsWritable {

  def this() =
    this(Identifiable.randomUID("idxToStr"))

  /** @group setParam */
  def setInputCol(value: String): this.type = set(inputCol, value)

  /** @group setParam */
  def setOutputCol(value: String): this.type = set(outputCol, value)

  /** @group setParam */
  def setLabels(value: Array[String]): this.type = set(labels, value)

  /**
   * Optional param for array of labels specifying index-string mapping.
   *
   * Default: Empty array, in which case [[inputCol]] metadata is used for labels.
    *
    * @group param
   */
  final val labels: StringArrayParam = new StringArrayParam(this, "labels",
    "Optional array of labels specifying index-string mapping." +
      " If not provided or if empty, then metadata from inputCol is used instead.")
  setDefault(labels, Array.empty[String])

  /** @group getParam */
  final def getLabels: Array[String] = $(labels)

  override def transformSchema(schema: StructType): StructType = {
    val inputColName = $(inputCol)
    val inputDataType = schema(inputColName).dataType
    require(inputDataType.isInstanceOf[NumericType],
      s"The input column $inputColName must be a numeric type, " +
        s"but got $inputDataType.")
    val inputFields = schema.fields
    val outputColName = $(outputCol)
    require(inputFields.forall(_.name != outputColName),
      s"Output column $outputColName already exists.")
    val outputFields = inputFields :+ StructField($(outputCol), StringType)
    StructType(outputFields)
  }

  override def transform(dataset: DataFrame): DataFrame = {
    val inputColSchema = dataset.schema($(inputCol))
    // If the labels array is empty use column metadata
    val values = if ($(labels).isEmpty) {
      Attribute.fromStructField(inputColSchema)
        .asInstanceOf[NominalAttribute].values.get
    } else {
      $(labels)
    }
    val indexer = udf { index: Double =>
      val idx = index.toInt
      if (0 <= idx && idx < values.length) {
        values(idx)
      } else {
        throw new SparkException(s"Unseen index: $index ??")
      }
    }
    val outputColName = $(outputCol)
    dataset.select(col("*"),
      indexer(dataset($(inputCol)).cast(DoubleType)).as(outputColName))
  }

  override def copy(extra: ParamMap): IndexToString = {
    defaultCopy(extra)
  }
}

@Since("1.6.0")
object IndexToString extends DefaultParamsReadable[IndexToString] {

  @Since("1.6.0")
  override def load(path: String): IndexToString = super.load(path)
}
