/*
 * Copyright (c) 2019, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.rapids.spark

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, CodeGenerator, ExprCode, FalseLiteral, JavaCode}
import org.apache.spark.sql.execution.{ColumnarBatchScan, ColumnarToRowExec, SparkPlan}
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.vectorized.{ColumnarBatch, ColumnVector}

class GpuColumnarToRowExec(child: SparkPlan) extends ColumnarToRowExec(child) {

  override def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")
    val numInputBatches = longMetric("numInputBatches")
    val scanTime = longMetric("scanTime")
    val batches = child.executeColumnar()
    batches.mapPartitions {
      (cbIter) => {
        // UnsafeProjection is not serializable so do it on the executor side
        val outputProject = UnsafeProjection.create(output, output)
        new Iterator[InternalRow] {
          var it: java.util.Iterator[InternalRow] = null

          def loadNextBatch: Unit = {
            if (it != null) {
              it = null
            }
            val batchStartNs = System.nanoTime()
            if (cbIter.hasNext) {
              val cb = cbIter.next()
              for (i <- 0 until cb.numCols()) {
                // TODO in the future we should have much better ways of supporting this.
                cb.column(i).asInstanceOf[GpuColumnVector].getBase.ensureOnHost()
              }
              it = cb.rowIterator()
              numInputBatches += 1
              // In order to match the numOutputRows metric in the generated code we update
              // numOutputRows for each batch. This is less accurate than doing it at output
              // because it will over count the number of rows output in the case of a limit,
              // but it is more efficient.
              numOutputRows += cb.numRows()
            }
            scanTime += ((System.nanoTime() - batchStartNs) / (1000 * 1000))
          }

          override def hasNext: Boolean = {
            val itHasNext = it != null && it.hasNext
            if (!itHasNext) {
              loadNextBatch
              it != null && it.hasNext
            } else {
              itHasNext
            }
          }

          override def next(): InternalRow = {
            if (it == null || !it.hasNext) {
              loadNextBatch
            }
            if (it == null) {
              throw new NoSuchElementException()
            }
            it.next()
          }

          // This is to convert the InternalRow to an UnsafeRow. Even though the type is
          // InternalRow some operations downstream operations like collect require it to
          // be UnsafeRow
        }.map(outputProject)
      }
    }
  }

  /**
   * Generate [[ColumnVector]] expressions for our parent to consume as rows.
   * This is called once per [[ColumnVector]] in the batch.
   *
   * This code came unchanged from [[ColumnarBatchScan]] and will hopefully replace it
   * at some point.
   */
  private def genCodeColumnVector(
      ctx: CodegenContext,
      columnVar: String,
      ordinal: String,
      dataType: DataType,
      nullable: Boolean): ExprCode = {
    val javaType = CodeGenerator.javaType(dataType)
    val value = CodeGenerator.getValueFromVector(columnVar, dataType, ordinal)
    val isNullVar = if (nullable) {
      JavaCode.isNullVariable(ctx.freshName("isNull"))
    } else {
      FalseLiteral
    }
    val valueVar = ctx.freshName("value")
    val str = s"columnVector[$columnVar, $ordinal, ${dataType.simpleString}]"
    val code = code"${ctx.registerComment(str)}" + (if (nullable) {
      code"""
        boolean $isNullVar = $columnVar.isNullAt($ordinal);
        $javaType $valueVar = $isNullVar ? ${CodeGenerator.defaultValue(dataType)} : ($value);
      """
    } else {
      code"$javaType $valueVar = $value;"
    })
    ExprCode(code, isNullVar, JavaCode.variable(valueVar, dataType))
  }

  /**
   * Produce code to process the input iterator as [[ColumnarBatch]]es.
   * This produces an [[org.apache.spark.sql.catalyst.expressions.UnsafeRow]] for each row in
   * each batch.
   *
   * This code came almost completely unchanged from [[ColumnarBatchScan]] and will
   * hopefully replace it at some point.
   */
  override protected def doProduce(ctx: CodegenContext): String = {
    // PhysicalRDD always just has one input
    val input = ctx.addMutableState("scala.collection.Iterator", "input",
      v => s"$v = inputs[0];")

    // metrics
    val numOutputRows = metricTerm(ctx, "numOutputRows")
    val numInputBatches = metricTerm(ctx, "numInputBatches")
    val scanTimeMetric = metricTerm(ctx, "scanTime")
    val scanTimeTotalNs =
      ctx.addMutableState(CodeGenerator.JAVA_LONG, "scanTime") // init as scanTime = 0

    val columnarBatchClz = classOf[ColumnarBatch].getName
    val batch = ctx.addMutableState(columnarBatchClz, "batch")

    val idx = ctx.addMutableState(CodeGenerator.JAVA_INT, "batchIdx") // init as batchIdx = 0
    val columnVectorClzs = child.vectorTypes.getOrElse(
      Seq.fill(output.indices.size)(classOf[GpuColumnVector].getName))
    val (colVars, columnAssigns) = columnVectorClzs.zipWithIndex.map {
      case (columnVectorClz, i) =>
        val name = ctx.addMutableState(columnVectorClz, s"colInstance$i")
        (name, s"$name = ($columnVectorClz) $batch.column($i); $name.getBase().ensureOnHost();")
    }.unzip

    val nextBatch = ctx.freshName("nextBatch")
    val nextBatchFuncName = ctx.addNewFunction(nextBatch,
      s"""
         |private void $nextBatch() throws java.io.IOException {
         |  long getBatchStart = System.nanoTime();
         |  if ($input.hasNext()) {
         |    $batch = ($columnarBatchClz)$input.next();
         |    $numOutputRows.add($batch.numRows());
         |    $idx = 0;
         |    ${columnAssigns.mkString("", "\n", "\n")}
         |    ${numInputBatches}.add(1);
         |  }
         |  $scanTimeTotalNs += System.nanoTime() - getBatchStart;
         |}""".stripMargin)

    ctx.currentVars = null
    val rowidx = ctx.freshName("rowIdx")
    val columnsBatchInput = (output zip colVars).map { case (attr, colVar) =>
      genCodeColumnVector(ctx, colVar, rowidx, attr.dataType, attr.nullable)
    }
    val localIdx = ctx.freshName("localIdx")
    val localEnd = ctx.freshName("localEnd")
    val numRows = ctx.freshName("numRows")
    val shouldStop = if (parent.needStopCheck) {
      s"if (shouldStop()) { $idx = $rowidx + 1; return; }"
    } else {
      "// shouldStop check is eliminated"
    }
    s"""
       |if ($batch == null) {
       |  $nextBatchFuncName();
       |}
       |while ($batch != null) {
       |  int $numRows = $batch.numRows();
       |  int $localEnd = $numRows - $idx;
       |  for (int $localIdx = 0; $localIdx < $localEnd; $localIdx++) {
       |    int $rowidx = $idx + $localIdx;
       |    ${consume(ctx, columnsBatchInput).trim}
       |    $shouldStop
       |  }
       |  $idx = $numRows;
       |  $batch = null;
       |  $nextBatchFuncName();
       |}
       |$scanTimeMetric.add($scanTimeTotalNs / (1000 * 1000));
       |$scanTimeTotalNs = 0;
     """.stripMargin
  }

  override def equals(other: Any): Boolean = {
    if (!super.equals(other)) {
      return false
    }
    return other.isInstanceOf[GpuColumnarToRowExec]
  }
}