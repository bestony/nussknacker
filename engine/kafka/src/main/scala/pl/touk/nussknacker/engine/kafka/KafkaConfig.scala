package pl.touk.nussknacker.engine.kafka

case class KafkaConfig(zkAddress: String, kafkaAddress: String,
                       kafkaProperties: Option[Map[String, String]], kafkaEspProperties: Option[Map[String, String]])
