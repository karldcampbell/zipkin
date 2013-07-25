/*
 * Copyright 2013 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.zipkin.storage.anormdb

import com.twitter.util.{Future, Time}
import com.twitter.conversions.time._
import com.twitter.zipkin.common.{Service, DependencyLink, Dependencies}
import com.twitter.zipkin.storage.Aggregates
import java.sql.Connection
import anorm._
import anorm.SqlParser._
import com.twitter.algebird.Moments

case class AnormAggregates(db: DB, openCon: Option[Connection] = None) extends Aggregates {
  // Database connection object
  private implicit val conn = openCon match {
    case None => db.getConnection()
    case Some(con) => con
  }

  /**
   * Close the index
   */
  def close() { conn.close() }

  /**
   * Get the top annotations for a service name
   */
  def getDependencies(startDate: Time, endDate: Option[Time]=None): Future[Dependencies] = {

    // floor to nearest day in microseconds
    val realStart = startDate.floor(1.day).inMicroseconds
    val realEnd = endDate.getOrElse(startDate).floor(1.day).inMicroseconds

    val result: List[DependLink] = SQL(
      """SELECT l.dlid, startTs, endTs, parent, child, m0, m1, m2, m3, m4
        |FROM zipkin_dependency_links AS l
        |LEFT JOIN zipkin_dependencies AS d
        |  ON l.dlid = d.dlid
        |WHERE startTs >= {startTs}
        |  AND endTs <= {endTs}
        |ORDER BY l.dlid DESC
      """.stripMargin)
    .on("startTs" -> realStart)
    .on("endTs" -> realEnd)
    .as((long("l.dlid") ~ long("startTs") ~ long("endTs") ~ str("parent") ~ str("child") ~ long("m0") ~ get[Double]("m1") ~ get[Double]("m2") ~ get[Double]("m3") ~ get[Double]("m4") map {
      case a~b~c~d~e~f~g~h~i~j => DependLink(a, b, c, d, e, f, g, h, i, j)
    }) *)

    Future {
      val deps: List[(Long, Time, Time)] = result.map {
        d => (d.dlid, Time.fromMilliseconds(d.startTs), Time.fromMilliseconds(d.endTs))
      }
      deps.map { dep =>
        val links = result.filter(_.dlid == dep._1).map { link =>
          val moments = new Moments(link.m0, link.m1, link.m2, link.m3, link.m4)
          new DependencyLink(new Service(link.parent), new Service(link.child), moments)
        }
        new Dependencies(dep._2, dep._3, links)
      }
    }
  }

  /**
   * Synchronize these so we don't do concurrent writes from the same box
   */
  def storeDependencies(dependencies: Dependencies): Future[Unit] = {
    val dlid = SQL("""INSERT INTO zipkin_dependencies
          |  (startTs, endTs)
          |VALUES ({startTs}, {endTs})
        """.stripMargin)
      .on("startTs" -> dependencies.startTime.inMicroseconds)
      .on("endTs" -> dependencies.endTime.inMicroseconds)
    .executeInsert()

    dependencies.links.foreach { link =>
      SQL("""INSERT INTO zipkin_dependency_links
            |  (dlid, parent, child, m0, m1, m2, m3, m4)
            |VALUES ({dlid}, {parent}, {child}, {m0}, {m1}, {m2}, {m3}, {m4})
          """.stripMargin)
        .on("dlid" -> dlid)
        .on("parent" -> link.parent.name)
        .on("child" -> link.child.name)
        .on("m0" -> link.durationMoments.m0)
        .on("m1" -> link.durationMoments.m1)
        .on("m2" -> link.durationMoments.m2)
        .on("m3" -> link.durationMoments.m3)
        .on("m4" -> link.durationMoments.m4)
      .execute()
    }

    Future.Unit
  }

  /**
   * Get the top annotations for a service name
   */
  def getTopAnnotations(serviceName: String): Future[Seq[String]] = {

  }

  /**
   * Get the top key value annotation keys for a service name
   */
  def getTopKeyValueAnnotations(serviceName: String): Future[Seq[String]] = {

  }

  /**
   * Override the top annotations for a service
   */
  def storeTopAnnotations(serviceName: String, a: Seq[String]): Future[Unit] = {

  }

  /**
   * Override the top key value annotation keys for a service
   */
  def storeTopKeyValueAnnotations(serviceName: String, a: Seq[String]): Future[Unit] = {

  }

  case class DependLink(dlid: Long, startTs: Long, endTs: Long, parent: String, child: String, m0: Long, m1: Double, m2: Double, m3: Double, m4: Double)
}