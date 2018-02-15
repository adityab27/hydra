/*
 * Copyright (C) 2017 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hydra.kafka.producer

import java.io.{File, InputStream}

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.TestKit
import com.fasterxml.jackson.databind.ObjectMapper
import hydra.avro.resource.SchemaResource
import hydra.core.akka.SchemaFetchActor.{FetchSchema, SchemaFetchResponse}
import hydra.core.ingest.{HydraRequest, RequestParams}
import hydra.core.ingest.RequestParams.{HYDRA_KAFKA_TOPIC_PARAM, HYDRA_RECORD_FORMAT_PARAM, HYDRA_SCHEMA_PARAM}
import hydra.core.protocol.InvalidRequest
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecordBuilder
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source

/**
  * Created by alexsilva on 1/11/17.
  */
class KafkaRecordFactoriesSpec extends TestKit(ActorSystem("hydra"))
  with Matchers
  with FunSpecLike
  with ScalaFutures
  with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(
    timeout = scaled(200 millis),
    interval = scaled(100 millis)
  )

  val schemaResource = new SchemaResource {

    val testSchema = Thread.currentThread()
      .getContextClassLoader.getResource("avro-factory-test.avsc").getFile

    override def schema: Schema = new Schema.Parser().parse(new File(testSchema))

    override def id: Int = 1

    override def version: Int = 1

    override def location: String = "location"

    override def getDescription: String = "description"

    override def getInputStream: InputStream = null //not testing this
  }
  val loader = system.actorOf(Props(new Actor() {
    override def receive: Receive = {
      case FetchSchema(_) => sender ! SchemaFetchResponse(schemaResource)
    }
  }))

  val factories = new KafkaRecordFactories(loader)

  override def afterAll = TestKit.shutdownActorSystem(system)

  describe("When using KafkaRecordFactories") {
    it("handles avro") {
      val json = """{"name":"test", "rank":10}"""
      val request = HydraRequest("123", json)
        .withMetadata(HYDRA_SCHEMA_PARAM -> "classpath:kafka-factories-test.avsc")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = factories.build(request)
      val avroSchema = new Schema.Parser().parse(Source.fromResource("kafka-factories-test.avsc").mkString)
      val genericRecord = new GenericRecordBuilder(avroSchema).set("name", "test").set("rank", 10).build()

      whenReady(record)(_ shouldBe AvroRecord("test-topic", avroSchema, None, genericRecord))
    }

    it("handles delete records") {
      val request = HydraRequest("123", "test")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic",
          RequestParams.HYDRA_DELETE_DIRECTIVE -> "true",
          RequestParams.HYDRA_RECORD_KEY_PARAM -> "123")
      val record = factories.build(request)
      whenReady(record)(_ shouldBe DeleteTombstoneRecord("test-topic", Some("123")))
    }

    it("handles json") {
      val json = """{"name":"test", "rank":10}"""
      val request = HydraRequest("123", json)
        .withMetadata(HYDRA_SCHEMA_PARAM -> "classpath:kafka-factories-test.avsc")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "json")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = factories.build(request)
      whenReady(record)(_ shouldBe JsonRecord("test-topic", None,
        new ObjectMapper().reader().readTree(json)))
    }

    it("handles strings") {
      val json = """{"name":"test", "rank":10}"""
      val request = HydraRequest("123", json)
        .withMetadata(HYDRA_SCHEMA_PARAM -> "classpath:kafka-factories-test.avsc")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "string")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = factories.build(request)
      whenReady(record)(_ shouldBe StringRecord("test-topic", None, json))
    }

    it("validates json records") {
      val request = HydraRequest("123","""{"name":"test"}""")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "json")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = factories.build(request)
      whenReady(record)(_ shouldBe a[JsonRecord])
    }

    it("validates string records") {
      val request = HydraRequest("123","""{"name":"test"}""")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "string")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = factories.build(request)
      whenReady(record)(_ shouldBe a[StringRecord])
    }

    it("validates avro records") {
      val schema = Thread.currentThread().getContextClassLoader.getResource("kafka-factories-test.avsc").getFile
      val avroSchema = new Schema.Parser().parse(new File(schema))
      val json = """{"name":"test", "rank":10}"""
      val request = HydraRequest("123", json)
        .withMetadata(HYDRA_SCHEMA_PARAM -> "classpath:kafka-factories-test.avsc")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val record = new AvroRecordFactory(loader).build(request)
      whenReady(record)(_ shouldBe a[AvroRecord])
    }

    it("invalidates unknown formats") {
      val request = HydraRequest("123","""{"name":"test"}""")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "unknown-format")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      val rec = factories.build(request)
      whenReady(rec.failed)(ex => ex shouldBe InvalidRequest(_: IllegalArgumentException))
    }

    it("throws error with unknown formats") {
      val json = """{"name":"test", "rank":10}"""
      val request = HydraRequest("123", json)
        .withMetadata(HYDRA_SCHEMA_PARAM -> "classpath:kafka-factories-test.avsc")
        .withMetadata(HYDRA_RECORD_FORMAT_PARAM -> "unknown")
        .withMetadata(HYDRA_KAFKA_TOPIC_PARAM -> "test-topic")
      whenReady(factories.build(request).failed)(_ shouldBe an[IllegalArgumentException])
    }
  }
}