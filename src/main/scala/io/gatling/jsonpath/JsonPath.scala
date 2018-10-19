/*
 * Copyright 2011-2018 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.jsonpath

import java.util.{ List => JList, Map => JMap }

import scala.collection.JavaConverters._
import scala.math.abs

import io.gatling.jsonpath.AST._

case class JPError(reason: String)

object JsonPath {
  private val JsonPathParser = ThreadLocal.withInitial[Parser](() => new Parser)

  def compile(query: String): Either[JPError, JsonPath] =
    JsonPathParser.get.compile(query) match {
      case Parser.Success(q, _) => Right(new JsonPath(q))
      case ns: Parser.NoSuccess => Left(JPError(ns.msg))
    }

  def query(query: String, jsonObject: Any, acceptMissingFields: Boolean): Either[JPError, Iterator[Any]] =
    compile(query).right.map(_.query(jsonObject, acceptMissingFields))
}

class JsonPath(path: List[PathToken]) {
  def query(jsonObject: Any, acceptMissingFields: Boolean): Iterator[Any] = new JsonPathWalker(jsonObject, path, acceptMissingFields).walk()
}

class JsonPathWalker(rootNode: Any, fullPath: List[PathToken], acceptMissingFields: Boolean) {

  def walk(): Iterator[Any] = walk(rootNode, fullPath, acceptMissingFields)

  private[this] def walk(node: Any, path: List[PathToken], acceptMissingFields: Boolean): Iterator[Any] =
    path match {
      case head :: tail => walk1(node, head, acceptMissingFields).flatMap(walk(_, tail, acceptMissingFields))
      case _            => Iterator.single(node)
    }

  private[this] def walk1(node: Any, query: PathToken, acceptMissingFields: Boolean): Iterator[Any] =
    query match {
      case RootNode    => Iterator.single(rootNode)

      case CurrentNode => Iterator.single(node)

      case Field(name) => node match {
        case obj: JMap[_, _] if obj.containsKey(name) => Iterator.single(obj.get(name))
        case _                                        => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case RecursiveField(name) => recFieldFilter(node, name)

      case MultiField(fieldNames) => node match {
        case obj: JMap[_, _] =>
          // don't use collect on iterator with filter causes (executed twice)
          fieldNames.iterator.filter(obj.containsKey).map(obj.get)
        case _ => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case AnyField => node match {
        case obj: JMap[_, _] => obj.values.iterator.asScala
        case _               => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case ArraySlice(None, None, 1) => node match {
        case array: JList[_] => array.asScala.iterator
        case _               => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case ArraySlice(start, stop, step) => node match {
        case array: JList[_] => sliceArray(array, start, stop, step)
        case _               => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case ArrayRandomAccess(indices) => node match {
        case array: JList[_] => indices.iterator.collect {
          case i if i >= 0 && i < array.size  => array.get(i)
          case i if i < 0 && i >= -array.size => array.get(i + array.size)
          case _                              => if (acceptMissingFields) null
        }
        case _ => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

      case RecursiveFilterToken(filterToken) => recFilter(node, filterToken, acceptMissingFields)

      case filterToken: FilterToken          => applyFilter(node, filterToken, acceptMissingFields)

      case RecursiveAnyField                 => Iterator.single(node) ++ recFieldExplorer(node)
    }

  private[this] def recFilter(node: Any, filterToken: FilterToken, acceptMissingFields: Boolean): Iterator[Any] = {

    def allNodes(curr: Any): Iterator[Any] = curr match {
      case array: JList[_]                 => array.asScala.iterator.flatMap(allNodes)
      case obj: JMap[_, _] if !obj.isEmpty => Iterator.single(obj) ++ obj.values.iterator.asScala.flatMap(allNodes)
      case _                               => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
    }

    allNodes(node).flatMap(applyFilter(_, filterToken, acceptMissingFields))
  }

  private[this] def applyFilter(currentNode: Any, filterToken: FilterToken, acceptMissingFields: Boolean): Iterator[Any] = {

    def resolveSubQuery(node: Any, q: List[AST.PathToken], nextOp: Any => Boolean): Boolean = {
      val it = walk(node, q, acceptMissingFields)
      it.hasNext && nextOp(it.next())
    }

    def applyBinaryOpWithResolvedLeft(node: Any, op: ComparisonOperator, lhsNode: Any, rhs: FilterValue): Boolean =
      rhs match {
        case direct: FilterDirectValue => op(lhsNode, direct.value)
        case SubQuery(q)               => resolveSubQuery(node, q, op(lhsNode, _))
      }

    def applyBinaryOp(node: Any, op: ComparisonOperator, lhs: FilterValue, rhs: FilterValue): Boolean =
      lhs match {
        case direct: FilterDirectValue => applyBinaryOpWithResolvedLeft(node, op, direct.value, rhs)
        case SubQuery(q)               => resolveSubQuery(node, q, applyBinaryOpWithResolvedLeft(node, op, _, rhs))
      }

    def elementsToFilter(node: Any): Iterator[Any] =
      node match {
        case array: JList[_] => array.asScala.iterator
        case obj: JMap[_, _] => Iterator.single(obj)
        case _               => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

    def evaluateFilter(filterToken: FilterToken): Any => Boolean =
      filterToken match {
        case HasFilter(subQuery) =>
          (node: Any) => walk(node, subQuery.path, acceptMissingFields).hasNext

        case ComparisonFilter(op, lhs, rhs) =>
          (node: Any) => applyBinaryOp(node, op, lhs, rhs)

        case BooleanFilter(op, filter1, filter2) =>
          val f1 = evaluateFilter(filter1)
          val f2 = evaluateFilter(filter2)
          (node: Any) => op(f1(node), f2(node))
      }

    val filterFunction = evaluateFilter(filterToken)
    elementsToFilter(currentNode).filter(filterFunction)
  }

  def recFieldFilter(node: Any, name: String): Iterator[Any] = {
    def recFieldFilter0(node: Any): Iterator[Any] =
      node match {
        case obj: JMap[_, _] =>
          obj.entrySet.iterator.asScala.flatMap(e => e.getKey match {
            case `name` => Iterator.single(e.getValue)
            case _      => recFieldFilter0(e.getValue)
          })
        case list: JList[_] => list.iterator.asScala.flatMap(recFieldFilter0)
        case _              => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
      }

    recFieldFilter0(node)
  }

  def recFieldExplorer(node: Any): Iterator[Any] =
    node match {
      case obj: JMap[_, _] =>
        val values = obj.values
        values.iterator.asScala ++ values.iterator.asScala.flatMap(recFieldExplorer)
      case list: JList[_] =>
        list.iterator.asScala.flatMap(recFieldExplorer)
      case _ => if (acceptMissingFields) Iterator.single(null) else Iterator.empty
    }

  private[this] def sliceArray(array: JList[_], start: Option[Int], stop: Option[Int], step: Int): Iterator[Any] = {
    val size = array.size

    def lenRelative(x: Int) = if (x >= 0) x else size + x
    def stepRelative(x: Int) = if (step >= 0) x else -1 - x
    def relative(x: Int) = lenRelative(stepRelative(x))

    val absStart = start match {
      case Some(v) => relative(v)
      case _       => 0
    }
    val absEnd = stop match {
      case Some(v) => relative(v)
      case _       => size
    }
    val absStep = abs(step)

    val elements: Iterator[Any] = if (step < 0) array.asScala.reverseIterator else array.asScala.iterator
    val fromStartToEnd = elements.slice(absStart, absEnd)

    if (absStep == 1)
      fromStartToEnd
    else
      fromStartToEnd.grouped(absStep).map(_.head)
  }
}
