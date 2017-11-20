package hydra.kafka.transport

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import hydra.core.transport.Transport
import hydra.core.transport.Transport.{Deliver, TransportError}
import hydra.kafka.config.KafkaConfigSupport
import hydra.kafka.producer.{JsonRecord, StringRecord}
import hydra.kafka.transport.KafkaProducerProxy.ProducerInitializationError
import hydra.kafka.transport.KafkaTransport.RecordProduceError
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.common.errors.TimeoutException
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.concurrent.duration._

/**
  * Created by alexsilva on 12/5/16.
  */
class KafkaTransportSpec extends TestKit(ActorSystem("hydra")) with Matchers with FunSpecLike with ImplicitSender
  with BeforeAndAfterAll with KafkaConfigSupport {

  val producerName = StringRecord("transport_test", Some("key"), "payload").formatName

  val kafkaSupervisor = TestActorRef[KafkaTransport](KafkaTransport.props(kafkaProducerFormats), "kafka")

  implicit val config = EmbeddedKafkaConfig(kafkaPort = 8092, zooKeeperPort = 3181,
    customBrokerProperties = Map("auto.create.topics.enable" -> "false"))

  val ingestor = TestProbe()
  val streamActor = TestProbe()

  system.eventStream.subscribe(streamActor.ref, classOf[RecordProduceError])
  system.eventStream.subscribe(streamActor.ref, classOf[ProducerInitializationError])

  override def beforeAll() = {
    super.beforeAll()
    EmbeddedKafka.start()
    EmbeddedKafka.createCustomTopic("transport_test")
  }

  override def afterAll() = {
    super.afterAll()
    EmbeddedKafka.stop()
    system.stop(kafkaSupervisor)
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
  }

  describe("When using the KafkaTransport") {

    it("has the right producers") {
      kafkaSupervisor.underlyingActor.producers.size shouldBe 2
      kafkaSupervisor.underlyingActor.producers.keys should contain allOf("avro", "string")
    }

    it("errors if no proxy can be found for the format") {
      val probe = TestProbe()
      val ack: Transport.AckCallback = (md, err) => probe.ref ! err.get
      kafkaSupervisor ! Deliver(JsonRecord("transport_test", Some("key"), """{"name":"alex"}"""), 1, ack)
      probe.expectMsgType[IllegalArgumentException]
      expectMsg(TransportError(1))
    }

    it("forwards to the right proxy") {
      val ack: Transport.AckCallback = (md, err) => ingestor.ref ! "DONE"
      val rec = StringRecord("transport_test", Some("key"), "payload")
      kafkaSupervisor ! Deliver(rec, 1, ack)
      ingestor.expectMsg(max = 10.seconds, "DONE")
    }

    it("publishes errors to the stream") {
      val rec = StringRecord("unknown_topic", Some("key"), "payload")
      kafkaSupervisor ! Deliver(rec)
      streamActor.expectMsgPF(max = 15.seconds) { //need max so Kafka times out
        case RecordProduceError(deliveryId, r, err) =>
          deliveryId shouldBe -1
          r shouldBe rec
          err shouldBe a[TimeoutException]
      }
    }

    it("publishes producer init errors to the stream") {
      system.actorOf(KafkaTransport.props(Map("frmt" -> ConfigFactory.parseString("format=false"))))
      streamActor.expectMsgPF() { case ProducerInitializationError("frmt", err) =>
        err shouldBe a[IllegalArgumentException]
      }
    }
  }
}
