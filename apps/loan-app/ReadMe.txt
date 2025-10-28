
go to kafka\bin\windows then open cmd there and run the following commands:
# Create topics for the loan processing system

kafka-topics.bat --create --topic loan-application-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic credit-check-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic property-valuation-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic document-verification-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic loan-processing-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic notification-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic saga-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic saga-orchestration-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic saga-compensation-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
kafka-topics.bat --create --topic saga-dead-letter-events --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1

# List all topics
kafka-topics.bat --bootstrap-server localhost:9092 --list



