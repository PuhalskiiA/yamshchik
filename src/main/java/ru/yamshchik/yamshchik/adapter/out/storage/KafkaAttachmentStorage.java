package ru.yamshchik.yamshchik.adapter.out.storage;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.yamshchik.yamshchik.application.port.out.AttachmentStorage;
import ru.yamshchik.yamshchik.config.exception.type.AttachmentStorageException;
import ru.yamshchik.yamshchik.config.exception.type.ServiceException;
import ru.yamshchik.yamshchik.config.properties.StorageProperties;
import ru.yamshchik.yamshchik.config.properties.YamshchikProperties;
import ru.yamshchik.yamshchik.domain.Attachment;
import ru.yamshchik.yamshchik.domain.AttachmentContent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Вложения в топике Kafka: одно вложение — одно сообщение. Ключом хранения служит место сообщения
 * в журнале («раздел/смещение»), по нему сообщение читается без просмотра топика.
 * <p>
 * Ограничения: вложение целиком держится в памяти при записи и чтении; удалить отдельное сообщение
 * нельзя — оно уходит вместе с сегментом журнала по сроку хранения топика.
 */
@Component
@ConditionalOnProperty(name = StorageProperties.TYPE_PROPERTY, havingValue = StorageProperties.TYPE_KAFKA)
public class KafkaAttachmentStorage implements AttachmentStorage {

    private static final String KEY_SEPARATOR = "/";

    private static final String ACKS_ALL = "all";

    // Смещение, которого в журнале уже нет, — это ошибка, а не повод начать читать с другого места
    private static final String OFFSET_RESET_NONE = "none";

    private static final Duration IO_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    private static final String MISSING_MESSAGE = "Attachment content is missing: ";

    private static final String UNAVAILABLE_MESSAGE = "Attachment storage is unavailable: ";

    private final String topic;

    private final Map<String, Object> consumerConfig;

    private final KafkaProducer<String, byte[]> producer;

    public KafkaAttachmentStorage(YamshchikProperties properties) {
        StorageProperties.Kafka kafka = properties.getStorage().getKafka();
        int maxRecordSize = Math.toIntExact(kafka.getMaxRecordSize().toBytes());
        this.topic = kafka.getTopic();
        this.consumerConfig = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OFFSET_RESET_NONE,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1,
                ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxRecordSize,
                ConsumerConfig.FETCH_MAX_BYTES_CONFIG, maxRecordSize);
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, ACKS_ALL,
                ProducerConfig.MAX_REQUEST_SIZE_CONFIG, maxRecordSize));
    }

    @Override
    public String store(Attachment attachment, AttachmentContent content) {
        try (InputStream input = content.open()) {
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(topic, attachment.storageKey(), input.readAllBytes());
            RecordMetadata stored = producer.send(record).get(IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return stored.partition() + KEY_SEPARATOR + stored.offset();
        } catch (IOException | ExecutionException | TimeoutException e) {
            throw new ServiceException("Failed to write attachment content: " + attachment.storageKey(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Interrupted while writing attachment content: " + attachment.storageKey(), e);
        }
    }

    @Override
    public InputStream open(Attachment attachment) {
        String[] location = attachment.storageKey().split(KEY_SEPARATOR);
        TopicPartition partition = new TopicPartition(topic, Integer.parseInt(location[0]));
        long offset = Long.parseLong(location[1]);

        // Потребитель не потокобезопасен, а обработчиков отправки несколько: на каждое чтение свой
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerConfig)) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            long deadline = System.nanoTime() + IO_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(POLL_TIMEOUT)) {
                    if (record.offset() == offset) {
                        return new ByteArrayInputStream(record.value());
                    }
                }
            }
            throw new AttachmentStorageException(UNAVAILABLE_MESSAGE + "read timed out", true, null);
        } catch (OffsetOutOfRangeException e) {
            throw new AttachmentStorageException(MISSING_MESSAGE + attachment.storageKey(), false, e);
        } catch (KafkaException e) {
            throw new AttachmentStorageException(UNAVAILABLE_MESSAGE + e.getMessage(), true, e);
        }
    }

    @Override
    public void delete(Attachment attachment) {
        // Отдельное сообщение из журнала не удаляется: его уберёт срок хранения топика
    }

    @PreDestroy
    public void close() {
        producer.close(IO_TIMEOUT);
    }
}
